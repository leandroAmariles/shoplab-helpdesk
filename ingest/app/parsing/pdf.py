"""PDF parsing: the text in reading order, with headings recognised by their font size.

The body size is the most frequent one; a short line set at least `HEADING_SIZE_DELTA` points above it is a heading and
the sizes of the headings, from largest to smallest, give their levels. A PDF whose text is all one size has no
headings, and is chunked by size alone."""

import io
import re
from collections import Counter
from dataclasses import dataclass

import pdfplumber

from app.domain.errors import IngestError, InvalidDocumentError, NoExtractableTextError
from app.domain.models import ParsedDocument, ParsedSection

HEADING_SIZE_DELTA = 1.0
HEADING_MAX_CHARACTERS = 120
PARAGRAPH_GAP_FACTOR = 0.75
_PAGE_NUMBER = re.compile(r"^\s*(?:(?:page|página|pág\.?)\s*)?\d{1,4}(?:\s*(?:/|of|de)\s*\d{1,4})?\s*$", re.IGNORECASE)


@dataclass(frozen=True)
class _Line:
    text: str
    size: float
    top: float
    bottom: float
    page: int


def parse_pdf(content: bytes) -> ParsedDocument:
    try:
        lines = _read_lines(content)
    except IngestError:
        raise
    except Exception as error:
        raise InvalidDocumentError("The PDF is corrupt, protected with a password or cannot be read") from error
    if not lines:
        raise NoExtractableTextError("The PDF has no extractable text; scanned documents are not supported")
    return _sections(lines)


def _read_lines(content: bytes) -> list[_Line]:
    lines: list[_Line] = []
    with pdfplumber.open(io.BytesIO(content)) as pdf:
        for page in pdf.pages:
            for raw in page.extract_text_lines(return_chars=True, strip=True):
                text = " ".join(raw["text"].split())
                sizes = Counter(round(char["size"] * 2) / 2 for char in raw["chars"] if char["text"].strip())
                if not text or not sizes or _PAGE_NUMBER.match(text):
                    continue
                lines.append(_Line(text, sizes.most_common(1)[0][0], raw["top"], raw["bottom"], page.page_number))
    return lines


def _sections(lines: list[_Line]) -> ParsedDocument:
    body_size = _body_size(lines)
    heading_sizes = sorted({line.size for line in lines if _is_heading(line, body_size)}, reverse=True)
    level_of = {size: level for level, size in enumerate(heading_sizes, start=1)}

    sections: list[ParsedSection] = []
    stack: list[tuple[int, str]] = []
    path: tuple[str, ...] = ()
    blocks: list[str] = []
    paragraph: list[str] = []
    title: str | None = None
    previous: _Line | None = None
    pending_heading: tuple[float, list[str]] | None = None

    def close_paragraph() -> None:
        if paragraph:
            blocks.append(" ".join(paragraph))
            paragraph.clear()

    def open_section(size: float, words: list[str]) -> None:
        nonlocal path, blocks, title
        close_paragraph()
        if blocks or path:
            sections.append(ParsedSection(path=path, blocks=tuple(blocks)))
        level = level_of[size]
        heading = " ".join(words)
        while stack and stack[-1][0] >= level:
            stack.pop()
        stack.append((level, heading))
        path = tuple(name for _, name in stack)
        blocks = []
        if level == 1 and title is None:
            title = heading

    for line in lines:
        if _is_heading(line, body_size):
            if pending_heading and pending_heading[0] == line.size and previous is not None \
                    and previous.page == line.page and _is_heading(previous, body_size):
                pending_heading[1].append(line.text)
            else:
                if pending_heading:
                    open_section(*pending_heading)
                pending_heading = (line.size, [line.text])
        else:
            if pending_heading:
                open_section(*pending_heading)
                pending_heading = None
            if previous is not None and _starts_paragraph(previous, line):
                close_paragraph()
            paragraph.append(line.text)
        previous = line
    if pending_heading:
        open_section(*pending_heading)
    close_paragraph()
    if blocks or path:
        sections.append(ParsedSection(path=path, blocks=tuple(blocks)))
    return ParsedDocument(title=title, sections=tuple(sections))


def _body_size(lines: list[_Line]) -> float:
    weight: Counter[float] = Counter()
    for line in lines:
        weight[line.size] += len(line.text)
    return weight.most_common(1)[0][0]


def _is_heading(line: _Line, body_size: float) -> bool:
    return line.size >= body_size + HEADING_SIZE_DELTA and len(line.text) <= HEADING_MAX_CHARACTERS


def _starts_paragraph(previous: _Line, line: _Line) -> bool:
    if previous.page != line.page:
        return True
    return line.top - previous.bottom > PARAGRAPH_GAP_FACTOR * line.size
