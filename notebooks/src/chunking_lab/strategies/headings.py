"""Chunking by headings: the production chunker of `ingest/` (ADR-0016), reused as is, never copied.

`ingest/` goes at the END of sys.path so that its own `tests/` package cannot shadow this project's.
"""

import sys

from chunking_lab.config import ExperimentConfig, repo_root
from chunking_lab.corpus import Document
from chunking_lab.strategies import Chunk, Strategy

_INGEST_DIR = str(repo_root() / "ingest")
if _INGEST_DIR not in sys.path:
    sys.path.append(_INGEST_DIR)

from app.chunking.splitter import split_sections  # noqa: E402
from app.domain.models import ChunkingConfig  # noqa: E402
from app.parsing.markdown import parse_markdown  # noqa: E402


def chunk_documents(documents: list[Document], config: ExperimentConfig) -> list[Chunk]:
    ingest_config = ChunkingConfig(chunk_size=config.chunk_size, chunk_overlap=config.chunk_overlap)
    chunks: list[Chunk] = []
    for document in documents:
        parsed = parse_markdown(document.raw)  # the ingest parser removes the front matter itself
        for chunk in split_sections(parsed.sections, ingest_config, document.id):
            chunks.append(Chunk(strategy=Strategy.HEADINGS, document=document.id, position=chunk.position,
                                section=chunk.section, text=chunk.text))
    return chunks
