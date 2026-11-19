"""Which metrics a case gets, what an empty score is, and the means by scope (specs/LAB-29, FR-006, FR-011, FR-023).

Everything is pure: no network, no database and no model. The values are worked out by hand.
"""

import math

import pytest

from shoplab_eval.constants import ALL_SCOPE
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.quality_scores import (
    CaseKind,
    CaseScores,
    MetricStat,
    applicable_metrics,
    classify_case,
    summarize_quality,
)


def case(case_id: str, category: str, documents: tuple[str, ...] = ()) -> GoldenCase:
    return GoldenCase(
        id=case_id,
        question=f"Pregunta {case_id}",
        reference_answer=f"Respuesta {case_id}",
        expected_documents=documents,
        category=category,
    )


def scopes(scored, excluded=None):
    return {summary.scope: summary for summary in summarize_quality(scored, excluded or {})}


# --- kind of case and the metrics it gets --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("category", "documents", "kind"),
    [
        ("payments", ("payments-01-a",), CaseKind.ANSWERED),
        ("payments", (), CaseKind.UNANSWERED),
        ("off_topic", (), CaseKind.UNANSWERED),
        ("small_talk", (), CaseKind.CONVERSATION),
        ("injection", (), CaseKind.CONVERSATION),
    ],
)
def test_the_kind_comes_from_the_documents_and_the_category(category, documents, kind) -> None:
    assert classify_case(case("G001", category, documents)) is kind


def test_an_answered_case_gets_the_three_metrics() -> None:
    assert applicable_metrics(CaseKind.ANSWERED) == ("faithfulness", "answer_relevancy", "context_precision")


def test_an_unanswered_case_is_only_scored_on_faithfulness() -> None:
    assert applicable_metrics(CaseKind.UNANSWERED) == ("faithfulness",)


def test_a_conversation_case_gets_no_metric() -> None:
    assert applicable_metrics(CaseKind.CONVERSATION) == ()


# --- a score is a number between 0 and 1, or empty -----------------------------------------------------------------


def test_scores_between_zero_and_one_are_accepted_and_empty_is_none() -> None:
    scores = CaseScores(faithfulness=0.0, answer_relevancy=1.0, context_precision=None, abstained=False)

    assert (scores.faithfulness, scores.answer_relevancy, scores.context_precision) == (0.0, 1.0, None)


@pytest.mark.parametrize("bad", [-0.01, 1.01, 2.0, math.nan, math.inf])
def test_a_score_out_of_range_or_not_a_number_is_an_error_and_is_never_clipped(bad: float) -> None:
    with pytest.raises(MeasurementError, match="fuera de 0 a 1|no es un número"):
        CaseScores(faithfulness=bad, answer_relevancy=None, context_precision=None, abstained=False)


# --- means by scope ------------------------------------------------------------------------------------------------


def worked_example():
    scored = [
        (case("G001", "payments", ("d1",)), CaseScores(0.8, 0.6, 1.0, False)),
        (case("G002", "payments", ("d1",)), CaseScores(None, 0.4, 0.0, True)),  # it answered "no information"
        (case("G003", "privacy", ()), CaseScores(1.0, None, None, False)),  # unanswered, it did not invent anything
        (case("G004", "off_topic", ()), CaseScores(None, None, None, True)),  # unanswered and it abstained
    ]
    return scored, {"small_talk": 2, "injection": 1}


def test_the_all_scope_averages_every_value_and_counts_what_entered_and_what_is_empty() -> None:
    scored, excluded = worked_example()

    overall = scopes(scored, excluded)[ALL_SCOPE]

    assert overall.cases == 4
    assert overall.excluded == 3
    assert overall.faithfulness == MetricStat(mean=pytest.approx(0.9), entered=2, empty=2)
    assert overall.answer_relevancy == MetricStat(mean=pytest.approx(0.5), entered=2, empty=0)
    assert overall.context_precision == MetricStat(mean=pytest.approx(0.5), entered=2, empty=0)
    assert overall.abstained == 2


def test_each_category_with_measured_cases_has_its_own_row_and_the_conversation_ones_do_not() -> None:
    scored, excluded = worked_example()

    summaries = summarize_quality(scored, excluded)

    assert [summary.scope for summary in summaries] == [ALL_SCOPE, "payments", "privacy", "off_topic"]
    payments = summaries[1]
    assert payments.cases == 2
    assert payments.faithfulness == MetricStat(mean=pytest.approx(0.8), entered=1, empty=1)
    assert payments.answer_relevancy.mean == pytest.approx(0.5)
    assert payments.abstained == 1


def test_a_scope_with_no_value_in_a_metric_has_no_mean_instead_of_a_zero() -> None:
    scored, excluded = worked_example()

    off_topic = scopes(scored, excluded)["off_topic"]

    assert off_topic.faithfulness == MetricStat(mean=None, entered=0, empty=1)
    assert off_topic.abstained == 1


def test_a_metric_that_does_not_apply_to_the_case_is_not_counted_as_empty() -> None:
    scored = [(case("G001", "privacy", ()), CaseScores(0.5, None, None, False))]

    privacy = scopes(scored)["privacy"]

    assert privacy.answer_relevancy == MetricStat(mean=None, entered=0, empty=0)
    assert privacy.context_precision == MetricStat(mean=None, entered=0, empty=0)


def test_a_category_without_measured_cases_has_no_row() -> None:
    scored = [(case("G001", "payments", ("d1",)), CaseScores(1.0, 1.0, 1.0, False))]

    assert [summary.scope for summary in summarize_quality(scored, {"small_talk": 4})] == [ALL_SCOPE, "payments"]


def test_the_excluded_cases_are_reported_in_the_all_row_even_when_nothing_was_measured() -> None:
    summaries = summarize_quality([], {"small_talk": 3, "injection": 2})

    assert [summary.scope for summary in summaries] == [ALL_SCOPE]
    assert summaries[0].cases == 0
    assert summaries[0].excluded == 5
    assert summaries[0].faithfulness.mean is None


def test_a_conversation_case_is_never_scored() -> None:
    scored = [(case("G091", "small_talk", ()), CaseScores(None, None, None, False))]

    with pytest.raises(MeasurementError, match="G091"):
        summarize_quality(scored, {})


def test_a_value_for_a_metric_that_does_not_apply_is_an_error() -> None:
    scored = [(case("G003", "privacy", ()), CaseScores(0.5, 0.9, None, False))]

    with pytest.raises(MeasurementError, match="G003.*answer_relevancy"):
        summarize_quality(scored, {})


def test_the_means_do_not_depend_on_the_order_of_the_cases() -> None:
    scored, excluded = worked_example()

    forward = summarize_quality(scored, excluded)
    backward = summarize_quality(list(reversed(scored)), excluded)

    assert {s.scope: s for s in forward} == {s.scope: s for s in backward}
