"""A local HTTP server that plays the backend of the knowledge search, and a fake searcher without HTTP.

The client and the command are tested against the real network code (a ``ThreadingHTTPServer`` in a thread), so no
test patches ``urllib``.
"""

import json
import threading
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from shoplab_eval.golden import GoldenCase
from shoplab_eval.search_client import IndexedDocument, SearchHit, SearchResponse

MODEL = "bge-m3"
SEARCH_PATH = "/api/knowledge/search"
DOCUMENTS_PATH = "/api/documents"

# A handler answers (status, body); a dict or list body is sent as JSON, a str or bytes body as is.
Reply = tuple[int, object]
SearchHandler = Callable[[str, int | None], Reply]

CHAT_PATH = "/api/chat"


@dataclass
class ChatReply:
    """What ``POST /api/chat`` answers: a server-sent event stream (``events``) or a JSON error (``body``).

    ``events`` are ``(name, data)`` pairs; the stream is sent as is, so a list without ``done`` is a cut stream.
    ``headers`` overrides the response headers (by default ``X-Request-Id`` echoes the request's, like the real filter).
    """

    status: int = 200
    events: list[tuple[str, object]] | None = None
    body: object = None
    headers: dict[str, str] = field(default_factory=dict)


ChatHandler = Callable[[str, dict[str, str]], ChatReply]


def sse_text(events: Sequence[tuple[str, object]]) -> str:
    """The wire format of the chat stream: ``event:`` and a one-line JSON ``data:`` per event, then a blank line."""
    return "".join(f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n" for name, data in events)


def chat_turn(answer: str, sources: Sequence[dict[str, object]] = (), tokens: int = 3) -> ChatReply:
    """A correct turn: the sources (only when there are any), the answer cut into ``tokens`` pieces, then ``done``."""
    events: list[tuple[str, object]] = []
    if sources:
        events.append(("sources", {"sources": list(sources)}))
    size = max(1, -(-len(answer) // tokens))
    pieces = [answer[index : index + size] for index in range(0, len(answer), size)] or [""]
    events.extend(("token", {"content": piece}) for piece in pieces)
    events.append(("done", {"fragments": len(pieces)}))
    return ChatReply(events=events)


def source(number: int, title: str, text: str, section: str = "") -> dict[str, object]:
    return {"number": number, "title": title, "section": section, "text": text}


def ok_search(sources_by_query: dict[str, Sequence[str]], model: str = MODEL) -> SearchHandler:
    """Answers the sources of ``sources_by_query`` (empty for an unknown question), best chunk first."""

    def handler(query: str, limit: int | None) -> Reply:
        sources = list(sources_by_query.get(query, ()))[: limit or None]
        results = [
            {"source": source, "title": source, "section": "", "position": index, "score": 1 - index / 100,
             "text": f"texto {index}"}
            for index, source in enumerate(sources)
        ]
        return 200, {"model": model, "results": results}

    return handler


def indexed_documents(*doc_ids: str, status: str = "INDEXED") -> list[dict[str, object]]:
    return [{"id": f"id-{doc_id}", "name": f"{doc_id}.md", "title": doc_id, "status": status, "chunkCount": 3}
            for doc_id in doc_ids]


class FakeBackend:
    """Serves ``GET /api/knowledge/search`` and ``GET /api/documents`` and records what it receives."""

    def __init__(self) -> None:
        self.search_handler: SearchHandler = ok_search({})
        self.documents_reply: Reply = (200, [])
        self.search_requests: list[dict[str, str]] = []
        self.document_requests = 0
        self.chat_handler: ChatHandler = lambda message, headers: chat_turn("Respuesta")
        self.chat_requests: list[dict[str, object]] = []
        self.get_headers: list[dict[str, str]] = []  # the headers of every GET (search and document catalog)
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                backend.get_headers.append({name.lower(): value for name, value in self.headers.items()})
                parts = urlsplit(self.path)
                params = {key: values[0] for key, values in parse_qs(parts.query, keep_blank_values=True).items()}
                if parts.path == SEARCH_PATH:
                    backend.search_requests.append(params)
                    limit = int(params["limit"]) if "limit" in params else None
                    status, body = backend.search_handler(params.get("query", ""), limit)
                elif parts.path == DOCUMENTS_PATH:
                    backend.document_requests += 1
                    status, body = backend.documents_reply
                else:
                    status, body = 404, {"code": "SLHD_GENE_00001"}
                self._send(status, body)

            def do_POST(self) -> None:
                if urlsplit(self.path).path != CHAT_PATH:
                    self._send(404, {"code": "SLHD_GENE_00001"})
                    return
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                headers = {name.lower(): value for name, value in self.headers.items()}
                payload = json.loads(raw.decode("utf-8")) if raw else {}
                backend.chat_requests.append({"headers": headers, "body": payload})
                reply = backend.chat_handler(str(payload.get("message", "")), headers)
                if reply.events is None:
                    self._send(reply.status, reply.body if reply.body is not None else {})
                    return
                stream = sse_text(reply.events).encode("utf-8")
                self.send_response(reply.status)
                self.send_header("Content-Type", "text/event-stream;charset=UTF-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Request-Id", reply.headers.get("X-Request-Id", headers.get("x-request-id", "")))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(stream)
                self.wfile.flush()
                self.close_connection = True

            def _send(self, status: int, body: object) -> None:
                if isinstance(body, str):
                    payload = body.encode("utf-8")
                elif isinstance(body, bytes):
                    payload = body
                else:
                    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
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

    def start(self) -> "FakeBackend":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


class FakeSearcher:
    """The ``Searcher`` of the measurement without HTTP: it answers from a dict and counts the calls."""

    def __init__(self, sources_by_query: dict[str, Sequence[str]], documents: Sequence[IndexedDocument] = (),
                 model: str = MODEL) -> None:
        self.sources_by_query = sources_by_query
        self.documents = list(documents)
        self.model = model
        self.models_by_call: list[str] = []
        self.fail_on: dict[str, Exception] = {}
        self.search_calls: list[tuple[str, int]] = []
        self.list_calls = 0

    def search(self, query: str, limit: int) -> SearchResponse:
        self.search_calls.append((query, limit))
        if query in self.fail_on:
            raise self.fail_on[query]
        model = self.models_by_call[len(self.search_calls) - 1] if self.models_by_call else self.model
        hits = tuple(SearchHit(source, 1.0) for source in self.sources_by_query.get(query, ())[:limit])
        return SearchResponse(model, hits)

    def list_documents(self) -> list[IndexedDocument]:
        self.list_calls += 1
        return list(self.documents)


def indexed(cases: Sequence[GoldenCase], status: str = "INDEXED") -> list[IndexedDocument]:
    """Every document the cases expect, as the Markdown file in the given state."""
    ids = sorted({doc for case in cases for doc in case.expected_documents})
    return [IndexedDocument(f"{doc}.md", status) for doc in ids]
