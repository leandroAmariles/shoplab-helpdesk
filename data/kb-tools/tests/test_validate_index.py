from kb_tools.validate import validate
from tests.conftest import make_corpus


def index_issues(kb_dir):
    return [issue for issue in validate(kb_dir) if issue.code == "KB006"]


def edit_index(kb_dir, transform):
    index = kb_dir / "INDEX.md"
    index.write_text(transform(index.read_text(encoding="utf-8")), encoding="utf-8")


def test_index_that_matches_the_directory_is_valid(tmp_path):
    assert index_issues(make_corpus(tmp_path, pdf=True)) == []


def test_missing_index_is_kb006(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "INDEX.md").unlink()
    assert index_issues(kb_dir)


def test_index_missing_a_document_is_kb006(tmp_path):
    kb_dir = make_corpus(tmp_path)
    edit_index(kb_dir, lambda text: "\n".join(line for line in text.splitlines() if "returns-02" not in line) + "\n")
    issues = index_issues(kb_dir)
    assert any("returns-02-doc-2" in issue.message for issue in issues)


def test_index_with_an_extra_document_is_kb006(tmp_path):
    kb_dir = make_corpus(tmp_path)
    edit_index(kb_dir, lambda text: text + "| shipping-09-ghost | Fantasma | shipping | policy | md |\n")
    assert any("shipping-09-ghost" in issue.message for issue in index_issues(kb_dir))


def test_index_formats_must_reflect_existing_pdfs(tmp_path):
    kb_dir = make_corpus(tmp_path, pdf=True)
    edit_index(kb_dir, lambda text: text.replace("md, pdf", "md", 1))
    assert index_issues(kb_dir)


def test_index_listing_a_pdf_that_does_not_exist_is_kb006(tmp_path):
    kb_dir = make_corpus(tmp_path)
    edit_index(kb_dir, lambda text: text.replace("| md |", "| md, pdf |", 1))
    assert index_issues(kb_dir)


def test_index_title_must_match_front_matter_title(tmp_path):
    kb_dir = make_corpus(tmp_path)
    edit_index(kb_dir, lambda text: text.replace("Envíos 1", "Otro título", 1))
    assert any("title" in issue.message for issue in index_issues(kb_dir))
