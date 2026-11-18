"""What a valid dataset looks like, for the command and for the people who review it."""

from dataclasses import dataclass

from shoplab_eval.constants import CATEGORIES, CONTENT_WORD_MIN_LETTERS, GoldenRules
from shoplab_eval.corpus import CorpusDocument, normalize_text
from shoplab_eval.golden import GoldenCase

_KINDS = ("answered", "unanswered_topic", "off_topic")
_LOW_OVERLAP_BELOW = 0.5


@dataclass(frozen=True)
class GoldenSummary:
    total: int
    by_category: dict[str, int]
    by_kind: dict[str, int]
    multi_document: int
    uncovered_documents: tuple[str, ...]
    low_overlap_share: float | None


def summarize(cases: list[GoldenCase], corpus: dict[str, CorpusDocument]) -> GoldenSummary:
    expected = {doc for case in cases for doc in case.expected_documents}
    return GoldenSummary(
        total=len(cases),
        by_category={category: sum(1 for case in cases if case.category == category) for category in CATEGORIES},
        by_kind={kind: sum(1 for case in cases if case.kind == kind) for kind in _KINDS},
        multi_document=sum(1 for case in cases if case.is_multi_document),
        uncovered_documents=tuple(sorted(set(corpus) - expected)),
        low_overlap_share=_low_overlap_share(cases, corpus),
    )


def _low_overlap_share(cases: list[GoldenCase], corpus: dict[str, CorpusDocument]) -> float | None:
    """Share of the answered questions whose content words mostly do not appear in their expected documents.

    Informative only: it helps to check FR-013 by hand and never makes the validation fail.
    """
    measured = 0
    low = 0
    for case in cases:
        words = {word for word in normalize_text(case.question).split() if len(word) >= CONTENT_WORD_MIN_LETTERS}
        if not case.answerable or not words:
            continue
        document_words: set[str] = set()
        for doc in case.expected_documents:
            if doc in corpus:
                document_words.update(corpus[doc].text.split())
        measured += 1
        if len(words & document_words) / len(words) < _LOW_OVERLAP_BELOW:
            low += 1
    return low / measured if measured else None


def format_summary(summary: GoldenSummary) -> str:
    """One line per fact, always in the same order."""
    target = f"{GoldenRules().min_low_overlap_share * 100:.0f} %"
    lines = [
        f"Casos: {summary.total}",
        "Por categoría: " + ", ".join(f"{name}={count}" for name, count in summary.by_category.items()),
        "Por tipo: " + ", ".join(f"{name}={count}" for name, count in summary.by_kind.items()),
        f"Casos de dos documentos: {summary.multi_document}",
        "Documentos del corpus sin caso: " + (", ".join(summary.uncovered_documents) or "ninguno"),
    ]
    if summary.low_overlap_share is None:
        lines.append(f"Solapamiento léxico bajo: sin preguntas con respuesta que medir (objetivo FR-013: {target})")
    else:
        lines.append(
            f"Solapamiento léxico bajo: {summary.low_overlap_share * 100:.1f} % de las preguntas con respuesta "
            f"(objetivo FR-013: al menos {target}; informativo)"
        )
    return "\n".join(lines)
