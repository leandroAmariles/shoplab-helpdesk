"""The retrieval measurement: searches every measurable case and computes the metrics (specs/LAB-28)."""

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from shoplab_eval.constants import INDEXED_STATUS
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.retrieval_metrics import (
    CaseResult,
    ScopeSummary,
    compute_case_metrics,
    distinct_documents,
    summarize,
)
from shoplab_eval.search_client import IndexedDocument, Searcher


@dataclass(frozen=True)
class RetrievalReport:
    """A finished measurement: everything the CSV files and the printed summary are made of.

    It exists only for a complete measurement, so nothing that looks like a result is ever written after a failure.
    """

    model: str
    depth: int
    ks: tuple[int, ...]
    results: tuple[CaseResult, ...]
    summaries: tuple[ScopeSummary, ...]
    excluded: int


def measure_retrieval(
    cases: Sequence[GoldenCase], searcher: Searcher, ks: Sequence[int], depth: int
) -> RetrievalReport:
    """Searches each case that has expected documents (in the order of the dataset) and measures the ranking.

    The cases without expected documents cannot be scored with these metrics: they are not searched and are counted
    as excluded. The conversation cases (courtesies and injection attempts, LAB-114) have no search by design: they
    are neither searched nor counted. The ranking is the order of the search itself, before the selection the chat
    applies afterwards.

    Raises ``MeasurementError`` when the measurement would not be reliable: an expected document is not indexed (checked
    before the first search), a search fails (the message names the case) or the embedding model changes midway. A
    search that works but does not return the expected document is not an error: it is a zero.
    """
    measurable = [case for case in cases if case.expected_documents]
    excluded_by_category = Counter(
        case.category for case in cases if not case.expected_documents and not case.is_conversation
    )
    if measurable:
        check_index(searcher.list_documents(), {doc for case in measurable for doc in case.expected_documents})
    results: list[CaseResult] = []
    model = ""
    for case in measurable:
        try:
            response = searcher.search(case.question, depth)
        except MeasurementError as error:
            raise MeasurementError(f"Falló la búsqueda del caso {case.id}: {error}") from error
        if model and response.model != model:
            raise MeasurementError(
                f"El modelo de embeddings cambió durante la medición ({model!r} → {response.model!r}) en el caso "
                f"{case.id}: la base se reindexó a mitad de ejecución; vuelve a ejecutar la medición"
            )
        model = response.model
        retrieved = distinct_documents(hit.source for hit in response.hits)
        results.append(CaseResult(case, retrieved, compute_case_metrics(case.expected_documents, retrieved, ks)))
    return RetrievalReport(
        model=model,
        depth=depth,
        ks=tuple(ks),
        results=tuple(results),
        summaries=summarize(results, excluded_by_category, ks),
        excluded=sum(excluded_by_category.values()),
    )


def check_index(documents: Sequence[IndexedDocument], expected: set[str]) -> None:
    """Every expected document must be in the catalog with at least one file in ``INDEXED`` state.

    A document can be uploaded as Markdown and as PDF; both are the same document and one indexed file is enough.
    Without this check an empty or partial index would give zeros that look like a bad search.
    """
    statuses: dict[str, list[str]] = {}
    for document in documents:
        statuses.setdefault(Path(document.name).stem, []).append(document.status)
    problems = []
    for doc_id in sorted(expected):
        found = statuses.get(doc_id)
        if found is None:
            problems.append(f"  - {doc_id}: no está en la base de conocimiento")
        elif INDEXED_STATUS not in found:
            problems.append(f"  - {doc_id}: está en estado {', '.join(sorted(set(found)))}")
    if problems:
        raise MeasurementError(
            "El índice no contiene los documentos que el dataset espera, así que no se mide (los ceros no se podrían "
            "distinguir de una mala búsqueda):\n"
            + "\n".join(problems)
            + "\nSúbelos o reindexa, espera al estado INDEXED y vuelve a ejecutar."
        )
