"""The retrieval metrics on hand-made rankings: no network, no backend (FR-017)."""

import pytest

from shoplab_eval.constants import CATEGORIES
from shoplab_eval.golden import GoldenCase
from shoplab_eval.retrieval_metrics import (
    CaseResult,
    compute_case_metrics,
    distinct_documents,
    summarize,
)

KS = (1, 3, 5)


def _result(case_id: str, category: str, expected: list[str], retrieved: list[str], ks=KS) -> CaseResult:
    case = GoldenCase(
        id=case_id,
        question=f"Pregunta {case_id}",
        reference_answer="Respuesta de referencia de prueba",
        expected_documents=tuple(expected),
        category=category,
    )
    return CaseResult(case, tuple(retrieved), compute_case_metrics(expected, retrieved, ks))


def test_the_expected_document_in_first_place_scores_full_everywhere() -> None:
    metrics = compute_case_metrics(["a"], ["a", "b", "c"], KS)

    assert metrics.hit == {1: 1, 3: 1, 5: 1}
    assert metrics.recall == {1: 1.0, 3: 1.0, 5: 1.0}
    assert metrics.reciprocal_rank == 1.0
    assert metrics.first_hit_rank == 1


def test_a_hit_in_third_place_is_a_miss_at_one_and_a_hit_from_three() -> None:
    metrics = compute_case_metrics(["c"], ["a", "b", "c", "d"], KS)

    assert metrics.hit == {1: 0, 3: 1, 5: 1}
    assert metrics.recall == {1: 0.0, 3: 1.0, 5: 1.0}
    assert metrics.reciprocal_rank == pytest.approx(1 / 3)
    assert metrics.first_hit_rank == 3


def test_two_expected_documents_with_one_found_give_half_recall_and_a_hit() -> None:
    metrics = compute_case_metrics(["a", "z"], ["a", "b", "c", "d", "e", "z"], KS)

    assert metrics.hit == {1: 1, 3: 1, 5: 1}
    assert metrics.recall == {1: 0.5, 3: 0.5, 5: 0.5}
    assert metrics.first_hit_rank == 1


def test_the_second_expected_document_raises_recall_once_it_is_inside_k() -> None:
    metrics = compute_case_metrics(["a", "b"], ["a", "x", "b"], KS)

    assert metrics.recall == {1: 0.5, 3: 1.0, 5: 1.0}


def test_with_k_one_and_two_expected_documents_recall_cannot_exceed_half() -> None:
    metrics = compute_case_metrics(["a", "b"], ["b", "a"], (1,))

    assert metrics.recall == {1: 0.5}
    assert metrics.hit == {1: 1}


def test_no_expected_document_in_the_list_scores_zero_everywhere() -> None:
    metrics = compute_case_metrics(["a"], ["x", "y", "z"], KS)

    assert metrics.hit == {1: 0, 3: 0, 5: 0}
    assert metrics.recall == {1: 0.0, 3: 0.0, 5: 0.0}
    assert metrics.reciprocal_rank == 0.0
    assert metrics.first_hit_rank is None


def test_an_empty_list_is_a_miss_not_an_error() -> None:
    metrics = compute_case_metrics(["a"], [], KS)

    assert metrics.hit == {1: 0, 3: 0, 5: 0}
    assert metrics.first_hit_rank is None


def test_fewer_documents_than_k_are_measured_with_the_ones_there_are() -> None:
    metrics = compute_case_metrics(["b"], ["a", "b"], KS)

    assert metrics.hit == {1: 0, 3: 1, 5: 1}
    assert metrics.first_hit_rank == 2


def test_the_reciprocal_rank_uses_the_whole_list_not_only_the_largest_k() -> None:
    retrieved = ["a", "b", "c", "d", "e", "f", "target"]

    metrics = compute_case_metrics(["target"], retrieved, KS)

    assert metrics.hit == {1: 0, 3: 0, 5: 0}
    assert metrics.reciprocal_rank == pytest.approx(1 / 7)
    assert metrics.first_hit_rank == 7


def test_repeated_chunks_of_one_document_take_a_single_place() -> None:
    assert distinct_documents(["a.md", "a.md", "b.md", "a.md", "c.md"]) == ("a", "b", "c")


def test_the_markdown_and_the_pdf_of_a_document_are_the_same_document() -> None:
    sources = ["payments-01.pdf", "payments-02.md", "payments-01.md"]

    assert distinct_documents(sources) == ("payments-01", "payments-02")


def test_the_place_of_a_document_is_the_place_of_its_closest_chunk() -> None:
    retrieved = distinct_documents(["a.md", "b.md", "b.md", "c.md"])

    metrics = compute_case_metrics(["c"], retrieved, KS)

    assert retrieved == ("a", "b", "c")
    assert metrics.first_hit_rank == 3


def test_a_custom_set_of_k_is_reported_in_the_order_given() -> None:
    metrics = compute_case_metrics(["a"], ["x", "a"], (4, 2))

    assert list(metrics.hit) == [4, 2]
    assert metrics.hit == {4: 1, 2: 1}


def test_the_summary_averages_by_scope_with_all_first_and_the_categories_in_order() -> None:
    results = [
        _result("G001", "returns", ["r1"], ["r1", "x"]),
        _result("G002", "payments", ["p1"], ["x", "p1"]),
        _result("G003", "payments", ["p2"], ["x", "y", "z"]),
    ]

    summaries = summarize(results, {"payments": 1, "off_topic": 2}, KS)

    assert [summary.scope for summary in summaries] == ["all", "payments", "returns"]
    everything, payments, returns = summaries
    assert (everything.cases, everything.excluded) == (3, 3)
    assert everything.hit_rate == {1: pytest.approx(1 / 3), 3: pytest.approx(2 / 3), 5: pytest.approx(2 / 3)}
    assert everything.mrr == pytest.approx((1 + 0.5 + 0) / 3)
    assert (payments.cases, payments.excluded) == (2, 1)
    assert payments.hit_rate == {1: 0.0, 3: 0.5, 5: 0.5}
    assert payments.recall == {1: 0.0, 3: 0.5, 5: 0.5}
    assert payments.mrr == pytest.approx(0.25)
    assert (returns.cases, returns.excluded) == (1, 0)
    assert returns.mrr == 1.0


def test_a_category_without_measurable_cases_has_no_row() -> None:
    results = [_result("G001", "payments", ["p1"], ["p1"])]

    summaries = summarize(results, {"off_topic": 8, "privacy": 2}, KS)

    assert [summary.scope for summary in summaries] == ["all", "payments"]
    assert summaries[0].excluded == 10


def test_the_categories_follow_the_order_of_the_dataset_rules() -> None:
    results = [_result(f"G{index:03d}", category, ["d"], ["d"]) for index, category in enumerate(reversed(CATEGORIES))
               if category != "off_topic"]

    summaries = summarize(results, {}, KS)

    expected = ["all", *[category for category in CATEGORIES if category != "off_topic"]]
    assert [summary.scope for summary in summaries] == expected


def test_a_summary_without_cases_is_empty() -> None:
    assert summarize([], {"off_topic": 3}, KS) == ()
