"""Writes the OpenAPI contract of POST /ingest to ingest/openapi.json: `uv run python -m app.openapi_export`.

The file is versioned; ingest/tests/test_openapi_contract.py fails when the service no longer matches it, and the
backend contract test (retrieval) reads it too."""

import json
from pathlib import Path

from app.main import create_app

CONTRACT = Path(__file__).resolve().parents[1] / "openapi.json"


def render() -> str:
    return json.dumps(create_app().openapi(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    CONTRACT.write_text(render(), encoding="utf-8", newline="\n")
    print(f"Wrote {CONTRACT}")
