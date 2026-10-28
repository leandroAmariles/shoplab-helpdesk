"""Wire format of POST /ingest, published in ingest/openapi.json (specs/LAB-20, contracts/ingest.openapi.yaml)."""

from typing import Literal

from pydantic import BaseModel, Field

from app.domain.errors import ERROR_CODES

ErrorCode = Literal[ERROR_CODES]  # type: ignore[valid-type]

CHUNK_EXAMPLE = {
    "text": "Plazos\n\nTienes 30 días para devolver un producto.",
    "metadata": {"document": "returns-01-return-policy.md", "section": "Política de devoluciones > Plazos",
                 "position": 0},
}


class DocumentInfo(BaseModel):
    name: str
    title: str
    format: Literal["markdown", "pdf"]


class ChunkMetadata(BaseModel):
    document: str = Field(description="Name of the source document")
    section: str = Field(description="Chain of headings separated by ' > '; empty when there is none")
    position: int = Field(ge=0, description="Consecutive position in the document, from 0")


class Chunk(BaseModel):
    text: str = Field(min_length=1)
    metadata: ChunkMetadata


class IngestResponse(BaseModel):
    document: DocumentInfo
    chunks: list[Chunk] = Field(min_length=1)

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "document": {"name": "returns-01-return-policy.md", "title": "Política de devoluciones",
                                 "format": "markdown"},
                    "chunks": [CHUNK_EXAMPLE],
                }
            ]
        }
    }


class ErrorResponse(BaseModel):
    code: ErrorCode
    message: str


class HealthResponse(BaseModel):
    status: Literal["ok"]
