from kb_tools.validate import Options, validate
from tests.conftest import make_corpus, write_review

REVIEW_CODES = {"KB007", "KB011"}


def review_codes(kb_dir, require_reviewed: bool = False) -> set[str]:
    issues = validate(kb_dir, Options(require_reviewed=require_reviewed))
    return {issue.code for issue in issues} & REVIEW_CODES


def test_reviewed_corpus_is_valid_even_when_review_is_required(tmp_path):
    assert review_codes(make_corpus(tmp_path), require_reviewed=True) == set()


def test_missing_review_file_is_kb007(tmp_path):
    kb_dir = make_corpus(tmp_path)
    (kb_dir / "REVIEW.md").unlink()
    assert "KB007" in review_codes(kb_dir)


def test_review_with_a_missing_document_is_kb007(tmp_path):
    kb_dir = make_corpus(tmp_path)
    review = kb_dir / "REVIEW.md"
    lines = review.read_text(encoding="utf-8").splitlines()
    review.write_text("\n".join(line for line in lines if "shipping-01" not in line) + "\n", encoding="utf-8")
    assert "KB007" in review_codes(kb_dir)


def test_review_with_an_extra_document_is_kb007(tmp_path):
    kb_dir = make_corpus(tmp_path)
    review = kb_dir / "REVIEW.md"
    review.write_text(
        review.read_text(encoding="utf-8") + "| shipping-09-ghost | Revisor de prueba | 2026-10-28 | reviewed |\n",
        encoding="utf-8",
    )
    assert "KB007" in review_codes(kb_dir)


def test_reviewed_without_reviewer_or_date_is_kb007(tmp_path):
    for old, new in (("Revisor de prueba", ""), ("2026-10-28", ""), ("2026-10-28", "28/10/2026")):
        kb_dir = make_corpus(tmp_path / f"{len(old)}{len(new)}{old[:2]}")
        review = kb_dir / "REVIEW.md"
        first, rest = review.read_text(encoding="utf-8").split("shipping-01-doc-1", 1)
        review.write_text(first + "shipping-01-doc-1" + rest.replace(old, new, 1), encoding="utf-8")
        assert "KB007" in review_codes(kb_dir), (old, new)


def test_pending_rows_are_valid_unless_review_is_required(tmp_path):
    kb_dir = make_corpus(tmp_path, reviewed=False)
    assert review_codes(kb_dir) == set()
    assert "KB011" in review_codes(kb_dir, require_reviewed=True)


def test_only_unreviewed_documents_are_reported_with_require_reviewed(tmp_path):
    kb_dir = make_corpus(tmp_path)
    write_review(kb_dir, "pending")
    issues = [i for i in validate(kb_dir, Options(require_reviewed=True)) if i.code == "KB011"]
    assert len(issues) == 20
