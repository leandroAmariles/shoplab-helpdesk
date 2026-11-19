"""Answer quality scores: which metrics a case gets, what an empty score is and the means by scope (specs/LAB-29).

Everything here is pure: no network, no judge and no RAGAS. The orchestration decides how each score is obtained; this
module only says what a score is and how the scores of a run are summarized.

A score is a number between 0 and 1, or ``None``. ``None`` means the metric does not apply to the case or cannot be
computed by definition (an answer that only says it has no information has nothing to check against the context); it is
never a zero in disguise, it never enters a mean and it is counted apart. A failure of the judge is not a score: it is a
``MeasurementError`` raised before a ``CaseScores`` exists (FR-011).
"""

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum

from shoplab_eval.constants import (
    ALL_SCOPE,
    KNOWLEDGE_CATEGORIES,
    METRIC_ANSWER_RELEVANCY,
    METRIC_CONTEXT_PRECISION,
    METRIC_FAITHFULNESS,
    QUALITY_METRICS,
)
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase


class CaseKind(Enum):
    """What a case is for the quality measurement (data-model): it decides which metrics it gets."""

    ANSWERED = "answered"
    UNANSWERED = "unanswered"
    CONVERSATION = "conversation"


_APPLICABLE: dict[CaseKind, tuple[str, ...]] = {
    CaseKind.ANSWERED: QUALITY_METRICS,
    # The only thing to check in a case the knowledge base cannot answer is that the assistant did not invent one.
    CaseKind.UNANSWERED: (METRIC_FAITHFULNESS,),
    # Courtesies and injection attempts do not go through the retrieval: there is no context to judge (LAB-114).
    CaseKind.CONVERSATION: (),
}


def classify_case(case: GoldenCase) -> CaseKind:
    if case.is_conversation:
        return CaseKind.CONVERSATION
    return CaseKind.ANSWERED if case.answerable else CaseKind.UNANSWERED


def applicable_metrics(kind: CaseKind) -> tuple[str, ...]:
    return _APPLICABLE[kind]


@dataclass(frozen=True)
class CaseScores:
    """The scores of one case. ``None`` is an empty cell; a value is always between 0 and 1, never ``NaN``."""

    faithfulness: float | None
    answer_relevancy: float | None
    context_precision: float | None
    abstained: bool

    def __post_init__(self) -> None:
        for metric, value in self.by_metric().items():
            if value is None:
                continue
            if isinstance(value, bool) or not isinstance(value, (int, float)) or math.isnan(value):
                raise MeasurementError(f"La puntuación de {metric} no es un número ({value!r})")
            if not 0.0 <= value <= 1.0:
                raise MeasurementError(f"La puntuación de {metric} está fuera de 0 a 1 ({value!r}): no se recorta")

    def by_metric(self) -> dict[str, float | None]:
        return {
            METRIC_FAITHFULNESS: self.faithfulness,
            METRIC_ANSWER_RELEVANCY: self.answer_relevancy,
            METRIC_CONTEXT_PRECISION: self.context_precision,
        }


@dataclass(frozen=True)
class MetricStat:
    """One metric in one scope: its mean, the cases that entered it and the ones where it applied but was empty."""

    mean: float | None
    entered: int
    empty: int


@dataclass(frozen=True)
class QualityScopeSummary:
    """One row of the summary CSV: ``all`` or a category with measured cases."""

    scope: str
    cases: int
    excluded: int
    faithfulness: MetricStat
    answer_relevancy: MetricStat
    context_precision: MetricStat
    abstained: int

    def stat(self, metric: str) -> MetricStat:
        return {
            METRIC_FAITHFULNESS: self.faithfulness,
            METRIC_ANSWER_RELEVANCY: self.answer_relevancy,
            METRIC_CONTEXT_PRECISION: self.context_precision,
        }[metric]


def summarize_quality(
    scored: Sequence[tuple[GoldenCase, CaseScores]], excluded_by_category: Mapping[str, int]
) -> tuple[QualityScopeSummary, ...]:
    """The ``all`` row and one row per category with measured cases, in the order of the categories of the dataset.

    ``excluded_by_category`` counts the conversation cases that were not measured. A conversation case among the
    scored ones, or a value for a metric that does not apply to its kind, is a bug of the caller: it fails loudly.
    """
    for case, scores in scored:
        _check_applicable(case, scores)
    summaries = [_summarize(ALL_SCOPE, scored, sum(excluded_by_category.values()))]
    for category in KNOWLEDGE_CATEGORIES:
        of_category = [item for item in scored if item[0].category == category]
        if of_category:
            summaries.append(_summarize(category, of_category, excluded_by_category.get(category, 0)))
    return tuple(summaries)


def _check_applicable(case: GoldenCase, scores: CaseScores) -> None:
    kind = classify_case(case)
    if kind is CaseKind.CONVERSATION:
        raise MeasurementError(f"El caso {case.id} es de conversación: no se puntúa")
    applicable = applicable_metrics(kind)
    for metric, value in scores.by_metric().items():
        if metric not in applicable and value is not None:
            raise MeasurementError(f"El caso {case.id} no admite la métrica {metric}, pero trae un valor")


def _summarize(scope: str, scored: Sequence[tuple[GoldenCase, CaseScores]], excluded: int) -> QualityScopeSummary:
    stats = {metric: _stat(metric, scored) for metric in QUALITY_METRICS}
    return QualityScopeSummary(
        scope=scope,
        cases=len(scored),
        excluded=excluded,
        faithfulness=stats[METRIC_FAITHFULNESS],
        answer_relevancy=stats[METRIC_ANSWER_RELEVANCY],
        context_precision=stats[METRIC_CONTEXT_PRECISION],
        abstained=sum(1 for _, scores in scored if scores.abstained),
    )


def _stat(metric: str, scored: Sequence[tuple[GoldenCase, CaseScores]]) -> MetricStat:
    values: list[float] = []
    empty = 0
    for case, scores in scored:
        if metric not in applicable_metrics(classify_case(case)):
            continue
        value = scores.by_metric()[metric]
        if value is None:
            empty += 1
        else:
            values.append(float(value))
    mean = math.fsum(values) / len(values) if values else None
    return MetricStat(mean=mean, entered=len(values), empty=empty)
