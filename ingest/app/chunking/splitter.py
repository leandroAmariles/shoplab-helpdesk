"""Chunking by section: a chunk never mixes two sections, never exceeds `chunk_size` (overlap included), and every
chunk of a section but the first starts with the last `chunk_overlap` characters of the previous one."""

import re

from app.domain.models import SECTION_SEPARATOR, Chunk, ChunkingConfig, ParsedSection

BLOCK_SEPARATOR = "\n\n"
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
# Natural limits tried in order when a piece is too long: lines, sentences, words; characters as the last resort.
_LEVELS = ((re.compile(r"\n"), "\n"), (_SENTENCE_END, " "), (re.compile(r" +"), " "))


def split_sections(sections: list[ParsedSection] | tuple[ParsedSection, ...], config: ChunkingConfig,
                   document: str) -> list[Chunk]:
    """Chunks of every section in document order, with consecutive positions from 0."""
    chunks: list[Chunk] = []
    for section in sections:
        for text in _split_section(section, config):
            chunks.append(Chunk(text=text, document=document, section=SECTION_SEPARATOR.join(section.path),
                                position=len(chunks)))
    return chunks


def _split_section(section: ParsedSection, config: ChunkingConfig) -> list[str]:
    atoms = _atoms(section, config.chunk_size - config.chunk_overlap)
    if not any(block.strip() for block in section.blocks):
        return []
    texts: list[str] = []
    body = ""
    for text, separator in atoms:
        budget = config.chunk_size if not texts else config.chunk_size - config.chunk_overlap
        candidate = text if not body else body + separator + text
        if len(candidate) <= budget:
            body = candidate
            continue
        texts.append(_with_overlap(texts, body, config.chunk_overlap))
        body = text
    if body:
        texts.append(_with_overlap(texts, body, config.chunk_overlap))
    return texts


def _with_overlap(previous: list[str], body: str, overlap: int) -> str:
    if previous and overlap:
        return previous[-1][-overlap:] + body
    return body


def _atoms(section: ParsedSection, limit: int) -> list[tuple[str, str]]:
    """The heading and the blocks cut into pieces of at most `limit` characters, each with the separator that goes
    before it when packed together."""
    atoms: list[tuple[str, str]] = []
    heading = section.path[-1] if section.path else ""
    for piece_index, piece in enumerate(_fit(heading.strip(), limit) if heading.strip() else []):
        atoms.append((piece, "" if piece_index == 0 else " "))
    for block in section.blocks:
        text = block.strip()
        if not text:
            continue
        for piece_index, piece in enumerate(_fit(text, limit)):
            atoms.append((piece, BLOCK_SEPARATOR if piece_index == 0 else " "))
    if atoms:
        atoms[0] = (atoms[0][0], "")
    return atoms


def _fit(text: str, limit: int, level: int = 0) -> list[str]:
    """`text` cut into pieces of at most `limit` characters at the most natural limits available."""
    if len(text) <= limit:
        return [text]
    if level == len(_LEVELS):
        pieces = [text[start:start + limit] for start in range(0, len(text), limit)]
        return [piece for piece in pieces if piece.strip()]
    pattern, joiner = _LEVELS[level]
    parts = [part.strip() for part in pattern.split(text) if part.strip()]
    if len(parts) <= 1:
        return _fit(text, limit, level + 1)
    merged: list[str] = []
    current = ""
    for part in parts:
        for piece in _fit(part, limit, level + 1):
            candidate = piece if not current else current + joiner + piece
            if len(candidate) <= limit:
                current = candidate
            else:
                merged.append(current)
                current = piece
    if current:
        merged.append(current)
    return merged
