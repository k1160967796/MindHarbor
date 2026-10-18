"""Upload-time OCR/layout service. Run one worker; Paddle pipelines are not thread-safe."""
import io
import json
import os
import secrets
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

app = FastAPI(title="MindBridge document parser")
_pipeline = None
_lock = threading.Lock()
MAX_BYTES = 32 * 1024 * 1024
MAX_PIXELS = 20_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def normalize_blocks(result):
    """Normalize PP-StructureV3's JSON result; fail visibly on an incompatible schema."""
    payload = result.json
    if callable(payload):
        payload = payload()
    if isinstance(payload, str):
        payload = json.loads(payload)
    payload = payload.get("res", payload)
    if not isinstance(payload.get("parsing_res_list"), list):
        raise ValueError("Unsupported PP-StructureV3 result schema")
    blocks = []
    for block in payload["parsing_res_list"]:
        label = block.get("block_label", "text")
        if label in {"header", "footer", "number", "header_image", "footer_image"}:
            continue
        kind = {"table": "table", "formula": "formula", "display_formula": "formula",
                "inline_formula": "formula", "image": "image", "chart": "chart"}.get(label, "text")
        blocks.append({"type": kind, "text": str(block.get("block_content", "") or ""),
                       "bbox": [int(x) for x in block.get("block_bbox", [])], "needsReview": True})
    return blocks


def parse_image(data):
    global _pipeline
    # Bound inference concurrency, including initial model loading.
    if not _lock.acquire(blocking=False):
        raise HTTPException(429, "Document parser is busy; retry later")
    try:
        if _pipeline is None:
            from paddleocr import PPStructureV3
            _pipeline = PPStructureV3(device=os.getenv("PADDLE_DEVICE", "cpu"),
                                      use_doc_orientation_classify=False,
                                      use_doc_unwarping=False,
                                      use_textline_orientation=False,
                                      use_seal_recognition=False,
                                      # Charts are interpreted by the dedicated Ollama vision step.
                                      use_chart_recognition=False,
                                      cpu_threads=2,
                                      use_table_recognition=True,
                                      use_formula_recognition=True)
        with tempfile.TemporaryDirectory(prefix="mindbridge-ocr-") as directory:
            image_path = Path(directory) / "page.png"
            with Image.open(io.BytesIO(data)) as image:
                image.convert("RGB").save(image_path)
            blocks = []
            for result in _pipeline.predict(input=str(image_path)):
                blocks.extend(normalize_blocks(result))
            return {"blocks": blocks}
    finally:
        _lock.release()


@app.get("/health")
def health():
    return {"status": "up", "modelsLoaded": _pipeline is not None}


@app.post("/parse")
async def parse(file: UploadFile = File(...), authorization: str | None = Header(default=None)):
    token = os.getenv("DOCUMENT_PARSER_TOKEN", "")
    if token and not secrets.compare_digest(authorization or "", "Bearer " + token):
        raise HTTPException(401, "Unauthorized")
    try:
        data = await file.read(MAX_BYTES + 1)
    finally:
        await file.close()
    if not data or len(data) > MAX_BYTES:
        raise HTTPException(413, "Empty or oversized image")
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format not in {"PNG", "JPEG"} or image.width * image.height > MAX_PIXELS:
                raise HTTPException(422, "Unsupported image format or dimensions")
            image.verify()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
        raise HTTPException(422, "Invalid image") from error
    try:
        return await run_in_threadpool(parse_image, data)
    except HTTPException:
        raise
    except Exception as error:
        # Document contents are intentionally not logged.
        raise HTTPException(503, "OCR unavailable; check model installation and service configuration") from error
