import pytest

from app.domain.errors import InvalidDocumentError, NoExtractableTextError
from app.parsing.pdf import parse_pdf
from tests.pdf_factory import (
    build_blank_pdf,
    build_image_only_pdf,
    build_pdf,
    build_pdf_pages,
    encrypt,
)

PARAGRAPH = "Este párrafo explica las condiciones del servicio para los clientes de la tienda."


def paths(parsed):
    return [section.path for section in parsed.sections]


def test_headings_of_different_sizes_build_the_section_path():
    content = build_pdf([
        ("Política de devoluciones", 20),
        (PARAGRAPH, 11),
        ("Plazos", 15),
        ("Tienes treinta días para devolver el producto.", 11),
    ])

    parsed = parse_pdf(content)

    assert paths(parsed) == [("Política de devoluciones",), ("Política de devoluciones", "Plazos")]
    assert parsed.sections[0].blocks == (PARAGRAPH,)
    assert parsed.sections[1].blocks == ("Tienes treinta días para devolver el producto.",)


def test_the_title_is_the_heading_of_the_largest_size():
    content = build_pdf([("Garantía legal", 20), (PARAGRAPH, 11), ("Alcance", 15), (PARAGRAPH, 11)])

    assert parse_pdf(content).title == "Garantía legal"


def test_text_of_a_single_size_is_one_section_without_path():
    content = build_pdf([(PARAGRAPH, 11), ("Otro párrafo distinto del primero, de la misma letra.", 11)])

    parsed = parse_pdf(content)

    assert paths(parsed) == [()]
    assert parsed.title is None
    assert "Otro párrafo distinto" in " ".join(parsed.sections[0].blocks)


def test_a_heading_that_wraps_in_two_lines_is_one_heading():
    long_title = "Condiciones generales de devolución de productos comprados en la tienda en línea"
    content = build_pdf([(long_title, 20), (PARAGRAPH, 11)])

    parsed = parse_pdf(content)

    assert len(parsed.sections) == 1
    assert parsed.sections[0].path == (long_title,)


def test_a_line_that_is_only_a_page_number_is_dropped():
    content = build_pdf([("Título", 20), (PARAGRAPH, 11)], page_numbers=True)

    blocks = " ".join(block for section in parse_pdf(content).sections for block in section.blocks)

    assert PARAGRAPH in blocks
    assert blocks.strip().split()[-1] != "1"


def test_the_reading_order_across_pages_is_kept():
    content = build_pdf_pages([
        [("Primera", 15), ("Texto de la primera página con contenido suficiente para ser cuerpo.", 11)],
        [("Segunda", 15), ("Texto de la segunda página con contenido suficiente para ser cuerpo.", 11)],
    ])

    assert paths(parse_pdf(content)) == [("Primera",), ("Segunda",)]


def test_accents_and_enyes_are_extracted():
    content = build_pdf([("Garantía", 15), ("La reparación del año cubre a los niños.", 11)])

    assert "reparación del año cubre a los niños" in parse_pdf(content).sections[0].blocks[0]


@pytest.mark.parametrize("content", [build_blank_pdf(), build_image_only_pdf()])
def test_a_pdf_without_text_has_nothing_to_extract(content):
    with pytest.raises(NoExtractableTextError) as error:
        parse_pdf(content)

    assert error.value.status_code == 422


def test_corrupt_bytes_with_the_pdf_signature_are_invalid():
    with pytest.raises(InvalidDocumentError):
        parse_pdf(b"%PDF-1.7\nesto esta muy roto \x00\x01\x02 sin estructura")


def test_a_password_protected_pdf_is_invalid():
    protected = encrypt(build_pdf([("Título", 20), (PARAGRAPH, 11)]))

    with pytest.raises(InvalidDocumentError):
        parse_pdf(protected)
