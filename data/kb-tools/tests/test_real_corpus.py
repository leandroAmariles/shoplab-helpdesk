"""Delivery gate (research D-9): the real corpus in data/kb must be valid and fully reviewed by hand."""

from pathlib import Path

from kb_tools.constants import MAX_DOCS, MIN_DOCS, PDF_DOCUMENT_IDS
from kb_tools.document import load_corpus
from kb_tools.validate import Options, validate

KB_DIR = Path(__file__).resolve().parents[2] / "kb"


def test_real_corpus_is_valid_and_reviewed():
    issues = validate(KB_DIR, Options(require_reviewed=True))
    assert [issue.format() for issue in issues] == []


def test_real_corpus_has_the_planned_documents_and_pdfs():
    documents, errors = load_corpus(KB_DIR)
    assert errors == []
    assert MIN_DOCS <= len(documents) <= MAX_DOCS
    with_pdf = {document.id for document in documents if document.has_pdf}
    assert with_pdf == set(PDF_DOCUMENT_IDS)
