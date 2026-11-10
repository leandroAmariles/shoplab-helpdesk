import sys

import pytest

from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document, load_corpus
from chunking_lab.strategies import Strategy, build_chunks


def test_equals_ingest_document_on_every_document_of_the_corpus(real_kb_dir):
    from app.domain.models import ChunkingConfig
    from app.service import ingest_document

    config = ExperimentConfig()
    documents = load_corpus(real_kb_dir)
    ours = build_chunks(Strategy.HEADINGS, documents, config)
    ingest_config = ChunkingConfig(config.chunk_size, config.chunk_overlap)
    expected = []
    for document in documents:
        result = ingest_document(f"{document.id}.md", document.raw.encode("utf-8"), ingest_config, 10 * 1024 * 1024)
        expected.extend((document.id, c.position, c.section, c.text) for c in result.chunks)
    assert [(c.document, c.position, c.section, c.text) for c in ours] == expected
    assert len(ours) > 100


def test_no_chunk_mixes_two_sections(real_kb_dir):
    documents = load_corpus(real_kb_dir)
    chunks = build_chunks(Strategy.HEADINGS, documents, ExperimentConfig())
    assert all(c.strategy is Strategy.HEADINGS for c in chunks)
    assert any(c.section for c in chunks)
    by_section = {(c.document, c.section) for c in chunks}
    assert len(by_section) > len(documents)


def test_a_document_without_headings_falls_back_to_size_with_empty_section():
    body = "Frase de relleno número uno. " * 100
    document = Document(id="payments-09-x", topic="payments", raw=body, body=body)
    chunks = build_chunks(Strategy.HEADINGS, [document], ExperimentConfig(chunk_size=500, chunk_overlap=50))
    assert len(chunks) > 1
    assert all(c.section == "" for c in chunks)
    assert all(len(c.text) <= 500 for c in chunks)


def test_ingest_goes_at_the_end_of_sys_path_so_its_tests_package_cannot_shadow_ours():
    ingest_index = max(i for i, p in enumerate(sys.path) if p.replace("\\", "/").endswith("/ingest"))
    ours = [i for i, p in enumerate(sys.path) if p.replace("\\", "/").endswith("notebooks/tests")]
    assert ours
    assert ingest_index > max(ours)


@pytest.mark.parametrize("module", ["app.parsing.markdown", "app.chunking.splitter"])
def test_the_adapter_reuses_ingest_modules(module):
    __import__(module)
    from chunking_lab.strategies import headings

    assert headings.parse_markdown.__module__ == "app.parsing.markdown"
    assert headings.split_sections.__module__ == "app.chunking.splitter"
