"""Values of the ingest pipeline: bytes -> sections -> chunks. Nothing here is stored."""

from dataclasses import dataclass
from typing import Literal

from app.domain.errors import InvalidChunkingConfigError

DocumentFormat = Literal["markdown", "pdf"]

MIN_CHUNK_SIZE = 100
SECTION_SEPARATOR = " > "


@dataclass(frozen=True)
class ParsedSection:
    """The text under one heading: `path` is the chain of headings (empty before the first heading)."""

    path: tuple[str, ...]
    blocks: tuple[str, ...]


@dataclass(frozen=True)
class Chunk:
    text: str
    document: str
    section: str
    position: int


@dataclass(frozen=True)
class IngestResult:
    name: str
    title: str
    format: DocumentFormat
    chunks: tuple[Chunk, ...]


@dataclass(frozen=True)
class ParsedDocument:
    """What a parser extracts: the title it found (if any) and the sections in document order."""

    title: str | None
    sections: tuple[ParsedSection, ...]


@dataclass(frozen=True)
class ChunkingConfig:
    """`chunk_size` caps a whole chunk, overlap included; `chunk_overlap` is the characters repeated from the previous
    chunk of the same section."""

    chunk_size: int
    chunk_overlap: int

    def __post_init__(self) -> None:
        if self.chunk_size < MIN_CHUNK_SIZE:
            raise InvalidChunkingConfigError(f"chunk_size must be at least {MIN_CHUNK_SIZE}, got {self.chunk_size}")
        if self.chunk_overlap < 0:
            raise InvalidChunkingConfigError(f"chunk_overlap must not be negative, got {self.chunk_overlap}")
        if self.chunk_overlap >= self.chunk_size:
            raise InvalidChunkingConfigError(
                f"chunk_overlap ({self.chunk_overlap}) must be smaller than chunk_size ({self.chunk_size})"
            )
