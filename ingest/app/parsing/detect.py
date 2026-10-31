"""What kind of document arrived: by extension, then by content. Nothing is guessed."""

import re

from app.domain.errors import (
    DocumentTooLargeError,
    EmptyDocumentError,
    InvalidDocumentError,
    UnsupportedFormatError,
)
from app.domain.models import DocumentFormat

PDF_SIGNATURE = b"%PDF-"
_EXTENSIONS: dict[str, DocumentFormat] = {".md": "markdown", ".pdf": "pdf"}
_PATH_SEPARATORS = re.compile(r"[\\/]+")


def normalize_name(raw: str | None) -> str:
    """The base name of the file: a path in the name is dropped so the same file is always the same document."""
    base = _PATH_SEPARATORS.split((raw or "").strip())[-1].strip()
    if not base:
        raise UnsupportedFormatError("The document has no file name; only .pdf and .md are supported")
    return base


def detect_format(name: str, content: bytes, max_bytes: int) -> DocumentFormat:
    extension = "." + name.rsplit(".", 1)[-1].lower() if "." in name else ""
    document_format = _EXTENSIONS.get(extension)
    if document_format is None:
        raise UnsupportedFormatError(f"'{name}' is not supported: only .pdf and .md documents are accepted")
    if len(content) > max_bytes:
        raise DocumentTooLargeError(f"The document is larger than the limit of {max_bytes} bytes")
    if not content.strip():
        raise EmptyDocumentError("The document is empty")
    if document_format == "pdf" and not content.startswith(PDF_SIGNATURE):
        raise InvalidDocumentError(f"'{name}' is not a PDF document")
    return document_format


def decode_markdown(content: bytes) -> str:
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise InvalidDocumentError("The Markdown document is not valid UTF-8") from error
