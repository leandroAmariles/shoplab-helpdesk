"""Markdown to PDF conversion with reproducible output, and text extraction to check it."""

import io
import re
import unicodedata
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from fpdf import FPDF
from markdown_it import MarkdownIt
from markdown_it.token import Token
from pypdf import PdfReader

from kb_tools.document import Document

FIXED_CREATION_DATE = datetime(2026, 1, 1, tzinfo=UTC)
TYPOGRAPHIC_REPLACEMENTS = {
    "‘": "'",
    "’": "'",
    "“": '"',
    "”": '"',
    "–": "-",
    "—": "-",
    "…": "...",
    "•": "-",
}
HEADING_SIZES = {1: 18, 2: 14, 3: 12}
BODY_SIZE = 10.5
LINE_HEIGHT = 5.5
LIST_INDENT = 6
PAGE_MARGIN = 20


def normalize_chars(text: str) -> str:
    for source, target in TYPOGRAPHIC_REPLACEMENTS.items():
        text = text.replace(source, target)
    return text


def words(text: str) -> list[str]:
    return re.findall(r"\w+", unicodedata.normalize("NFC", text).lower())


def normalize_text(text: str) -> str:
    """Lowercase words separated by single spaces; ignores layout, bullets and formatting marks."""
    return " ".join(words(text))


def parse(body: str) -> list[Token]:
    return MarkdownIt("commonmark").enable("table").parse(normalize_chars(body))


def flatten(children: list[Token] | None) -> str:
    """Plain text of an inline token: formatting marks are dropped, code and link text are kept."""
    parts: list[str] = []
    for child in children or []:
        if child.type in ("text", "code_inline"):
            parts.append(child.content)
        elif child.type in ("softbreak", "hardbreak"):
            parts.append(" ")
        elif child.type == "image":
            parts.append(child.content)
    return "".join(parts)


def signature(text: str) -> Counter[str]:
    """Multiset of letters and digits: robust to the spaces PDF extraction drops at line wraps and to cell order."""
    return Counter("".join(words(text)))


def markdown_signature(document: Document) -> Counter[str]:
    """Letters and digits the PDF must contain: headings, paragraphs, list items (with numbers) and table cells."""
    collected: list[str] = []
    ordered: list[int] = []
    for token in parse(document.body):
        if token.type == "ordered_list_open":
            ordered.append(0)
        elif token.type == "bullet_list_open":
            ordered.append(-1)
        elif token.type in ("ordered_list_close", "bullet_list_close"):
            ordered.pop()
        elif token.type == "list_item_open" and ordered[-1] >= 0:
            ordered[-1] += 1
            collected.append(str(ordered[-1]))
        elif token.type == "inline":
            collected.append(flatten(token.children))
    return signature(" ".join(collected))


def extract_text(pdf_bytes: bytes) -> str:
    reader = PdfReader(io.BytesIO(pdf_bytes))
    return "\n".join(page.extract_text() or "" for page in reader.pages)


def pdf_signature(pdf_bytes: bytes) -> Counter[str]:
    return signature(extract_text(pdf_bytes))


def write_inline(pdf: FPDF, children: list[Token] | None, size: float, base_style: str = "") -> None:
    bold = italic = False
    for child in children or []:
        if child.type == "strong_open":
            bold = True
        elif child.type == "strong_close":
            bold = False
        elif child.type == "em_open":
            italic = True
        elif child.type == "em_close":
            italic = False
        elif child.type == "code_inline":
            pdf.set_font("Courier", "", size)
            pdf.write(LINE_HEIGHT, child.content)
        elif child.type in ("text", "softbreak", "hardbreak"):
            style = base_style + ("B" if bold else "") + ("I" if italic else "")
            pdf.set_font("Helvetica", "".join(sorted(set(style))), size)
            pdf.write(LINE_HEIGHT, " " if child.type != "text" else child.content)
    pdf.set_font("Helvetica", "", size)


def render_table(pdf: FPDF, rows: list[list[str]]) -> None:
    pdf.set_font("Helvetica", "", 9.5)
    with pdf.table(text_align="LEFT", line_height=5) as table:
        for values in rows:
            row = table.row()
            for value in values:
                row.cell(value)
    pdf.ln(3)
    pdf.set_font("Helvetica", "", BODY_SIZE)


def collect_table(tokens: list[Token], start: int) -> tuple[list[list[str]], int]:
    rows: list[list[str]] = []
    index = start
    while tokens[index].type != "table_close":
        if tokens[index].type == "tr_open":
            rows.append([])
        elif tokens[index].type == "inline":
            rows[-1].append(flatten(tokens[index].children))
        index += 1
    return rows, index


def render_pdf(document: Document) -> bytes:
    pdf = FPDF(format="A4")
    pdf.set_creation_date(FIXED_CREATION_DATE)
    pdf.set_title(document.title)
    pdf.set_author("ShopLab")
    pdf.set_lang("es")
    pdf.set_margins(PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN)
    pdf.set_auto_page_break(True, margin=PAGE_MARGIN)
    pdf.add_page()
    pdf.set_font("Helvetica", "", BODY_SIZE)

    tokens = parse(document.body)
    lists: list[dict[str, int | bool]] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.type == "heading_open":
            level = int(token.tag[1])
            size = HEADING_SIZES.get(level, BODY_SIZE)
            pdf.ln(3 if level > 1 else 0)
            pdf.set_x(pdf.l_margin)
            write_inline(pdf, tokens[index + 1].children, size, "B")
            pdf.ln(LINE_HEIGHT + 2)
            index += 2
        elif token.type == "paragraph_open":
            if lists:
                state = lists[-1]
                marker = f"{state['number']}. " if state["ordered"] else "- "
                pdf.set_left_margin(PAGE_MARGIN + LIST_INDENT * len(lists))
                pdf.set_x(pdf.l_margin)
                pdf.set_font("Helvetica", "", BODY_SIZE)
                pdf.write(LINE_HEIGHT, marker)
            else:
                pdf.set_x(pdf.l_margin)
            write_inline(pdf, tokens[index + 1].children, BODY_SIZE)
            pdf.ln(LINE_HEIGHT + (1 if lists else 2))
            pdf.set_left_margin(PAGE_MARGIN)
            index += 2
        elif token.type in ("bullet_list_open", "ordered_list_open"):
            lists.append({"ordered": token.type == "ordered_list_open", "number": 0})
        elif token.type in ("bullet_list_close", "ordered_list_close"):
            lists.pop()
            if not lists:
                pdf.ln(1)
        elif token.type == "list_item_open":
            lists[-1]["number"] = int(lists[-1]["number"]) + 1
        elif token.type == "table_open":
            rows, index = collect_table(tokens, index)
            render_table(pdf, rows)
        index += 1
    return bytes(pdf.output())


def write_pdf(document: Document, target: Path) -> bool:
    """Writes the PDF of the document; returns False when the file already had exactly that content."""
    data = render_pdf(document)
    if target.exists() and target.read_bytes() == data:
        return False
    target.write_bytes(data)
    return True
