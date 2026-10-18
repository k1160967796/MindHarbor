"""Contract tests run without downloading Paddle weights. Run: python -m unittest -v"""
import io
import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from PIL import Image
import app as service


class FakeResult:
    json = {"res": {"parsing_res_list": [
        {"block_label": "header", "block_content": "ignore"},
        {"block_label": "table", "block_content": "<table><tr><td>保留表格</td></tr></table>",
         "block_bbox": [0, 0, 10, 10]},
        {"block_label": "display_formula", "block_content": "x^2", "block_bbox": [0, 10, 10, 20]},
        {"block_label": "image", "block_content": "", "block_bbox": [0, 20, 10, 30]},
    ]}}


class ParserTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(service.app)
        self.image = io.BytesIO()
        Image.new("RGB", (40, 40), "white").save(self.image, format="PNG")

    def test_normalizes_structured_blocks_in_reading_order(self):
        blocks = service.normalize_blocks(FakeResult())
        self.assertEqual([b["type"] for b in blocks], ["table", "formula", "image"])
        self.assertIn("<table>", blocks[0]["text"])
        self.assertTrue(all(b["needsReview"] for b in blocks))

    def test_endpoint_contract(self):
        with patch.object(service, "parse_image", return_value={"blocks": service.normalize_blocks(FakeResult())}):
            response = self.client.post("/parse", files={"file": ("scan.png", self.image.getvalue(), "image/png")})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["blocks"][1]["text"], "x^2")

    def test_invalid_image_rejected_before_model(self):
        with patch.object(service, "parse_image") as parser:
            response = self.client.post("/parse", files={"file": ("bad.png", b"not an image", "image/png")})
        self.assertEqual(response.status_code, 422)
        parser.assert_not_called()

    def test_token_and_busy_status(self):
        with patch.dict(os.environ, {"DOCUMENT_PARSER_TOKEN": "test-token"}):
            response = self.client.post("/parse", files={"file": ("scan.png", self.image.getvalue())})
            self.assertEqual(response.status_code, 401)
            service._lock.acquire()
            try:
                response = self.client.post("/parse", headers={"Authorization": "Bearer test-token"},
                                            files={"file": ("scan.png", self.image.getvalue())})
                self.assertEqual(response.status_code, 429)
            finally:
                service._lock.release()


if __name__ == "__main__":
    unittest.main()
