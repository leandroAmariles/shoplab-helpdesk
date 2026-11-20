"""The comparison of two quality summaries (specs/LAB-85-versioned-prompts, FR-021 and FR-022).

It only reads two files: no model, no Langfuse and no backend, and the tests check that nothing opens a connection.
"""

import csv
import io
import socket
from pathlib import Path

import pytest

from shoplab_eval.__main__ import main
from shoplab_eval.compare import (
    NOT_COMPARABLE,
    SummaryError,
    compare_summaries,
    comparison_csv,
    format_comparison,
    read_summary,
)

COLUMNS = [
    "scope", "cases", "excluded_cases",
    "faithfulness_mean", "faithfulness_n", "faithfulness_empty",
    "answer_relevancy_mean", "answer_relevancy_n", "answer_relevancy_empty",
    "context_precision_mean", "context_precision_n", "context_precision_empty",
    "abstained", "run_id", "judge_model", "judge_underlying_model", "judge_temperature", "ragas_version",
    "abstention_prompt_version", "assistant_model", "embedding_model", "langfuse", "prompt_versions",
]  # fmt: skip


def summary_row(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "scope": "all", "cases": 90, "excluded_cases": 30,
        "faithfulness_mean": "0.8000", "faithfulness_n": 80, "faithfulness_empty": 0,
        "answer_relevancy_mean": "0.7000", "answer_relevancy_n": 90, "answer_relevancy_empty": 0,
        "context_precision_mean": "0.9000", "context_precision_n": 88, "context_precision_empty": 2,
        "abstained": 4, "run_id": "prompt-a", "judge_model": "judge", "judge_underlying_model": "qwen2.5:7b",
        "judge_temperature": "0.0000", "ragas_version": "0.4.4", "abstention_prompt_version": "2",
        "assistant_model": "llama3.2:3b", "embedding_model": "bge-m3", "langfuse": "skipped",
        "prompt_versions": "production",
    }  # fmt: skip
    row.update(overrides)
    return row


def write_summary(path: Path, *rows: dict[str, object]) -> Path:
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=COLUMNS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    path.write_text(buffer.getvalue(), encoding="utf-8", newline="")
    return path


@pytest.fixture
def pair(tmp_path: Path) -> tuple[Path, Path]:
    a = write_summary(tmp_path / "a.csv", summary_row(scope="payments", cases=10), summary_row())
    b = write_summary(
        tmp_path / "b.csv",
        summary_row(run_id="prompt-b", faithfulness_mean="0.8500", answer_relevancy_mean="0.6500",
                    context_precision_mean="0.9000", prompt_versions="chat-system=2"),
    )  # fmt: skip
    return a, b


def test_the_table_has_the_three_metrics_with_the_means_the_cases_and_the_difference(pair: tuple[Path, Path]) -> None:
    comparison = compare_summaries(read_summary(pair[0]), read_summary(pair[1]))

    rows = {row.metric: row for row in comparison.rows}
    assert list(rows) == ["faithfulness", "answer_relevancy", "context_precision"]
    assert (rows["faithfulness"].a_mean, rows["faithfulness"].b_mean) == (0.8, 0.85)
    assert rows["faithfulness"].delta == pytest.approx(0.05)
    assert rows["answer_relevancy"].delta == pytest.approx(-0.05)
    assert rows["context_precision"].delta == pytest.approx(0.0)
    assert (rows["faithfulness"].a_n, rows["context_precision"].b_n) == (80, 88)
    assert (comparison.run_a, comparison.run_b) == ("prompt-a", "prompt-b")


def test_only_the_all_row_counts_whatever_the_order_of_the_rows(pair: tuple[Path, Path]) -> None:
    row = read_summary(pair[0])

    assert row["scope"] == "all"
    assert row["cases"] == "90"


def test_the_prompts_that_tell_one_run_from_the_other_are_named(pair: tuple[Path, Path]) -> None:
    comparison = compare_summaries(read_summary(pair[0]), read_summary(pair[1]))

    assert comparison.differing_prompts == ("chat-system",)
    assert comparison.comparable
    assert "Prompts que difieren: chat-system" in format_comparison(comparison)


def test_a_prompt_that_only_one_run_fixes_differs_from_production() -> None:
    a = summary_row(prompt_versions="chat-context=2|chat-system=3")
    b = summary_row(prompt_versions="chat-system=3")

    comparison = compare_summaries({k: str(v) for k, v in a.items()}, {k: str(v) for k, v in b.items()})

    assert comparison.differing_prompts == ("chat-context",)


def test_two_runs_with_the_same_prompts_say_none_differs() -> None:
    row = {k: str(v) for k, v in summary_row(prompt_versions="chat-system=3").items()}

    comparison = compare_summaries(row, row)

    assert comparison.differing_prompts == ()
    assert "Prompts que difieren: ninguno" in format_comparison(comparison)


@pytest.mark.parametrize(
    "parameter, other",
    [
        ("judge_model", "otro-juez"),
        ("judge_underlying_model", "llama3.1:8b"),
        ("judge_temperature", "0.5000"),
        ("assistant_model", "qwen2.5:7b"),
        ("embedding_model", "otro-embedding"),
        ("cases", 60),
    ],
)
def test_a_different_parameter_makes_the_runs_not_comparable_and_says_which_with_both_values(
    parameter: str, other: object
) -> None:
    a = {k: str(v) for k, v in summary_row().items()}
    b = {k: str(v) for k, v in summary_row(**{parameter: other}).items()}

    comparison = compare_summaries(a, b)
    text = format_comparison(comparison)

    assert not comparison.comparable
    assert comparison.warnings == (f"{parameter}: A={a[parameter]} · B={other}",)
    assert NOT_COMPARABLE in text
    assert text.index(NOT_COMPARABLE) < text.index("faithfulness")  # said before the numbers, not after
    assert f"{parameter}: A=" in text


def test_a_metric_without_a_mean_has_no_difference_and_shows_a_dash() -> None:
    a = {k: str(v) for k, v in summary_row(faithfulness_mean="", faithfulness_n=0).items()}
    b = {k: str(v) for k, v in summary_row().items()}

    comparison = compare_summaries(a, b)

    assert comparison.rows[0].delta is None
    assert comparison.rows[0].a_mean is None
    assert "—" in format_comparison(comparison)
    assert comparison_csv(comparison).splitlines()[1] == "faithfulness,,0,0.8000,80,"


def test_the_difference_of_equal_means_never_prints_as_a_negative_zero() -> None:
    a = {k: str(v) for k, v in summary_row(context_precision_mean="0.9000").items()}
    b = {k: str(v) for k, v in summary_row(context_precision_mean="0.9000").items()}

    text = format_comparison(compare_summaries(a, b))

    assert "-0.0000" not in text
    assert "+0.0000" in text


def test_the_csv_has_the_documented_columns_and_four_decimals(pair: tuple[Path, Path]) -> None:
    comparison = compare_summaries(read_summary(pair[0]), read_summary(pair[1]))

    lines = comparison_csv(comparison).splitlines()

    assert lines[0] == "metric,a_mean,a_n,b_mean,b_n,delta"
    assert lines[1] == "faithfulness,0.8000,80,0.8500,80,0.0500"
    assert lines[2] == "answer_relevancy,0.7000,90,0.6500,90,-0.0500"


def test_a_summary_without_the_prompt_column_is_a_run_on_production(tmp_path: Path) -> None:
    older = summary_row()
    del older["prompt_versions"]
    path = tmp_path / "old.csv"
    columns = [c for c in COLUMNS if c != "prompt_versions"]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerow(older)

    comparison = compare_summaries(read_summary(path), read_summary(path))

    assert comparison.differing_prompts == ()


# --- what is not a summary ------------------------------------------------------------------------------------


def test_a_missing_file_is_a_summary_error(tmp_path: Path) -> None:
    with pytest.raises(SummaryError, match="No se pudo leer"):
        read_summary(tmp_path / "no-existe.csv")


def test_a_summary_without_the_all_row_is_a_summary_error(tmp_path: Path) -> None:
    path = write_summary(tmp_path / "x.csv", summary_row(scope="payments"))

    with pytest.raises(SummaryError, match="«all»"):
        read_summary(path)


def test_a_csv_that_is_not_a_quality_summary_is_a_summary_error(tmp_path: Path) -> None:
    path = tmp_path / "retrieval.csv"
    path.write_text("scope,cases,hit_rate@1\nall,90,0.5\n", encoding="utf-8")

    with pytest.raises(SummaryError, match="no es de calidad"):
        read_summary(path)


# --- the command ----------------------------------------------------------------------------------------------


def test_the_command_prints_the_table_and_exits_with_zero(
    pair: tuple[Path, Path], capsys: pytest.CaptureFixture[str]
) -> None:
    code = main(["compare", str(pair[0]), str(pair[1])])

    assert code == 0
    output = capsys.readouterr().out
    assert "faithfulness" in output
    assert "+0.0500" in output
    assert "Prompts que difieren: chat-system" in output


def test_with_warnings_the_exit_code_is_still_zero_and_the_table_is_shown_with_the_warning(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    a = write_summary(tmp_path / "a.csv", summary_row())
    b = write_summary(tmp_path / "b.csv", summary_row(judge_model="otro"))

    assert main(["compare", str(a), str(b)]) == 0

    output = capsys.readouterr().out
    assert NOT_COMPARABLE in output
    assert "judge_model: A=judge · B=otro" in output


def test_out_writes_the_csv_and_creates_the_folder(pair: tuple[Path, Path], tmp_path: Path) -> None:
    out = tmp_path / "nueva" / "comparacion.csv"

    assert main(["compare", str(pair[0]), str(pair[1]), "--out", str(out)]) == 0

    assert out.read_text(encoding="utf-8").splitlines()[0] == "metric,a_mean,a_n,b_mean,b_n,delta"
    assert not out.read_bytes().startswith(b"\xef\xbb\xbf")


def test_an_unreadable_summary_exits_with_two_and_writes_nothing(
    tmp_path: Path, pair: tuple[Path, Path], capsys: pytest.CaptureFixture[str]
) -> None:
    out = tmp_path / "out.csv"

    code = main(["compare", str(tmp_path / "nada.csv"), str(pair[1]), "--out", str(out)])

    assert code == 2
    assert "No se pudo leer" in capsys.readouterr().err
    assert not out.exists()


def test_comparing_opens_no_connection(pair: tuple[Path, Path], monkeypatch: pytest.MonkeyPatch) -> None:
    def refuse(*args: object, **kwargs: object) -> None:
        raise AssertionError("compare must not use the network")

    monkeypatch.setattr(socket, "socket", refuse)

    assert main(["compare", str(pair[0]), str(pair[1])]) == 0
