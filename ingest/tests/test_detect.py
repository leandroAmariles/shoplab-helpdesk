import pytest

from app.domain.errors import (
    DocumentTooLargeError,
    EmptyDocumentError,
    InvalidDocumentError,
    UnsupportedFormatError,
)
from app.parsing.detect import decode_markdown, detect_format, normalize_name

LIMIT = 1024


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("b.md", "b.md"), ("a/b.md", "b.md"), ("..\\x.md", "x.md"), ("C:\\docs\\Política.PDF", "Política.PDF")],
)
def test_the_name_is_reduced_to_its_base_name(raw, expected):
    assert normalize_name(raw) == expected


def test_a_name_without_base_is_unsupported():
    with pytest.raises(UnsupportedFormatError):
        normalize_name("carpeta/")


def test_the_format_follows_the_extension_whatever_its_case():
    assert detect_format("a.md", b"# t", LIMIT) == "markdown"
    assert detect_format("a.MD", b"# t", LIMIT) == "markdown"
    assert detect_format("a.pdf", b"%PDF-1.7 ...", LIMIT) == "pdf"


@pytest.mark.parametrize("name", ["a.txt", "a.docx", "a", "a.md.exe"])
def test_other_extensions_are_unsupported(name):
    with pytest.raises(UnsupportedFormatError) as error:
        detect_format(name, b"contenido", LIMIT)

    assert error.value.code == "UNSUPPORTED_FORMAT"
    assert error.value.status_code == 415


def test_a_pdf_without_the_pdf_signature_is_invalid_not_unsupported():
    with pytest.raises(InvalidDocumentError):
        detect_format("a.pdf", b"esto no es un pdf", LIMIT)


def test_a_document_over_the_limit_is_too_large():
    with pytest.raises(DocumentTooLargeError) as error:
        detect_format("a.md", b"x" * (LIMIT + 1), LIMIT)

    assert error.value.status_code == 413


def test_a_document_exactly_at_the_limit_is_accepted():
    assert detect_format("a.md", b"x" * LIMIT, LIMIT) == "markdown"


@pytest.mark.parametrize("content", [b"", b"   \n\t  "])
def test_an_empty_document_is_rejected(content):
    with pytest.raises(EmptyDocumentError) as error:
        detect_format("a.md", content, LIMIT)

    assert error.value.status_code == 422


def test_markdown_that_is_not_utf8_is_invalid():
    with pytest.raises(InvalidDocumentError):
        decode_markdown("Política".encode("latin-1"))


def test_markdown_utf8_with_bom_is_decoded_without_it():
    assert decode_markdown(b"\xef\xbb\xbf# T\xc3\xadtulo") == "# Título"
