"""The real knowledge base (data/kb) goes through the pipeline: specs/LAB-20 SC-001 to SC-004."""

import time
from pathlib import Path

import pytest

from app.domain.models import ChunkingConfig
from app.service import ingest_document
from tests.pdf_factory import build_pdf_pages

KB = Path(__file__).resolve().parents[2] / "data" / "kb"
CONFIG = ChunkingConfig(chunk_size=1000, chunk_overlap=100)
LIMIT = 10 * 1024 * 1024
DOCUMENTS = sorted(path for path in KB.glob("*") if path.suffix in {".md", ".pdf"}
                   and path.name not in {"INDEX.md", "REVIEW.md"})


def test_the_corpus_is_there():
    assert len([path for path in DOCUMENTS if path.suffix == ".md"]) >= 20
    assert len([path for path in DOCUMENTS if path.suffix == ".pdf"]) >= 8


@pytest.mark.parametrize("path", DOCUMENTS, ids=lambda path: path.name)
def test_every_document_of_the_corpus_is_chunked_within_the_rules(path):
    result = ingest_document(path.name, path.read_bytes(), CONFIG, LIMIT)

    assert result.chunks
    assert [chunk.position for chunk in result.chunks] == list(range(len(result.chunks)))
    assert all(0 < len(chunk.text) <= CONFIG.chunk_size for chunk in result.chunks)
    assert all(chunk.document == path.name for chunk in result.chunks)
    assert result.title


@pytest.mark.parametrize("path", [p for p in DOCUMENTS if p.suffix == ".md"], ids=lambda path: path.name)
def test_no_markdown_chunk_mixes_two_sections(path):
    chunks = ingest_document(path.name, path.read_bytes(), CONFIG, LIMIT).chunks

    for previous, chunk in zip((None, *chunks), chunks, strict=False):
        if chunk.section and (previous is None or previous.section != chunk.section):
            # The first chunk of a section starts with its own heading: nothing is carried over from the previous one.
            assert chunk.text.startswith(chunk.section.split(" > ")[-1]), (
                f"{path.name}: the chunk {chunk.position} of '{chunk.section}' does not start with its heading")


def test_a_twenty_page_pdf_is_ingested_in_under_five_seconds():
    page = [("Sección de la página", 15)] + [
        (f"Párrafo {index}: las condiciones del servicio se aplican a todos los pedidos entregados.", 11)
        for index in range(25)
    ]
    content = build_pdf_pages([page] * 20)

    started = time.perf_counter()
    result = ingest_document("larga.pdf", content, CONFIG, LIMIT)
    elapsed = time.perf_counter() - started

    assert result.chunks
    assert elapsed < 5.0, f"took {elapsed:.1f}s"
