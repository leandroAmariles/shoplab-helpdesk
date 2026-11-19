"""Semantic chunking: cut where the meaning changes between consecutive units (research R6).

1. The document is split into units (sentences, list items, tables; a heading is glued to the unit after it).
2. Every unit is embedded; the cosine distance between consecutive units is computed.
3. A cut goes after unit i when its distance to the next unit is strictly greater than the `breakpoint_percentile`
   of that document's distances.
4. A group bigger than `chunk_size` is split again at its largest internal distance; a group smaller than
   `min_chunk_chars` is merged with the closer neighbour as long as the result fits. No overlap.
"""

import re
from dataclasses import dataclass
from typing import Literal

import numpy as np

from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document
from chunking_lab.embeddings import Embedder
from chunking_lab.strategies import Chunk, Strategy

UnitKind = Literal["sentence", "list_item", "table"]
UNIT_SEPARATOR = "\n"

_BLOCKS = re.compile(r"\n[ \t]*\n")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
_HEADING = re.compile(r"^#{1,6}[ \t]+(.*?)[ \t#]*$")
_LIST_ITEM = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+")


@dataclass(frozen=True)
class Unit:
    text: str
    kind: UnitKind


def semantic_units(body: str, max_chars: int | None = None) -> list[Unit]:
    """The units of a document body; a unit longer than `max_chars` (if given) is cut by characters."""
    units: list[Unit] = []
    pending_heading = ""
    for block in (b.strip() for b in _BLOCKS.split(body)):
        if not block:
            continue
        heading = _HEADING.match(block) if "\n" not in block else None
        if heading:
            pending_heading = "\n".join(part for part in (pending_heading, heading.group(1)) if part)
            continue
        block_units = _units_of_block(block)
        if pending_heading and block_units:
            first = block_units[0]
            block_units[0] = Unit(f"{pending_heading}{UNIT_SEPARATOR}{first.text}", first.kind)
            pending_heading = ""
        units.extend(block_units)
    if pending_heading:
        units.append(Unit(pending_heading, "sentence"))
    return _cut_long_units(units, max_chars) if max_chars else units


def _units_of_block(block: str) -> list[Unit]:
    lines = block.split("\n")
    if all(line.lstrip().startswith("|") for line in lines):
        return [Unit(block, "table")]
    if _LIST_ITEM.match(lines[0]):
        items: list[str] = []
        for line in lines:
            if _LIST_ITEM.match(line) or not items:
                items.append(line)
            else:
                items[-1] += "\n" + line
        return [Unit(item, "list_item") for item in items]
    return [Unit(sentence, "sentence") for sentence in (s.strip() for s in _SENTENCE_END.split(block)) if sentence]


def _cut_long_units(units: list[Unit], max_chars: int) -> list[Unit]:
    cut: list[Unit] = []
    for unit in units:
        if len(unit.text) <= max_chars:
            cut.append(unit)
            continue
        pieces = (unit.text[start:start + max_chars] for start in range(0, len(unit.text), max_chars))
        cut.extend(Unit(piece, unit.kind) for piece in pieces if piece.strip())
    return cut


def adjacent_distances(units: list[Unit], embedder: Embedder) -> np.ndarray:
    """Cosine distance between every pair of consecutive units (length n - 1)."""
    if len(units) < 2:
        return np.zeros(0)
    vectors = np.asarray(embedder.embed([u.text for u in units]), dtype=np.float64)
    norms = np.linalg.norm(vectors, axis=1)
    norms[norms == 0] = 1.0
    unit_vectors = vectors / norms[:, None]
    return 1.0 - np.einsum("ij,ij->i", unit_vectors[:-1], unit_vectors[1:])


def _length(units: list[Unit], start: int, end: int) -> int:
    """Characters of the chunk made of units[start:end]."""
    return sum(len(u.text) for u in units[start:end]) + len(UNIT_SEPARATOR) * (end - start - 1)


def breakpoint_threshold(distances: np.ndarray, percentile: int) -> float:
    """The distance above which a cut goes (0.0 when there is nothing to cut)."""
    return float(np.percentile(distances, percentile)) if len(distances) else 0.0


def _initial_groups(count: int, distances: np.ndarray, percentile: int) -> list[tuple[int, int]]:
    if count == 0:
        return []
    cuts: list[int] = []
    if len(distances):
        threshold = breakpoint_threshold(distances, percentile)
        cuts = [i + 1 for i, d in enumerate(distances) if d > threshold]
    bounds = [0, *cuts, count]
    return list(zip(bounds, bounds[1:], strict=False))


def _fit(units: list[Unit], group: tuple[int, int], distances: np.ndarray, size: int) -> list[tuple[int, int]]:
    start, end = group
    if end - start == 1 or _length(units, start, end) <= size:
        return [group]
    inside = distances[start:end - 1]
    cut = start + int(np.argmax(inside)) + 1
    return _fit(units, (start, cut), distances, size) + _fit(units, (cut, end), distances, size)


def _merge_small(units: list[Unit], groups: list[tuple[int, int]], distances: np.ndarray, size: int,
                 minimum: int) -> list[tuple[int, int]]:
    groups = list(groups)
    while True:
        order = sorted(range(len(groups)), key=lambda i: (_length(units, *groups[i]), i))
        merged = False
        for index in order:
            if _length(units, *groups[index]) >= minimum:
                break
            options = []
            if index > 0 and _length(units, groups[index - 1][0], groups[index][1]) <= size:
                options.append((distances[groups[index][0] - 1], index - 1))
            if index + 1 < len(groups) and _length(units, groups[index][0], groups[index + 1][1]) <= size:
                options.append((distances[groups[index][1] - 1], index))
            if options:
                _, left = min(options)
                groups[left:left + 2] = [(groups[left][0], groups[left + 1][1])]
                merged = True
                break
        if not merged:
            return groups


def chunk_document(document: Document, config: ExperimentConfig, embedder: Embedder) -> list[Chunk]:
    units = semantic_units(document.body, config.chunk_size)
    distances = adjacent_distances(units, embedder)
    groups: list[tuple[int, int]] = []
    for group in _initial_groups(len(units), distances, config.breakpoint_percentile):
        groups.extend(_fit(units, group, distances, config.chunk_size))
    groups = _merge_small(units, groups, distances, config.chunk_size, config.min_chunk_chars)
    return [Chunk(strategy=Strategy.SEMANTIC, document=document.id, position=position, section="",
                  text=UNIT_SEPARATOR.join(u.text for u in units[start:end]))
            for position, (start, end) in enumerate(groups)]


def chunk_documents(documents: list[Document], config: ExperimentConfig, embedder: Embedder) -> list[Chunk]:
    chunks: list[Chunk] = []
    for document in documents:
        chunks.extend(chunk_document(document, config, embedder))
    return chunks
