import pytest

from chunking_lab.corpus import load_corpus
from chunking_lab.errors import ChunkingLabError

FRONT = "---\ntitle: T\ntopic: returns\n---\n# Title\n\nBody text.\n"


def test_excludes_index_and_review_and_strips_front_matter(tmp_path):
    (tmp_path / "returns-01-policy.md").write_text(FRONT, encoding="utf-8")
    (tmp_path / "payments-01-methods.md").write_text("# Pagos\n\nTexto con tilde y ñ.\n", encoding="utf-8")
    (tmp_path / "INDEX.md").write_text("| id |", encoding="utf-8")
    (tmp_path / "REVIEW.md").write_text("| id |", encoding="utf-8")
    (tmp_path / "returns-01-policy.pdf").write_bytes(b"%PDF")

    documents = load_corpus(tmp_path)

    assert [d.id for d in documents] == ["payments-01-methods", "returns-01-policy"]
    returns = documents[1]
    assert returns.topic == "returns"
    assert returns.raw == FRONT
    assert returns.body == "# Title\n\nBody text.\n"
    assert "ñ" in documents[0].body


def test_empty_directory_fails_clearly(tmp_path):
    with pytest.raises(ChunkingLabError, match="Markdown"):
        load_corpus(tmp_path)


def test_real_corpus_has_24_documents_and_five_topics(real_kb_dir):
    documents = load_corpus(real_kb_dir)
    assert len(documents) == 24
    assert {d.topic for d in documents} == {"payments", "privacy", "returns", "shipping", "warranty"}
    assert all(not d.body.startswith("---") for d in documents)
