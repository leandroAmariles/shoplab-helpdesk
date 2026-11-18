"""Retrieval metrics: hit rate@k, recall@k and MRR over documents (specs/LAB-28-retrieval-metrics, research R3 and R6).

Everything here is a pure function of lists of document ids: no network, no backend, no clock. The ranking is the list of
distinct documents the search returned, closest first; the expected documents are those of the golden case.
"""

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from shoplab_eval.constants import ALL_SCOPE, CATEGORIES
from shoplab_eval.golden import GoldenCase


@dataclass(frozen=True)
class CaseMetrics:
    """The metrics of one case; ``hit`` and ``recall`` have one entry per k, in the order the k were asked."""

    hit: Mapping[int, int]
    recall: Mapping[int, float]
    reciprocal_rank: float
    first_hit_rank: int | None


@dataclass(frozen=True)
class CaseResult:
    """A measured case: what the search returned (as distinct documents, closest first) and its metrics."""

    case: GoldenCase
    retrieved: tuple[str, ...]
    metrics: CaseMetrics


@dataclass(frozen=True)
class ScopeSummary:
    """The mean of the metrics over the measured cases of a scope: ``all`` or one category.

    ``excluded`` is how many cases of the scope were left out because they have no expected documents.
    """

    scope: str
    cases: int
    excluded: int
    hit_rate: Mapping[int, float]
    recall: Mapping[int, float]
    mrr: float


def distinct_documents(sources: Iterable[str]) -> tuple[str, ...]:
    """The documents of the returned chunks, closest first, each in the place of its closest chunk.

    A document is identified by its file name without extension, so the Markdown and the PDF of one document are the
    same document, and several chunks of a document take a single place.
    """
    seen: dict[str, None] = {}
    for source in sources:
        seen.setdefault(Path(source).stem, None)
    return tuple(seen)


def compute_case_metrics(expected: Sequence[str], retrieved: Sequence[str], ks: Sequence[int]) -> CaseMetrics:
    """hit@k, recall@k and the reciprocal rank of one case.

    ``hit@k`` is 1 when some expected document is among the first k, ``recall@k`` is the share of expected documents
    among the first k, and the reciprocal rank is ``1 / place`` of the first expected document in the **whole** list
    (0 when none appears), never cut at k. A list shorter than k is measured with the documents it has.
    """
    expected_set = set(expected)
    first_hit = next((place for place, doc in enumerate(retrieved, start=1) if doc in expected_set), None)
    hit: dict[int, int] = {}
    recall: dict[int, float] = {}
    for k in ks:
        found = len(expected_set.intersection(retrieved[:k]))
        hit[k] = 1 if found else 0
        recall[k] = found / len(expected_set) if expected_set else 0.0
    return CaseMetrics(hit, recall, 1 / first_hit if first_hit else 0.0, first_hit)


def summarize(
    results: Sequence[CaseResult], excluded_by_category: Mapping[str, int], ks: Sequence[int]
) -> tuple[ScopeSummary, ...]:
    """``all`` first, then each category with at least one measured case, in the order of ``CATEGORIES``."""
    if not results:
        return ()
    summaries = [_scope_summary(ALL_SCOPE, results, sum(excluded_by_category.values()), ks)]
    for category in CATEGORIES:
        measured = [result for result in results if result.case.category == category]
        if measured:
            summaries.append(_scope_summary(category, measured, excluded_by_category.get(category, 0), ks))
    return tuple(summaries)


def _scope_summary(scope: str, results: Sequence[CaseResult], excluded: int, ks: Sequence[int]) -> ScopeSummary:
    count = len(results)
    return ScopeSummary(
        scope=scope,
        cases=count,
        excluded=excluded,
        hit_rate={k: sum(result.metrics.hit[k] for result in results) / count for k in ks},
        recall={k: sum(result.metrics.recall[k] for result in results) / count for k in ks},
        mrr=sum(result.metrics.reciprocal_rank for result in results) / count,
    )
