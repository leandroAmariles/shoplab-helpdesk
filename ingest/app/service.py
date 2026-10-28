"""The whole pipeline in one function: bytes in, chunks out. No I/O, no state."""

from pathlib import PurePosixPath

from app.chunking.splitter import split_sections
from app.domain.errors import EmptyDocumentError, NoExtractableTextError
from app.domain.models import ChunkingConfig, IngestResult
from app.parsing.detect import decode_markdown, detect_format, normalize_name
from app.parsing.markdown import parse_markdown
from app.parsing.pdf import parse_pdf


def ingest_document(raw_name: str | None, content: bytes, config: ChunkingConfig,
                    max_document_bytes: int) -> IngestResult:
    name = normalize_name(raw_name)
    document_format = detect_format(name, content, max_document_bytes)
    parsed = parse_markdown(decode_markdown(content)) if document_format == "markdown" else parse_pdf(content)
    chunks = split_sections(parsed.sections, config, name)
    if not chunks:
        if document_format == "pdf":
            raise NoExtractableTextError("The PDF has no extractable text; scanned documents are not supported")
        raise EmptyDocumentError("The document has no text under its headings")
    return IngestResult(name=name, title=parsed.title or PurePosixPath(name).stem, format=document_format,
                        chunks=tuple(chunks))
