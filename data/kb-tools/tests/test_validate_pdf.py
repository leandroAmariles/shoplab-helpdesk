from kb_tools.document import load_corpus
from kb_tools.pdf import write_pdf
from kb_tools.validate import validate
from tests.conftest import make_corpus, write_index

PDF_CODES = {"KB003", "KB008"}


def pdf_codes(kb_dir) -> set[str]:
    return {issue.code for issue in validate(kb_dir)} & PDF_CODES


def test_corpus_with_a_pdf_per_topic_is_valid(tmp_path):
    assert pdf_codes(make_corpus(tmp_path, pdf=True)) == set()


def test_topic_without_pdf_is_kb003(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    (kb_dir / "privacy-01-doc-1.pdf").unlink()
    write_index(kb_dir)
    issues = validate(kb_dir)
    assert any(issue.code == "KB003" and "privacy" in issue.message for issue in issues)


def test_pdf_with_other_text_is_kb008(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    markdown = kb_dir / "shipping-01-doc-1.md"
    markdown.write_text(markdown.read_text(encoding="utf-8").replace("Paso uno", "Paso distinto"), encoding="utf-8")
    assert "KB008" in pdf_codes(kb_dir)


def test_pdf_updated_after_markdown_change_is_valid(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    markdown = kb_dir / "shipping-01-doc-1.md"
    markdown.write_text(markdown.read_text(encoding="utf-8").replace("Paso uno", "Paso distinto"), encoding="utf-8")
    documents, _ = load_corpus(kb_dir)
    write_pdf(next(doc for doc in documents if doc.id == "shipping-01-doc-1"), kb_dir / "shipping-01-doc-1.pdf")
    assert "KB008" not in pdf_codes(kb_dir)


def test_file_that_is_not_a_pdf_is_kb008(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    (kb_dir / "returns-01-doc-1.pdf").write_bytes(b"esto no es un pdf")
    assert "KB008" in pdf_codes(kb_dir)


def test_pdf_without_text_is_kb008(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    from fpdf import FPDF

    empty = FPDF()
    empty.add_page()
    (kb_dir / "returns-01-doc-1.pdf").write_bytes(bytes(empty.output()))
    assert "KB008" in pdf_codes(kb_dir)
