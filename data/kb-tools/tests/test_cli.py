from typer.testing import CliRunner

from kb_tools.cli import app
from tests.conftest import make_corpus

runner = CliRunner()


def test_build_pdf_writes_then_reports_unchanged(tmp_path):
    kb_dir = make_corpus(tmp_path)
    first = runner.invoke(app, ["build-pdf", "--kb-dir", str(kb_dir), "--only", "shipping-01-doc-1"])
    assert first.exit_code == 0
    assert "ok shipping-01-doc-1.pdf" in first.output
    second = runner.invoke(app, ["build-pdf", "--kb-dir", str(kb_dir), "--only", "shipping-01-doc-1"])
    assert "unchanged shipping-01-doc-1.pdf" in second.output


def test_build_pdf_check_fails_when_pdf_is_missing_or_outdated(tmp_path):
    kb_dir = make_corpus(tmp_path)
    args = ["build-pdf", "--kb-dir", str(kb_dir), "--only", "shipping-01-doc-1"]
    assert runner.invoke(app, [*args, "--check"]).exit_code == 1
    runner.invoke(app, args)
    assert runner.invoke(app, [*args, "--check"]).exit_code == 0
    markdown = kb_dir / "shipping-01-doc-1.md"
    markdown.write_text(markdown.read_text(encoding="utf-8").replace("Paso uno", "Paso otro"), encoding="utf-8")
    assert runner.invoke(app, [*args, "--check"]).exit_code == 1


def test_build_pdf_unknown_document_is_usage_error(tmp_path):
    kb_dir = make_corpus(tmp_path)
    result = runner.invoke(app, ["build-pdf", "--kb-dir", str(kb_dir), "--only", "nope"])
    assert result.exit_code == 2


def test_validate_command_reports_summary_and_exit_code(tmp_path):
    kb_dir = make_corpus(tmp_path)
    result = runner.invoke(app, ["validate", "--kb-dir", str(kb_dir)])
    assert result.exit_code == 1  # no PDF yet: KB003 for every topic
    assert "KB003" in result.output
    assert "20 documentos, 0 PDF" in result.output
