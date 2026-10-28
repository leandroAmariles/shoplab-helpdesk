"""Builds small PDFs for the tests: headings are told apart from the body by their font size."""

import io

from fpdf import FPDF
from pypdf import PdfReader, PdfWriter

BODY_SIZE = 11


def build_pdf(items: list[tuple[str, float]], page_numbers: bool = False) -> bytes:
    """`items` are (text, font size) in reading order; a size above BODY_SIZE makes a heading."""
    pdf = FPDF()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_page()
    for text, size in items:
        pdf.set_font("Helvetica", "B" if size > BODY_SIZE else "", size)
        pdf.multi_cell(0, size * 0.5, text, new_x="LMARGIN", new_y="NEXT")
        pdf.ln(2 if size <= BODY_SIZE else 1)
    if page_numbers:
        pdf.set_y(-12)
        pdf.set_font("Helvetica", "", 9)
        pdf.cell(0, 5, "1", align="C")
    return bytes(pdf.output())


def build_pdf_pages(pages: list[list[tuple[str, float]]]) -> bytes:
    pdf = FPDF()
    for items in pages:
        pdf.add_page()
        for text, size in items:
            pdf.set_font("Helvetica", "B" if size > BODY_SIZE else "", size)
            pdf.multi_cell(0, size * 0.5, text, new_x="LMARGIN", new_y="NEXT")
            pdf.ln(2)
    return bytes(pdf.output())


def build_blank_pdf() -> bytes:
    pdf = FPDF()
    pdf.add_page()
    return bytes(pdf.output())


def build_image_only_pdf() -> bytes:
    """A page that holds a drawing and no text, like a scanned document."""
    pdf = FPDF()
    pdf.add_page()
    pdf.set_fill_color(120, 120, 120)
    pdf.rect(20, 20, 150, 200, style="F")
    return bytes(pdf.output())


def encrypt(content: bytes, password: str = "secret") -> bytes:
    writer = PdfWriter(clone_from=PdfReader(io.BytesIO(content)))
    writer.encrypt(user_password=password, owner_password=password, algorithm="RC4-128")
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()
