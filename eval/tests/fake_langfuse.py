"""A local HTTP server that plays Langfuse 4.x in ``events_only`` mode, as verified in specs/LAB-29 (validation.md, S-3).

In that mode the read endpoints of traces and scores do not exist (404): a trace is found through
``GET /api/public/v2/observations?traceId=…`` and a score can only be written, with ``POST /api/public/scores``. The
server records every score it receives by ``id``, so a test can see that publishing twice leaves a single score.
"""

import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PUBLIC_KEY = "pk-lf-prueba"
SECRET_KEY = "sk-lf-prueba-secreta"


class FakeLangfuse:
    def __init__(self, public_key: str = PUBLIC_KEY, secret_key: str = SECRET_KEY) -> None:
        self.credentials = base64.b64encode(f"{public_key}:{secret_key}".encode()).decode()
        # trace id -> the observations the API lists for it (what the backend exported)
        self.traces: dict[str, list[dict[str, object]]] = {}
        # trace id -> how many lookups answer «not yet» before the trace shows up (the export takes a moment)
        self.delay: dict[str, int] = {}
        self.scores: dict[str, dict[str, object]] = {}
        self.score_requests: list[dict[str, object]] = []
        self.requests: list[dict[str, str]] = []
        self.score_status = 200
        self.fail_scores_for: set[str] = set()
        self.projects_status = 200
        langfuse = self

        class Handler(BaseHTTPRequestHandler):
            def _authorised(self) -> bool:
                return self.headers.get("Authorization") == f"Basic {langfuse.credentials}"

            def do_GET(self) -> None:
                parts = urlsplit(self.path)
                langfuse.requests.append({"path": parts.path, "authorization": self.headers.get("Authorization", "")})
                if not self._authorised():
                    self._send(401, {"message": "Invalid credentials", "error": "UnauthorizedError"})
                elif parts.path == "/api/public/projects":
                    self._send(langfuse.projects_status, {"data": [{"id": "shoplab-helpdesk"}]})
                elif parts.path == "/api/public/v2/observations":
                    trace_id = parse_qs(parts.query).get("traceId", [""])[0]
                    if langfuse.delay.get(trace_id, 0) > 0:
                        langfuse.delay[trace_id] -= 1
                        self._send(200, {"data": [], "meta": {}})
                    else:
                        self._send(200, {"data": langfuse.traces.get(trace_id, []), "meta": {}})
                else:
                    # events_only: GET /api/public/traces, /traces/{id} and the score reads do not exist
                    self._send(404, {"message": "This endpoint is not available in events_only mode"})

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
                langfuse.requests.append({"path": self.path, "authorization": self.headers.get("Authorization", "")})
                if not self._authorised():
                    self._send(401, {"message": "Invalid credentials"})
                elif self.path == "/api/public/scores":
                    langfuse.score_requests.append(payload)
                    if payload.get("traceId") in langfuse.fail_scores_for:
                        self._send(500, {"message": "boom"})
                    elif langfuse.score_status != 200:
                        self._send(langfuse.score_status, {"message": "rejected"})
                    else:
                        langfuse.scores[str(payload["id"])] = payload  # the id is the key: a repeat replaces
                        self._send(200, {"id": payload["id"]})
                else:
                    self._send(404, {"message": "not found"})

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
    def url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def add_trace(self, trace_id: str, model: str | None = None) -> None:
        """The trace is there, with a ``generation`` observation that names the model when one is given."""
        observations: list[dict[str, object]] = [{"traceId": trace_id, "name": "chat-turn", "type": "SPAN"}]
        if model:
            observations.append({"traceId": trace_id, "name": "generation", "type": "GENERATION",
                                 "providedModelName": model})  # fmt: skip
        self.traces[trace_id] = observations

    def start(self) -> "FakeLangfuse":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)
