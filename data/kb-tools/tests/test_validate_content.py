from pathlib import Path

from kb_tools.validate import Issue, validate
from tests.conftest import document_text, make_corpus, write_index, write_review

CONTENT_CODES = {"KB001", "KB002", "KB004", "KB005", "KB010"}


def content_codes(kb_dir: Path) -> set[str]:
    issues: list[Issue] = validate(kb_dir)
    return {issue.code for issue in issues} & CONTENT_CODES


def refresh_controls(kb_dir: Path) -> None:
    write_index(kb_dir)
    write_review(kb_dir)


def test_valid_corpus_has_no_content_issues(tmp_path):
    assert content_codes(make_corpus(tmp_path)) == set()


def test_too_few_documents_is_kb001(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "shipping-04-doc-4.md").unlink()
    refresh_controls(kb_dir)
    assert "KB001" in content_codes(kb_dir)


def test_too_many_documents_is_kb001(tmp_path):
    kb_dir = make_corpus(tmp_path, per_topic=7)
    assert "KB001" in content_codes(kb_dir)


def test_documents_are_counted_once_even_with_pdf_copy(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "shipping-01-doc-1.pdf").write_bytes(b"%PDF-1.4")
    assert "KB001" not in content_codes(kb_dir)


def test_topic_with_too_few_documents_is_kb002(tmp_path):
    kb_dir = make_corpus(tmp_path, per_topic=5)
    for number in (3, 4, 5):
        (kb_dir / f"privacy-{number:02d}-doc-{number}.md").unlink()
    refresh_controls(kb_dir)
    issues = validate(kb_dir)
    assert any(issue.code == "KB002" and "privacy" in issue.message for issue in issues)


def test_invalid_file_name_is_kb004(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "shipping-04-doc-4.md").rename(kb_dir / "Shipping_4.md")
    assert "KB004" in content_codes(kb_dir)


def test_topic_different_from_file_prefix_is_kb004(tmp_path):
    kb_dir = make_corpus(tmp_path)
    path = kb_dir / "shipping-01-doc-1.md"
    path.write_text(document_text("Envíos 1", "returns"), encoding="utf-8")
    assert "KB004" in content_codes(kb_dir)


def test_missing_scope_is_kb005(tmp_path):
    kb_dir = make_corpus(tmp_path)
    path = kb_dir / "shipping-01-doc-1.md"
    path.write_text(path.read_text(encoding="utf-8").replace("scope: Alcance de Envíos 1.\n", ""), encoding="utf-8")
    assert "KB005" in content_codes(kb_dir)


def test_invalid_type_is_kb005(tmp_path):
    kb_dir = make_corpus(tmp_path)
    path = kb_dir / "shipping-01-doc-1.md"
    path.write_text(path.read_text(encoding="utf-8").replace("type: policy", "type: article"), encoding="utf-8")
    assert "KB005" in content_codes(kb_dir)


def test_missing_front_matter_is_kb005(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "shipping-01-doc-1.md").write_text("# Sin front matter\n", encoding="utf-8")
    assert "KB005" in content_codes(kb_dir)


def test_duplicate_title_is_kb005(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "shipping-02-doc-2.md").write_text(document_text("Envíos 1", "shipping"), encoding="utf-8")
    assert "KB005" in content_codes(kb_dir)


def test_character_outside_latin1_is_kb010(tmp_path):
    for bad in ("“comillas”", "guion — largo", "emoji \U0001f600"):
        kb_dir = make_corpus(tmp_path / bad[:3].encode().hex())
        path = kb_dir / "shipping-01-doc-1.md"
        path.write_text(path.read_text(encoding="utf-8") + f"\n{bad}\n", encoding="utf-8")
        assert "KB010" in content_codes(kb_dir), bad


def test_accents_and_inverted_marks_are_allowed(tmp_path):
    kb_dir = make_corpus(tmp_path)
    path = kb_dir / "shipping-01-doc-1.md"
    path.write_text(path.read_text(encoding="utf-8") + "\n¿Cuánto tarda? ¡Poco! Señor R$ 19,90\n", encoding="utf-8")
    assert "KB010" not in content_codes(kb_dir)
