"""When a retrieved chunk is right, and the aggregate metrics (research R8)."""

from collections.abc import Sequence

from chunking_lab.text import normalize


def is_correct(chunk, question) -> bool:
    """Right only if it comes from the expected document AND contains the reference passage."""
    return chunk.document == question.document and normalize(question.passage) in normalize(chunk.text)


def rank_of_first_correct(chunks: Sequence, question) -> int | None:
    """1-based place of the first correct chunk among the retrieved ones, or None when there is none."""
    for place, chunk in enumerate(chunks, start=1):
        if is_correct(chunk, question):
            return place
    return None


def hit_rate(ranks: Sequence[int | None]) -> float:
    return sum(1 for r in ranks if r is not None) / len(ranks) if ranks else 0.0


def mean_reciprocal_rank(ranks: Sequence[int | None]) -> float:
    return sum(1 / r for r in ranks if r is not None) / len(ranks) if ranks else 0.0
