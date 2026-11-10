"""Cosine search over the chunks of one strategy, across the whole corpus (research R8)."""

from dataclasses import dataclass

import numpy as np

from chunking_lab.embeddings import Embedder
from chunking_lab.metrics import rank_of_first_correct
from chunking_lab.questions import Question
from chunking_lab.strategies import Chunk, Strategy


@dataclass(frozen=True)
class Hit:
    chunk: Chunk
    score: float


@dataclass(frozen=True)
class Retrieval:
    """What one question retrieved with one strategy; `rank` is the 1-based place of the first correct chunk."""

    question_id: str
    strategy: Strategy
    hits: tuple[Hit, ...]
    rank: int | None


class ChunkIndex:
    def __init__(self, chunks: list[Chunk], vectors: np.ndarray):
        self.chunks = chunks
        self.strategy = chunks[0].strategy
        matrix = np.asarray(vectors, dtype=np.float64)
        norms = np.linalg.norm(matrix, axis=1)
        norms[norms == 0] = 1.0
        self._unit = matrix / norms[:, None]

    @classmethod
    def build(cls, chunks: list[Chunk], embedder: Embedder) -> "ChunkIndex":
        return cls(chunks, embedder.embed([c.text for c in chunks]))

    def search(self, query: np.ndarray, top_k: int) -> list[Hit]:
        """The `top_k` chunks closest to `query`; ties break by document, then position, so runs are repeatable."""
        vector = np.asarray(query, dtype=np.float64)
        norm = np.linalg.norm(vector)
        scores = self._unit @ (vector / (norm if norm else 1.0))
        order = sorted(range(len(self.chunks)),
                       key=lambda i: (-scores[i], self.chunks[i].document, self.chunks[i].position))
        return [Hit(self.chunks[i], float(scores[i])) for i in order[:top_k]]


def run_retrieval(index: ChunkIndex, questions: list[Question], embedder: Embedder, top_k: int) -> list[Retrieval]:
    vectors = embedder.embed([q.question for q in questions])
    retrievals = []
    for question, vector in zip(questions, vectors, strict=True):
        hits = tuple(index.search(vector, top_k))
        rank = rank_of_first_correct([h.chunk for h in hits], question)
        retrievals.append(Retrieval(question_id=question.id, strategy=index.strategy, hits=hits, rank=rank))
    return retrievals
