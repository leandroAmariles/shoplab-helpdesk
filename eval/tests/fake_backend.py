"""A local HTTP server that plays the backend of the knowledge search, and a fake searcher without HTTP.

The client and the command are tested against the real network code (a ``ThreadingHTTPServer`` in a thread), so no
test patches ``urllib``.
"""

import json
import threading
from collections.abc import Callable, Sequence
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
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
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
