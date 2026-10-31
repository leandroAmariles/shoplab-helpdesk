"""HTTP endpoints. Thin: they read the request, call the pipeline and shape the answer."""

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Response, UploadFile

from app.api.schemas import ErrorResponse, HealthResponse, IngestResponse
from app.core import metrics
from app.core.config import Settings, get_settings
from app.domain.models import ChunkingConfig
from app.service import ingest_document

LOG = logging.getLogger("ingest.api")
router = APIRouter()

_ERRORS = {
    413: {"model": ErrorResponse, "description": "The document is larger than INGEST_MAX_DOCUMENT_BYTES"},
    415: {"model": ErrorResponse, "description": "Only .pdf and .md documents are supported"},
    422: {"model": ErrorResponse, "description": "Empty, corrupt or protected document, no extractable text, "
                                                  "or an invalid chunking configuration"},
    500: {"model": ErrorResponse, "description": "Unexpected failure"},
}


@router.post("/ingest", operation_id="ingestDocument", response_model=IngestResponse, responses=_ERRORS,
             summary="Parses a document and returns its chunks with metadata; nothing is stored")
def ingest(
    file: Annotated[UploadFile, File(description="PDF (.pdf) or UTF-8 Markdown (.md); its base file name is the "
                                                  "reference of the document.")],
    settings: Annotated[Settings, Depends(get_settings)],
    chunk_size: Annotated[int | None, Form(description="Replaces INGEST_CHUNK_SIZE for this request only "
                                                       "(at least 100).")] = None,
    chunk_overlap: Annotated[int | None, Form(description="Replaces INGEST_CHUNK_OVERLAP for this request only; it "
                                                          "must be smaller than chunk_size.")] = None,
) -> IngestResponse:
    defaults = settings.chunking()
    config = ChunkingConfig(
        chunk_size=chunk_size if chunk_size is not None else defaults.chunk_size,
        chunk_overlap=chunk_overlap if chunk_overlap is not None else defaults.chunk_overlap,
    )
    content = file.file.read()
    result = ingest_document(file.filename, content, config, settings.max_document_bytes)
    LOG.info("document ingested", extra={"fields": {"document": result.name, "format": result.format,
                                                    "chunks": len(result.chunks), "bytes": len(content)}})
    return IngestResponse.model_validate({
        "document": {"name": result.name, "title": result.title, "format": result.format},
        "chunks": [
            {"text": chunk.text,
             "metadata": {"document": chunk.document, "section": chunk.section, "position": chunk.position}}
            for chunk in result.chunks
        ],
    })


@router.get("/health", operation_id="health", response_model=HealthResponse, summary="Liveness and readiness probe")
def health() -> HealthResponse:
    return HealthResponse(status="ok")


@router.get("/metrics", include_in_schema=False)
def prometheus_metrics() -> Response:
    body, content_type = metrics.render()
    return Response(content=body, media_type=content_type)
