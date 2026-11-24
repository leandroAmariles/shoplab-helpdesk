"""ShopLab Helpdesk ingest service: parses and chunks PDF and Markdown documents. Stateless; it stores nothing and
has no access to any database (specs/LAB-20, ADR-0015)."""

import time
import uuid
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, Response

from app.api.errors import error_response, register_error_handlers
from app.api.routes import router
from app.core import metrics
from app.core.config import get_settings
from app.core.logging import configure_logging, request_id

REQUEST_ID_HEADER = "X-Request-Id"
# Multipart framing and the other form fields add a little to the size of the document itself.
MULTIPART_OVERHEAD_BYTES = 64 * 1024


def create_app() -> FastAPI:
    configure_logging()
    app = FastAPI(
        title="ShopLab Helpdesk Ingest",
        version="0.1.0",
        description="Parses a document (PDF or Markdown) and returns its chunks with metadata. It stores nothing.",
    )
    register_error_handlers(app)
    app.include_router(router)

    @app.middleware("http")
    async def correlate_and_measure(request: Request,
                                    call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        token = request_id.set(request.headers.get(REQUEST_ID_HEADER) or uuid.uuid4().hex)
        started = time.perf_counter()
        try:
            try:
                response = _too_large(request) or await call_next(request)
            except Exception:
                if request.url.path == "/ingest":
                    metrics.record(500, time.perf_counter() - started)
                raise
            if request.url.path == "/ingest":
                metrics.record(response.status_code, time.perf_counter() - started)
            response.headers[REQUEST_ID_HEADER] = request_id.get()
            return response
        finally:
            request_id.reset(token)

    return app


def _too_large(request: Request) -> Response | None:
    """Rejects an oversized upload from its Content-Length, before the body is read."""
    declared = request.headers.get("content-length")
    if request.url.path != "/ingest" or not declared or not declared.isdigit():
        return None
    limit = request.app.dependency_overrides.get(get_settings, get_settings)().max_document_bytes
    if int(declared) > limit + MULTIPART_OVERHEAD_BYTES:
        return error_response("DOCUMENT_TOO_LARGE", f"The document is larger than the limit of {limit} bytes", 413)
    return None


app = create_app()
