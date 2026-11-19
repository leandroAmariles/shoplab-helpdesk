"""A local HTTP server that plays the LiteLLM gateway: its model list and the real model behind each logical name.

Like ``fake_backend``, it is real network code in a thread, so the client of the gateway is tested without patching
``urllib``. It checks the ``Authorization`` header like the gateway does (a wrong key is a 401) and records every
request, so a test can prove that the key travels only in that header.
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

GATEWAY_KEY = "sk-clave-valida-del-gateway"


class FakeGateway:
    """Serves ``GET /v1/models`` and ``GET /model/info`` (the second one is at the root, as LiteLLM has it)."""

    def __init__(self, models: dict[str, str] | None = None, key: str = GATEWAY_KEY) -> None:
        # logical name -> the real model behind it, as ``litellm_params.model`` shows it
        self.models = {"judge": "ollama_chat/qwen2.5:7b", "embeddings": "ollama/bge-m3"} if models is None else models
        self.key = key
        self.requests: list[dict[str, str]] = []
        self.models_status = 200
        self.info_status = 200
        self.info_body: object | None = None
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                gateway.requests.append({"path": self.path, "authorization": self.headers.get("Authorization", "")})
                if self.headers.get("Authorization") != f"Bearer {gateway.key}":
                    self._send(401, {"error": {"message": "Authentication Error, invalid key"}})
                elif self.path == "/v1/models":
                    status = gateway.models_status
                    body = {"data": [{"id": name, "object": "model"} for name in gateway.models]}
                    self._send(status, body if status == 200 else {"error": "boom"})
                elif self.path == "/model/info":
                    status = gateway.info_status
                    body = gateway.info_body
                    if body is None:
                        body = {
                            "data": [
                                {"model_name": name, "litellm_params": {"model": real}}
                                for name, real in gateway.models.items()
                            ]
                        }
                    self._send(status, body if status == 200 else {"error": "boom"})
                else:
                    self._send(404, {"error": "not found"})

            def _send(self, status: int, body: object) -> None:
                payload = json.dumps(body).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, format: str, *args: object) -> None:
                return

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)

    @property
    def root(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    @property
    def url(self) -> str:
        """The base URL of the OpenAI-compatible API, as the judge is configured (``…/v1``)."""
        return f"{self.root}/v1"

    def start(self) -> "FakeGateway":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)
