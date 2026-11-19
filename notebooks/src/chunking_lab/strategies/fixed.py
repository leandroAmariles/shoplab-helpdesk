"""Fixed-size chunking: character windows with overlap over the whole text, ignoring the document structure."""

from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document
from chunking_lab.strategies import Chunk, Strategy


def split_fixed(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """Windows of `chunk_size` characters, each starting `chunk_size - chunk_overlap` after the previous one.

    The cut is hard: it may fall in the middle of a word, a sentence or a table. Blank windows are dropped, and no
    window is emitted once the previous one already reached the end of the text.
    """
    step = chunk_size - chunk_overlap
    windows: list[str] = []
    start = 0
    while True:
        window = text[start:start + chunk_size]
        if window.strip():
            windows.append(window)
        if start + chunk_size >= len(text):
            break
        start += step
    return windows


def chunk_documents(documents: list[Document], config: ExperimentConfig) -> list[Chunk]:
    chunks: list[Chunk] = []
    for document in documents:
        for position, text in enumerate(split_fixed(document.body, config.chunk_size, config.chunk_overlap)):
            chunks.append(Chunk(strategy=Strategy.FIXED, document=document.id, position=position, section="",
                                text=text))
    return chunks
