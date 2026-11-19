"""The measurement over a dataset, with a fake searcher: no HTTP, no backend."""

import pytest

from fake_backend import FakeSearcher, indexed
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.retrieval_run import measure_retrieval
from shoplab_eval.search_client import IndexedDocument

KS = (1, 3, 5)


def _case(case_id: str, expected: list[str], category: str = "payments") -> GoldenCase:
    return GoldenCase(
        id=case_id,
        question=f"Pregunta {case_id}",
        reference_answer="Respuesta de referencia de prueba",
        expected_documents=tuple(expected),
        category=category,
    )


def _dataset() -> list[GoldenCase]:
    return [
        _case("G001", ["payments-01"]),
        _case("G002", ["payments-02", "payments-03"]),
        _case("G003", ["returns-01"], "returns"),
        _case("G004", [], "payments"),
        _case("G005", [], "off_topic"),
    ]


def _searcher(cases: list[GoldenCase]) -> FakeSearcher:
    rankings = {
        "Pregunta G001": ["payments-01.md", "returns-01.md"],
        "Pregunta G002": ["returns-01.md", "payments-02.md", "payments-02.pdf", "payments-03.md"],
        "Pregunta G003": ["privacy-01.md", "payments-01.md"],
    }
    return FakeSearcher(rankings, documents=indexed(cases))


def test_the_cases_without_expected_documents_are_not_searched_and_are_counted() -> None:
    cases = _dataset()
    searcher = _searcher(cases)

    report = measure_retrieval(cases, searcher, KS, depth=50)

    assert [query for query, _ in searcher.search_calls] == ["Pregunta G001", "Pregunta G002", "Pregunta G003"]
    assert report.excluded == 2
    assert [result.case.id for result in report.results] == ["G001", "G002", "G003"]


def test_every_search_asks_for_the_requested_depth() -> None:
    cases = _dataset()
    searcher = _searcher(cases)

    measure_retrieval(cases, searcher, KS, depth=30)

    assert {limit for _, limit in searcher.search_calls} == {30}


def test_the_report_records_the_model_the_depth_and_the_k_used() -> None:
    cases = _dataset()

    report = measure_retrieval(cases, _searcher(cases), (2, 4), depth=20)

    assert report.model == "bge-m3"
    assert report.depth == 20
    assert report.ks == (2, 4)


def test_the_chunks_become_distinct_documents_in_the_order_of_the_search() -> None:
    cases = _dataset()

    report = measure_retrieval(cases, _searcher(cases), KS, depth=50)

    by_id = {result.case.id: result for result in report.results}
    assert by_id["G002"].retrieved == ("returns-01", "payments-02", "payments-03")


def test_the_metrics_of_each_case_follow_the_definitions() -> None:
    cases = _dataset()

    report = measure_retrieval(cases, _searcher(cases), KS, depth=50)

    by_id = {result.case.id: result.metrics for result in report.results}
    assert by_id["G001"].first_hit_rank == 1
    assert by_id["G001"].reciprocal_rank == 1.0
    assert by_id["G002"].first_hit_rank == 2
    assert by_id["G002"].recall == {1: 0.0, 3: 1.0, 5: 1.0}
    assert by_id["G003"].first_hit_rank is None
    assert by_id["G003"].hit == {1: 0, 3: 0, 5: 0}


def test_the_summary_has_the_whole_dataset_and_each_category_with_measured_cases() -> None:
    cases = _dataset()

    report = measure_retrieval(cases, _searcher(cases), KS, depth=50)

    assert [summary.scope for summary in report.summaries] == ["all", "payments", "returns"]
    everything = report.summaries[0]
    assert everything.cases == 3
    assert everything.excluded == 2
    assert everything.hit_rate[3] == pytest.approx(2 / 3)
    assert everything.mrr == pytest.approx((1 + 0.5 + 0) / 3)


# --- the measurement is not valid: errors (US3) ---


def _measure_expecting_error(cases: list[GoldenCase], searcher: FakeSearcher) -> MeasurementError:
    with pytest.raises(MeasurementError) as error:
        measure_retrieval(cases, searcher, KS, depth=50)
    return error.value


def test_a_missing_document_stops_the_measurement_before_any_search() -> None:
    cases = _dataset()
    searcher = FakeSearcher({}, documents=[d for d in indexed(cases) if d.name != "payments-03.md"])

    error = _measure_expecting_error(cases, searcher)

    assert "payments-03" in str(error)
    assert "payments-01" not in str(error)
    assert searcher.search_calls == []
    assert searcher.list_calls == 1


@pytest.mark.parametrize("status", ["PENDING", "ERROR"])
def test_a_document_that_is_not_indexed_is_reported_with_its_state(status: str) -> None:
    cases = _dataset()
    documents = [d if d.name != "returns-01.md" else IndexedDocument(d.name, status) for d in indexed(cases)]
    searcher = FakeSearcher({}, documents=documents)

    error = _measure_expecting_error(cases, searcher)

    assert "returns-01" in str(error)
    assert status in str(error)
    assert searcher.search_calls == []


def test_every_missing_document_is_listed_at_once() -> None:
    cases = _dataset()
    searcher = FakeSearcher({}, documents=[])

    error = _measure_expecting_error(cases, searcher)

    for doc in ("payments-01", "payments-02", "payments-03", "returns-01"):
        assert doc in str(error)


def test_a_document_indexed_in_either_format_counts_once_and_is_enough() -> None:
    cases = [_case("G001", ["payments-01"])]
    documents = [IndexedDocument("payments-01.md", "ERROR"), IndexedDocument("payments-01.pdf", "INDEXED")]
    searcher = FakeSearcher({"Pregunta G001": ["payments-01.pdf"]}, documents=documents)

    report = measure_retrieval(cases, searcher, KS, depth=50)

    assert report.results[0].metrics.first_hit_rank == 1


def test_a_failed_search_names_the_case_it_failed_on() -> None:
    cases = _dataset()
    searcher = _searcher(cases)
    searcher.fail_on["Pregunta G002"] = MeasurementError("La búsqueda respondió 503 (SLHD_RETR_00006)")

    error = _measure_expecting_error(cases, searcher)

    assert "G002" in str(error)
    assert "503" in str(error)
    assert isinstance(error.__cause__, MeasurementError)


def test_a_change_of_model_during_the_run_is_reported_with_both_names() -> None:
    cases = _dataset()
    searcher = _searcher(cases)
    searcher.models_by_call = ["bge-m3", "bge-m3", "otro-modelo"]

    error = _measure_expecting_error(cases, searcher)

    assert "bge-m3" in str(error)
    assert "otro-modelo" in str(error)
    assert "G003" in str(error)


def test_a_search_that_works_but_finds_nothing_is_a_zero_not_an_error() -> None:
    cases = [_case("G001", ["payments-01"])]
    searcher = FakeSearcher({}, documents=indexed(cases))

    report = measure_retrieval(cases, searcher, KS, depth=50)

    assert report.results[0].retrieved == ()
    assert report.results[0].metrics.hit == {1: 0, 3: 0, 5: 0}
    assert report.summaries[0].mrr == 0.0


def test_fewer_documents_than_k_is_not_an_error() -> None:
    cases = [_case("G001", ["payments-01"])]
    searcher = FakeSearcher({"Pregunta G001": ["payments-01.md"]}, documents=indexed(cases))

    report = measure_retrieval(cases, searcher, (1, 3, 5), depth=50)

    assert report.results[0].metrics.hit == {1: 1, 3: 1, 5: 1}
