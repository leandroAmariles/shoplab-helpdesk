"""The three chunking strategies and the `Chunk` they all produce."""

from dataclasses import dataclass
from enum import StrEnum


class Strategy(StrEnum):
    """In the order the notebook always shows them."""

    FIXED = "fixed"
    HEADINGS = "headings"
    SEMANTIC = "semantic"


@dataclass(frozen=True)
class Chunk:
    """`position` is consecutive from 0 within the document; only the headings strategy fills `section`."""

    strategy: Strategy
    document: str
    position: int
    section: str
    text: str

    def __post_init__(self) -> None:
        if not self.text.strip():
            raise ValueError("a chunk must have text")


def build_chunks(strategy: Strategy, documents, config, embedder=None) -> list[Chunk]:
    """Chunks of every document with `strategy`, in document order."""
    from chunking_lab.strategies import fixed, headings, semantic

    if strategy is Strategy.FIXED:
        return fixed.chunk_documents(documents, config)
    if strategy is Strategy.HEADINGS:
        return headings.chunk_documents(documents, config)
    if strategy is Strategy.SEMANTIC:
        if embedder is None:
            raise ValueError("the semantic strategy needs an embedder")
        return semantic.chunk_documents(documents, config, embedder)
    raise ValueError(f"unknown strategy {strategy}")
