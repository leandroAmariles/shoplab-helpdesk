"""Typed errors of the ingest service: each carries the stable code and HTTP status the API answers with."""


class IngestError(Exception):
    """A document or a request that the service rejects, as opposed to an unexpected failure."""

    code = "INTERNAL_ERROR"
    status_code = 500

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class UnsupportedFormatError(IngestError):
    code = "UNSUPPORTED_FORMAT"
    status_code = 415


class DocumentTooLargeError(IngestError):
    code = "DOCUMENT_TOO_LARGE"
    status_code = 413


class EmptyDocumentError(IngestError):
    code = "EMPTY_DOCUMENT"
    status_code = 422


class NoExtractableTextError(IngestError):
    code = "NO_EXTRACTABLE_TEXT"
    status_code = 422


class InvalidDocumentError(IngestError):
    code = "INVALID_DOCUMENT"
    status_code = 422


class InvalidChunkingConfigError(IngestError):
    code = "INVALID_CHUNKING_CONFIG"
    status_code = 422


ERROR_CODES = (
    UnsupportedFormatError.code,
    DocumentTooLargeError.code,
    EmptyDocumentError.code,
    NoExtractableTextError.code,
    InvalidDocumentError.code,
    InvalidChunkingConfigError.code,
    IngestError.code,
)
