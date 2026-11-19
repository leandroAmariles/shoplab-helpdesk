import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

import check_models


def _config(api_base: str) -> str:
    return f"""
model_list:
  - model_name: chat
    litellm_params: {{ model: "ollama_chat/llama3.2:3b", api_base: "{api_base}" }}
  - model_name: chat-fallback
    litellm_params: {{ model: "ollama_chat/llama3.2:1b", api_base: "{api_base}" }}
  - model_name: embeddings
    litellm_params: {{ model: "ollama/bge-m3", api_base: "{api_base}" }}
  - model_name: chat-cloud
    litellm_params: {{ model: "openai/some-model" }}
"""


@pytest.fixture
def ollama():
    models = ["llama3.2:3b", "bge-m3:latest"]

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            body = json.dumps({"models": [{"name": m, "model": m} for m in models]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()


def test_reports_only_the_model_that_is_not_downloaded(ollama, tmp_path, capsys):
    config = tmp_path / "config.yaml"
    config.write_text(_config(ollama), encoding="utf-8")
    assert check_models.main(["check_models.py", str(config)]) == 0
    out = capsys.readouterr().out.strip().splitlines()
    assert out == ["WARN model_missing model=llama3.2:1b name=chat-fallback"]


def test_does_not_check_cloud_models(ollama, tmp_path, capsys):
    config = tmp_path / "config.yaml"
    config.write_text(_config(ollama), encoding="utf-8")
    check_models.main(["check_models.py", str(config)])
    assert "chat-cloud" not in capsys.readouterr().out


def test_unreachable_ollama_warns_and_does_not_fail(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("CHECK_MODELS_TIMEOUT", "0")
    config = tmp_path / "config.yaml"
    config.write_text(_config("http://127.0.0.1:1"), encoding="utf-8")
    assert check_models.main(["check_models.py", str(config)]) == 0
    out = capsys.readouterr().out
    assert "WARN ollama_unreachable" in out
    assert "model_missing" not in out


def test_missing_argument_does_not_fail(capsys):
    assert check_models.main(["check_models.py"]) == 0


def test_with_tag_adds_latest_only_when_no_tag():
    assert check_models.with_tag("bge-m3") == "bge-m3:latest"
    assert check_models.with_tag("llama3.2:3b") == "llama3.2:3b"
