from pathlib import Path

import pytest

from shoplab_eval.constants import DEFAULT_KB_DIR
from shoplab_eval.corpus import load_corpus, normalize_text


def test_load_corpus_skips_control_files_and_pdfs(toy_kb: Path) -> None:
    corpus = load_corpus(toy_kb)

    assert list(corpus) == [
        "payments-01-a",
        "payments-02-b",
        "privacy-01-a",
        "returns-01-a",
        "shipping-01-a",
        "warranty-01-a",
    ]
    assert corpus["returns-01-a"].topic == "returns"
    assert corpus["returns-01-a"].id == "returns-01-a"


def test_corpus_text_is_normalized_without_front_matter(toy_kb: Path) -> None:
    document = load_corpus(toy_kb)["payments-01-a"]

    assert "hasta 12 cuotas sin interés" in document.text
    assert "language" not in document.text
    assert document.text == normalize_text(document.text)


def test_document_without_front_matter_fails_naming_the_file(toy_kb: Path) -> None:
    (toy_kb / "returns-02-x.md").write_text("# Sin cabecera\nTexto.\n", encoding="utf-8")

    with pytest.raises(ValueError, match=r"returns-02-x\.md"):
        load_corpus(toy_kb)


def test_document_without_topic_fails_naming_the_file(toy_kb: Path) -> None:
    (toy_kb / "returns-02-x.md").write_text("---\ntitle: x\n---\nTexto.\n", encoding="utf-8")

    with pytest.raises(ValueError, match=r"returns-02-x\.md.*topic"):
        load_corpus(toy_kb)


def test_missing_directory_fails_with_a_clear_message(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="directorio del corpus"):
        load_corpus(tmp_path / "nope")


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("¿Cuántos DÍAS tengo?", "cuántos días tengo"),
        ("  Hola,\n\n  mundo!  ", "hola mundo"),
        ("**Plazo**: _7 días_ (corridos)", "plazo 7 días corridos"),
        ("Año nuevo: ñandú", "año nuevo ñandú"),
        ("R$ 120,00", "r 120 00"),
    ],
)
def test_normalize_text_keeps_accents_and_drops_punctuation(raw: str, expected: str) -> None:
    assert normalize_text(raw) == expected


def test_normalize_text_is_idempotent() -> None:
    once = normalize_text("¿Puedo pagar en 5 cuotas? Sí, con **tarjeta**.")

    assert normalize_text(once) == once


def test_real_corpus_has_the_24_documents() -> None:
    corpus = load_corpus(DEFAULT_KB_DIR)

    assert len(corpus) == 24
    assert {document.topic for document in corpus.values()} == {
        "payments",
        "privacy",
        "returns",
        "shipping",
        "warranty",
    }
