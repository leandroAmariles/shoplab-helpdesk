"""The CSV files of a measurement: columns, numbers, invariants and determinism (FR-009 to FR-013)."""

import csv
import io
from pathlib import Path

import pytest

from fake_backend import FakeSearcher, indexed
from shoplab_eval.golden import GoldenCase
from shoplab_eval.retrieval_report import detail_csv, summary_csv, write_report
from shoplab_eval.retrieval_run import RetrievalReport, measure_retrieval

QUESTION_WITH_EVERYTHING = '¿Cuántos días hábiles, "corridos" o no, tarda el envío a Ñuñoa?'


def _case(case_id: str, expected: list[str], category: str, question: str | None = None) -> GoldenCase:
    return GoldenCase(
        id=case_id,
        question=question or f"Pregunta {case_id}",
        reference_answer="La respuesta de referencia no debe aparecer en ningún CSV",
        expected_documents=tuple(expected),
        category=category,
    )


def _report(ks: tuple[int, ...] = (1, 3, 5)) -> RetrievalReport:
    cases = [
        _case("G001", ["payments-01"], "payments"),
        _case("G002", ["payments-02", "payments-03"], "payments"),
        _case("G003", ["returns-01"], "returns"),
        _case("G004", ["shipping-01"], "shipping", QUESTION_WITH_EVERYTHING),
        _case("G005", [], "payments"),
        _case("G006", [], "off_topic"),
    ]
    rankings = {
        "Pregunta G001": ["payments-01.md", "returns-01.md"],
        "Pregunta G002": ["returns-01.md", "payments-02.md", "payments-02.pdf"],
        "Pregunta G003": ["privacy-01.md", "payments-01.md"],
        QUESTION_WITH_EVERYTHING: ["x-01.md", "x-02.md", "shipping-01.md"],
    }
    return measure_retrieval(cases, FakeSearcher(rankings, documents=indexed(cases)), ks, depth=50)


def _rows(text: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(text, newline="")))


def test_the_summary_has_the_columns_of_the_contract() -> None:
    header = summary_csv(_report()).splitlines()[0]

    assert header == (
        "scope,cases,excluded_cases,embedding_model,search_depth,hit_rate@1,hit_rate@3,hit_rate@5,"
        "recall@1,recall@3,recall@5,mrr"
    )


def test_the_detail_has_the_columns_of_the_contract() -> None:
    header = detail_csv(_report()).splitlines()[0]

    assert header == (
        "id,category,question,expected_documents,retrieved_documents,first_hit_rank,hit@1,hit@3,hit@5,"
        "recall@1,recall@3,recall@5,reciprocal_rank"
    )


def test_a_custom_k_gets_its_own_columns_in_the_order_given() -> None:
    report = _report(ks=(2, 4))

    assert summary_csv(report).splitlines()[0] == (
        "scope,cases,excluded_cases,embedding_model,search_depth,hit_rate@2,hit_rate@4,recall@2,recall@4,mrr"
    )
    assert detail_csv(report).splitlines()[0].endswith("first_hit_rank,hit@2,hit@4,recall@2,recall@4,reciprocal_rank")


def test_the_summary_has_a_row_for_the_whole_dataset_and_one_per_category_with_cases() -> None:
    rows = _rows(summary_csv(_report()))

    assert [row["scope"] for row in rows] == ["all", "payments", "returns", "shipping"]
    everything = rows[0]
    assert everything["cases"] == "4"
    assert everything["excluded_cases"] == "2"
    assert everything["embedding_model"] == "bge-m3"
    assert everything["search_depth"] == "50"
    assert rows[1]["excluded_cases"] == "1"


def test_the_detail_has_one_row_per_measured_case_in_the_order_of_the_dataset() -> None:
    rows = _rows(detail_csv(_report()))

    assert [row["id"] for row in rows] == ["G001", "G002", "G003", "G004"]


def test_numbers_have_four_decimals_with_a_point_and_hits_are_zero_or_one() -> None:
    rows = {row["id"]: row for row in _rows(detail_csv(_report()))}

    third = rows["G004"]
    assert third["hit@1"] == "0"
    assert third["hit@3"] == "1"
    assert third["recall@3"] == "1.0000"
    assert third["recall@1"] == "0.0000"
    assert third["reciprocal_rank"] == "0.3333"
    summary = _rows(summary_csv(_report()))[0]
    assert summary["hit_rate@3"] == "0.7500"
    assert summary["mrr"] == "0.4583"


def test_the_documents_are_joined_with_a_bar_and_a_miss_leaves_the_rank_empty() -> None:
    rows = {row["id"]: row for row in _rows(detail_csv(_report()))}

    assert rows["G002"]["expected_documents"] == "payments-02|payments-03"
    assert rows["G002"]["retrieved_documents"] == "returns-01|payments-02"
    assert rows["G002"]["first_hit_rank"] == "2"
    assert rows["G003"]["first_hit_rank"] == ""
    assert rows["G003"]["reciprocal_rank"] == "0.0000"


def test_a_case_with_two_expected_documents_and_one_found_has_half_recall_and_a_hit() -> None:
    row = {row["id"]: row for row in _rows(detail_csv(_report()))}["G002"]

    assert row["hit@3"] == "1"
    assert row["recall@3"] == "0.5000"
    assert row["recall@5"] == "0.5000"


def test_a_question_with_commas_quotes_accents_and_enes_is_read_back_intact() -> None:
    rows = {row["id"]: row for row in _rows(detail_csv(_report()))}

    assert rows["G004"]["question"] == QUESTION_WITH_EVERYTHING
    assert rows["G004"]["expected_documents"] == "shipping-01"


def test_every_value_of_the_summary_is_the_mean_of_its_column_in_the_detail() -> None:
    report = _report()
    detail = _rows(detail_csv(report))
    for summary in _rows(summary_csv(report)):
        scoped = [row for row in detail if summary["scope"] in ("all", row["category"])]
        assert int(summary["cases"]) == len(scoped)
        for column in ("hit@1", "hit@3", "hit@5", "recall@1", "recall@3", "recall@5"):
            expected = sum(float(row[column]) for row in scoped) / len(scoped)
            summary_column = column.replace("hit@", "hit_rate@")
            assert float(summary[summary_column]) == pytest.approx(expected, abs=1e-4)
        assert float(summary["mrr"]) == pytest.approx(
            sum(float(row["reciprocal_rank"]) for row in scoped) / len(scoped), abs=1e-4
        )


def test_the_measured_and_excluded_cases_add_up_to_the_dataset() -> None:
    everything = _rows(summary_csv(_report()))[0]

    assert int(everything["cases"]) + int(everything["excluded_cases"]) == 6


def test_a_scope_without_measured_cases_has_no_row() -> None:
    assert "off_topic" not in summary_csv(_report())


def test_two_writes_of_the_same_report_are_identical_byte_for_byte(tmp_path: Path) -> None:
    first = write_report(_report(), tmp_path / "a")
    second = write_report(_report(), tmp_path / "b")

    for one, other in zip(first, second, strict=True):
        assert one.read_bytes() == other.read_bytes()


def test_the_files_are_utf8_without_bom_with_unix_line_ends_and_no_timestamps(tmp_path: Path) -> None:
    summary_path, detail_path = write_report(_report(), tmp_path)

    for path in (summary_path, detail_path):
        raw = path.read_bytes()
        assert not raw.startswith(b"\xef\xbb\xbf")
        assert b"\r" not in raw
        assert raw.endswith(b"\n")
        assert str(tmp_path).encode() not in raw
        assert "2026" not in raw.decode("utf-8")
    assert "Ñuñoa" in detail_path.read_text(encoding="utf-8")


def test_no_file_contains_chunk_text_or_the_reference_answers(tmp_path: Path) -> None:
    summary_path, detail_path = write_report(_report(), tmp_path)

    for path in (summary_path, detail_path):
        text = path.read_text(encoding="utf-8")
        assert "La respuesta de referencia" not in text
        assert "texto " not in text


def test_the_output_folder_is_created_when_it_does_not_exist(tmp_path: Path) -> None:
    out = tmp_path / "deep" / "results"

    summary_path, detail_path = write_report(_report(), out)

    assert summary_path == out / "retrieval-summary.csv"
    assert detail_path == out / "retrieval-detail.csv"
    assert summary_path.is_file() and detail_path.is_file()
