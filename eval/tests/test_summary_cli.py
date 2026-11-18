from pathlib import Path

import pytest

from helpers import make_case, valid_default_set, write_golden
from shoplab_eval import InvalidGoldenError, load_golden, validate_golden
from shoplab_eval.__main__ import main
from shoplab_eval.constants import CATEGORIES, DEFAULT_GOLDEN_PATH, DEFAULT_KB_DIR
from shoplab_eval.corpus import load_corpus
from shoplab_eval.golden import parse_golden
from shoplab_eval.summary import format_summary, summarize


@pytest.fixture
def valid_file(tmp_path: Path) -> Path:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set())


def test_summary_counts_the_valid_default_set(valid_file: Path, toy_kb: Path) -> None:
    summary = summarize(parse_golden(valid_file).cases, load_corpus(toy_kb))

    assert summary.total == 80
    assert summary.by_kind == {"answered": 68, "unanswered_topic": 6, "off_topic": 6}
    assert list(summary.by_category) == list(CATEGORIES)
    assert sum(summary.by_category.values()) == 80
    assert summary.multi_document == 8
    assert summary.uncovered_documents == ()


def test_summary_lists_the_documents_without_cases(tmp_path: Path, toy_kb: Path) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case()])

    summary = summarize(parse_golden(path).cases, load_corpus(toy_kb))

    assert summary.uncovered_documents == (
        "payments-02-b",
        "privacy-01-a",
        "returns-01-a",
        "shipping-01-a",
        "warranty-01-a",
    )


def test_lexical_overlap_indicator_counts_questions_with_few_words_in_common(tmp_path: Path, toy_kb: Path) -> None:
    same_words = make_case(id="G001", question="¿Puedes pagar con tarjeta de crédito hasta cuotas?")
    other_words = make_case(id="G002", question="¿Cómo divido mi compra mensualmente?")
    path = write_golden(tmp_path / "golden.jsonl", [same_words, other_words])

    summary = summarize(parse_golden(path).cases, load_corpus(toy_kb))

    assert summary.low_overlap_share == pytest.approx(0.5)


def test_lexical_overlap_indicator_is_none_without_answered_cases(tmp_path: Path, toy_kb: Path) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case(expected_documents=[], reference_answer="No hay datos.")])

    assert summarize(parse_golden(path).cases, load_corpus(toy_kb)).low_overlap_share is None


def test_format_summary_is_one_line_per_fact_and_stable(valid_file: Path, toy_kb: Path) -> None:
    summary = summarize(parse_golden(valid_file).cases, load_corpus(toy_kb))

    text = format_summary(summary)

    assert text == format_summary(summary)
    assert "Casos: 80" in text
    assert "answered=68" in text
    assert "Casos de dos documentos: 8" in text
    assert "Documentos del corpus sin caso: ninguno" in text
    assert "30 %" in text


def test_validate_golden_returns_the_summary_and_load_golden_the_cases(valid_file: Path, toy_kb: Path) -> None:
    assert validate_golden(valid_file, toy_kb).total == 80
    assert len(load_golden(valid_file, toy_kb)) == 80


def test_load_golden_raises_with_all_the_failures(tmp_path: Path, toy_kb: Path) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case(category="pagos"), make_case(id="G002")])

    with pytest.raises(InvalidGoldenError) as error:
        load_golden(path, toy_kb)

    assert {"G-CATEGORY", "G-COUNT"} <= {issue.code for issue in error.value.issues}


def test_cli_prints_the_summary_and_returns_zero(valid_file: Path, toy_kb: Path, capsys: pytest.CaptureFixture) -> None:
    code = main(["validate", "--golden", str(valid_file), "--kb", str(toy_kb)])

    assert code == 0
    assert "Casos: 80" in capsys.readouterr().out


def test_cli_prints_one_failure_per_line_and_returns_one(
    tmp_path: Path, toy_kb: Path, capsys: pytest.CaptureFixture
) -> None:
    path = write_golden(tmp_path / "golden.jsonl", [make_case(category="pagos")])

    code = main(["validate", "--golden", str(path), "--kb", str(toy_kb)])

    lines = capsys.readouterr().out.strip().splitlines()
    assert code == 1
    assert lines[0].startswith("G-CATEGORY G001:")
    assert any(line.startswith("G-COUNT SET:") for line in lines)


def test_two_runs_on_the_same_file_print_exactly_the_same(
    valid_file: Path, toy_kb: Path, capsys: pytest.CaptureFixture
) -> None:
    main(["validate", "--golden", str(valid_file), "--kb", str(toy_kb)])
    first = capsys.readouterr().out
    main(["validate", "--golden", str(valid_file), "--kb", str(toy_kb)])
    second = capsys.readouterr().out

    assert first == second


def test_cli_without_subcommand_shows_help_and_returns_two(capsys: pytest.CaptureFixture) -> None:
    assert main([]) == 2
    assert "validate" in capsys.readouterr().out


def test_cli_with_a_missing_file_returns_one_with_a_message(
    tmp_path: Path, toy_kb: Path, capsys: pytest.CaptureFixture
) -> None:
    code = main(["validate", "--golden", str(tmp_path / "no-existe.jsonl"), "--kb", str(toy_kb)])

    assert code == 1
    assert "no-existe.jsonl" in capsys.readouterr().out


def test_default_paths_are_absolute_and_do_not_depend_on_the_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)

    assert DEFAULT_GOLDEN_PATH.is_absolute()
    assert DEFAULT_GOLDEN_PATH.parent.name == "eval"
    assert DEFAULT_KB_DIR.is_dir()
