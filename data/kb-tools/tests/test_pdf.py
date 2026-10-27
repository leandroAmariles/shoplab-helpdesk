import io
from pathlib import Path

from pypdf import PdfReader

from kb_tools.document import Document, load_corpus
from kb_tools.pdf import extract_text, normalize_text, render_pdf, write_pdf
from tests.conftest import document_text

SAMPLE = document_text("Devoluciones de prueba", "returns") + (
    "\n| Motivo | Plazo |\n|---|---|\n| Arrepentimiento | 7 días corridos |\n\n"
    "1. Primer paso\n2. Segundo paso\n\nTexto con **énfasis** y `código` ¿Qué tal? ¡Señor!\n"
)


def sample_document(tmp_path: Path) -> Document:
    path = tmp_path / "returns-01-sample.md"
    path.write_text(SAMPLE, encoding="utf-8")
    documents, errors = load_corpus(tmp_path)
    assert not errors
    return documents[0]


def test_pdf_opens_and_has_extractable_text(tmp_path):
    pdf = render_pdf(sample_document(tmp_path))
    reader = PdfReader(io.BytesIO(pdf))
    assert len(reader.pages) >= 1
    text = extract_text(pdf)
    assert "Devoluciones de prueba" in text
    assert "7 días corridos" in text


def test_pdf_has_no_images(tmp_path):
    pdf = render_pdf(sample_document(tmp_path))
    reader = PdfReader(io.BytesIO(pdf))
    assert all(len(page.images) == 0 for page in reader.pages)


def test_rendering_twice_gives_identical_bytes(tmp_path):
    document = sample_document(tmp_path)
    assert render_pdf(document) == render_pdf(document)


def test_pdf_text_matches_markdown_text(tmp_path):
    document = sample_document(tmp_path)
    pdf_text = normalize_text(extract_text(render_pdf(document)))
    for expected in ("Alcance", "Paso uno y paso dos", "Primer paso", "Segundo paso", "énfasis", "Arrepentimiento"):
        assert normalize_text(expected) in pdf_text


def test_write_pdf_reports_whether_it_changed(tmp_path):
    document = sample_document(tmp_path)
    target = tmp_path / "out.pdf"
    assert write_pdf(document, target) is True
    assert write_pdf(document, target) is False
