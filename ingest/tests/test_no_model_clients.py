"""ingest does not call any model and has no route to one (LAB-84, FR-001/FR-002, research R-14).

It only parses and splits documents. When a first call to a model is added it must go through the model gateway with
the virtual key of ingest; until then nothing here may import a model client or name a model server.
"""

import pathlib
import re

APP = pathlib.Path(__file__).resolve().parents[1] / "app"
MODEL_CLIENTS = re.compile(r"^\s*(?:import|from)\s+(openai|ollama|litellm|anthropic|langchain\w*)\b", re.MULTILINE)
MODEL_SERVER = re.compile(r"11434|OLLAMA|ollama:\d+|ollama\.com", re.IGNORECASE)


def _sources():
    return sorted(APP.rglob("*.py"))


def test_there_are_sources_to_check():
    assert _sources()


def test_no_model_client_is_imported():
    offenders = [str(p) for p in _sources() if MODEL_CLIENTS.search(p.read_text(encoding="utf-8"))]
    assert offenders == []


def test_no_model_server_address_is_named():
    offenders = [str(p) for p in _sources() if MODEL_SERVER.search(p.read_text(encoding="utf-8"))]
    assert offenders == []
