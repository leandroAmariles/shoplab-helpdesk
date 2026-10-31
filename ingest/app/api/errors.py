"""Error answers: a stable code and a message. Details of unexpected failures stay in the logs."""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.domain.errors import IngestError

LOG = logging.getLogger("ingest.api")


def error_response(code: str, message: str, status_code: int) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"code": code, "message": message})


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(IngestError)
    async def handle_ingest_error(_: Request, error: IngestError) -> JSONResponse:
        LOG.info("document rejected", extra={"fields": {"code": error.code}})
        return error_response(error.code, error.message, error.status_code)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(_: Request, error: RequestValidationError) -> JSONResponse:
        fields = {str(part) for item in error.errors() for part in item.get("loc", ())}
        if "file" in fields:
            return error_response("INVALID_DOCUMENT", "The 'file' part with the document is required", 422)
        return error_response("INVALID_CHUNKING_CONFIG", "chunk_size and chunk_overlap must be integers", 422)

    @app.exception_handler(Exception)
    async def handle_unexpected_error(_: Request, error: Exception) -> JSONResponse:
        LOG.error("unexpected failure", exc_info=error)
        return error_response("INTERNAL_ERROR", "Unexpected error", 500)
