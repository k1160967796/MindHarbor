"""Fix PaddleX 3.3 eager chart loading when chart recognition is disabled.

The chart path is handled by Ollama in this application. Fail the build on an
unexpected upstream source instead of silently assuming this patch still fits.
"""
from pathlib import Path
from importlib.util import find_spec

root = Path(find_spec("paddlex").origin).parent
path = root / "inference/pipelines/layout_parsing/pipeline_v2.py"
source = path.read_text(encoding="utf-8")
old = """        self.chart_recognition_model = self.create_model(
            chart_recognition_config,
        )"""
new = """        self.chart_recognition_model = (
            self.create_model(chart_recognition_config)
            if self.use_chart_recognition else None
        )"""
if source.count(old) != 1:
    raise RuntimeError("PaddleX chart initialization changed; review the compatibility patch")
path.write_text(source.replace(old, new), encoding="utf-8")
