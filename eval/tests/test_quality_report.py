"""The two CSV files of the answer quality measurement and the text printed at the end (specs/LAB-29, FR-015 to FR-019).

The report is built by hand, with values worked out on paper, so every number the CSV shows can be checked from the
detail rows: that is exactly what SC-004 asks a person to do with a spreadsheet.
"""

import csv
from pathlib import Path

import pytest

from shoplab_eval.chat_client import ChatTurn, ContextItem
from shoplab_eval.constants import QUALITY_DETAIL_FILE, QUALITY_SUMMARY_FILE
from shoplab_eval.golden import GoldenCase
from shoplab_eval.langfuse_client import LangfuseStatus, trace_id_for
from shoplab_eval.quality_judge import JudgeSettings
from shoplab_eval.quality_report import format_quality_report, write_quality_report
from shoplab_eval.quality_run import QualityCaseResult, QualityReport, RunInfo, request_id_for
from shoplab_eval.quality_scores import CaseScores, classify_case, summarize_quality

RUN_ID = "20261118T153000Z"
ANSWER_TEXT = "TEXTO-DE-LA-RESPUESTA-QUE-NO-DEBE-ESCRIBIRSE"
FRAGMENT_TEXT = "TEXTO-DEL-FRAGMENTO-QUE-NO-DEBE-ESCRIBIRSE"

SUMMARY_COLUMNS = [
    "scope", "cases", "excluded_cases",
    "faithfulness_mean", "faithfulness_n", "faithfulness_empty",
    "answer_relevancy_mean", "answer_relevancy_n", "answer_relevancy_empty",
    "context_precision_mean", "context_precision_n", "context_precision_empty",
    "abstained", "run_id", "judge_model", "judge_underlying_model", "judge_temperature", "ragas_version",
    "abstention_prompt_version", "assistant_model", "embedding_model", "langfuse", "prompt_versions",
]  # fmt: skip
DETAIL_COLUMNS = [
    "id", "category", "question", "kind", "faithfulness", "answer_relevancy", "context_precision", "abstained",
    "context_documents", "context_fragments", "trace_id", "langfuse",
]  # fmt: skip


def golden(case_id: str, category: str, documents: tuple[str, ...], question: str | None = None) -> GoldenCase:
    return GoldenCase(case_id, question or f"Pregunta {case_id}", f"Referencia {case_id}", documents, category)


def result(
    case: GoldenCase, scores: CaseScores, fragments: tuple[tuple[str, str | None], ...] = ()
) -> QualityCaseResult:
    request_id = request_id_for(RUN_ID, case.id)
    context = tuple(
        ContextItem(number, title, "", FRAGMENT_TEXT, document_id)
        for number, (title, document_id) in enumerate(fragments, 1)
    )
    return QualityCaseResult(
        case=case,
        kind=classify_case(case),
        turn=ChatTurn(request_id, ANSWER_TEXT, context),
        scores=scores,
        trace_id=trace_id_for(request_id),
        langfuse=LangfuseStatus.SKIPPED,
    )


def worked_report() -> QualityReport:
    results = (
        result(golden("G001", "payments", ("d1",)), CaseScores(0.8, 0.6, 1.0, False),
               (("Pago en cuotas", "payments-02-installments"), ("Pago en cuotas", "payments-02-installments"),
                ("Documento a mano", None))),
        result(golden("G002", "payments", ("d1",)), CaseScores(None, 0.3, 0.0, True)),
        result(golden("G003", "privacy", ()), CaseScores(1.0, None, None, False), (("Tus derechos", "privacy-03"),)),
        result(golden("G004", "off_topic", ()), CaseScores(None, None, None, True)),
        result(golden("G005", "returns", ("d2",), question='¿Cuántas cuotas, "sin interés", en 5 días hábiles, ñandú?'),
               CaseScores(0.5, 0.7, 0.3333, False), (("Devoluciones", "returns-01"),)),
    )  # fmt: skip
    excluded = {"small_talk": 2, "injection": 1}
    info = RunInfo(
        run_id=RUN_ID,
        judge=JudgeSettings(model="judge", temperature=0.0),
        judge_underlying_model="qwen2.5:7b",
        ragas_version="0.4.4",
        abstention_prompt_version="1",
        assistant_model="llama3.2:3b",
        embedding_model="bge-m3",
        langfuse="skipped",
    )
    return QualityReport(
        info=info,
        results=results,
        summaries=summarize_quality([(r.case, r.scores) for r in results], excluded),
        excluded=3,
    )


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


@pytest.fixture
def written(tmp_path: Path) -> tuple[Path, Path]:
    return write_quality_report(worked_report(), tmp_path / "out")


# --- the files and their columns ------------------------------------------------------------------------------


def test_both_files_are_written_in_the_folder_and_it_is_created_when_missing(tmp_path: Path) -> None:
    summary, detail = write_quality_report(worked_report(), tmp_path / "a" / "b")

    assert (summary.name, detail.name) == (QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE)
    assert summary.parent == detail.parent == tmp_path / "a" / "b"
    assert summary.is_file() and detail.is_file()


def test_the_columns_are_exactly_those_of_the_contract_and_in_its_order(written: tuple[Path, Path]) -> None:
    summary, detail = written

    assert summary.read_text(encoding="utf-8").splitlines()[0].split(",") == SUMMARY_COLUMNS
    assert detail.read_text(encoding="utf-8").splitlines()[0].split(",") == DETAIL_COLUMNS


def test_the_summary_has_the_all_row_and_one_per_category_with_measured_cases(written: tuple[Path, Path]) -> None:
    rows = read_csv(written[0])

    assert [row["scope"] for row in rows] == ["all", "payments", "privacy", "returns", "off_topic"]
    assert rows[0]["cases"] == "5"
    assert rows[0]["excluded_cases"] == "3"


def test_the_detail_has_one_row_per_measured_case_in_the_order_of_the_dataset(written: tuple[Path, Path]) -> None:
    rows = read_csv(written[1])

    assert [row["id"] for row in rows] == ["G001", "G002", "G003", "G004", "G005"]
    assert [row["kind"] for row in rows] == ["answered", "answered", "unanswered", "unanswered", "answered"]


# --- values ---------------------------------------------------------------------------------------------------


def test_numbers_have_four_decimals_with_a_point_and_an_empty_score_is_an_empty_cell(written: tuple[Path, Path]) -> None:
    rows = {row["id"]: row for row in read_csv(written[1])}

    assert rows["G001"]["faithfulness"] == "0.8000"
    assert rows["G005"]["context_precision"] == "0.3333"
    assert rows["G002"]["faithfulness"] == ""  # it abstained: empty by definition
    assert rows["G003"]["answer_relevancy"] == ""  # it does not apply to an unanswered case
    assert rows["G004"]["faithfulness"] == ""
    cells = [cell for path in written for row in read_csv(path) for cell in row.values()]
    assert not [cell for cell in cells if cell.strip().lower() in ("nan", "none", "null")]


def test_a_real_zero_is_written_as_a_zero_and_is_not_confused_with_an_empty_cell(written: tuple[Path, Path]) -> None:
    rows = {row["id"]: row for row in read_csv(written[1])}

    assert rows["G002"]["context_precision"] == "0.0000"


def test_the_abstention_is_true_or_false(written: tuple[Path, Path]) -> None:
    rows = {row["id"]: row for row in read_csv(written[1])}

    assert (rows["G001"]["abstained"], rows["G002"]["abstained"]) == ("false", "true")


def test_the_documents_of_the_context_are_one_per_fragment_in_order_with_repeats_and_unknown_titles(
    written: tuple[Path, Path],
) -> None:
    rows = {row["id"]: row for row in read_csv(written[1])}

    assert rows["G001"]["context_documents"] == "payments-02-installments|payments-02-installments|?Documento a mano"
    assert rows["G001"]["context_fragments"] == "3"
    assert rows["G002"]["context_documents"] == ""
    assert rows["G002"]["context_fragments"] == "0"


def test_every_row_points_at_its_trace_and_says_whether_the_scores_were_attached(written: tuple[Path, Path]) -> None:
    for row in read_csv(written[1]):
        assert row["trace_id"] == trace_id_for(request_id_for(RUN_ID, row["id"]))
        assert row["langfuse"] == "skipped"


def test_the_summary_records_what_makes_two_results_comparable(written: tuple[Path, Path]) -> None:
    for row in read_csv(written[0]):
        assert row["run_id"] == RUN_ID
        assert (row["judge_model"], row["judge_underlying_model"]) == ("judge", "qwen2.5:7b")
        assert row["judge_temperature"] == "0.0000"
        assert (row["ragas_version"], row["abstention_prompt_version"]) == ("0.4.4", "1")
        assert (row["assistant_model"], row["embedding_model"], row["langfuse"]) == ("llama3.2:3b", "bge-m3", "skipped")


# --- the format -----------------------------------------------------------------------------------------------


def test_the_files_are_utf_8_without_bom_with_unix_line_ends_and_keep_accents(written: tuple[Path, Path]) -> None:
    for path in written:
        data = path.read_bytes()
        assert not data.startswith(b"\xef\xbb\xbf")
        assert b"\r" not in data
        data.decode("utf-8")
    assert "ñandú" in written[1].read_text(encoding="utf-8")


def test_a_question_with_commas_and_quotes_is_escaped_and_read_back_whole(written: tuple[Path, Path]) -> None:
    rows = {row["id"]: row for row in read_csv(written[1])}

    assert rows["G005"]["question"] == '¿Cuántas cuotas, "sin interés", en 5 días hábiles, ñandú?'


def test_the_files_never_contain_the_text_of_the_answer_or_of_the_fragments(written: tuple[Path, Path]) -> None:
    for path in written:
        text = path.read_text(encoding="utf-8")
        assert ANSWER_TEXT not in text
        assert FRAGMENT_TEXT not in text


# --- the summary can be recomputed from the detail (SC-004) ---------------------------------------------------


METRICS = ("faithfulness", "answer_relevancy", "context_precision")


def test_every_mean_count_and_empty_of_the_summary_is_reproduced_from_the_detail(written: tuple[Path, Path]) -> None:
    summary_rows = read_csv(written[0])
    detail_rows = read_csv(written[1])

    for summary in summary_rows:
        scope_rows = [row for row in detail_rows if summary["scope"] in ("all", row["category"])]
        assert int(summary["cases"]) == len(scope_rows)
        assert int(summary["abstained"]) == sum(row["abstained"] == "true" for row in scope_rows)
        for metric in METRICS:
            applies = [row for row in scope_rows if metric == "faithfulness" or row["kind"] == "answered"]
            values = [float(row[metric]) for row in applies if row[metric] != ""]
            assert int(summary[f"{metric}_n"]) == len(values), (summary["scope"], metric)
            assert int(summary[f"{metric}_empty"]) == len(applies) - len(values), (summary["scope"], metric)
            if values:
                assert float(summary[f"{metric}_mean"]) == pytest.approx(sum(values) / len(values), abs=5e-5)
            else:
                assert summary[f"{metric}_mean"] == ""


def test_a_mean_without_values_is_an_empty_cell_not_a_zero(written: tuple[Path, Path]) -> None:
    off_topic = next(row for row in read_csv(written[0]) if row["scope"] == "off_topic")

    assert off_topic["faithfulness_mean"] == ""
    assert (off_topic["faithfulness_n"], off_topic["faithfulness_empty"]) == ("0", "1")


def test_a_report_without_measured_cases_writes_only_the_all_row_and_the_header_of_the_detail(tmp_path: Path) -> None:
    report = worked_report()
    empty = QualityReport(report.info, (), summarize_quality([], {"small_talk": 4}), 4)

    summary, detail = write_quality_report(empty, tmp_path)

    assert [row["scope"] for row in read_csv(summary)] == ["all"]
    assert read_csv(detail) == []
    assert detail.read_text(encoding="utf-8").splitlines() == [",".join(DETAIL_COLUMNS)]


# --- what is printed ------------------------------------------------------------------------------------------


def test_the_printed_summary_shows_the_run_the_judge_the_means_the_empties_and_the_exclusions() -> None:
    text = format_quality_report(worked_report())

    assert RUN_ID in text
    assert "judge" in text and "qwen2.5:7b" in text and "0.4.4" in text
    for scope in ("all", "payments", "privacy", "returns", "off_topic"):
        assert scope in text
    assert "0.7667" in text  # mean faithfulness of the all row: (0.8 + 1.0 + 0.5) / 3
    assert "0.5333" in text  # mean answer relevancy of the all row: (0.6 + 0.3 + 0.7) / 3
    assert "3 excluidos" in text
    assert "abstenciones" in text


def test_the_printed_summary_says_when_a_metric_has_no_value_instead_of_printing_a_zero() -> None:
    text = format_quality_report(worked_report())

    off_topic = next(line for line in text.splitlines() if line.lstrip().startswith("off_topic"))
    assert "sin valores" in off_topic
