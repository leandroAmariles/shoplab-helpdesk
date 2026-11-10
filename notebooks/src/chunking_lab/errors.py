"""Typed errors of the notebook helpers. Messages are in Spanish: they are read by whoever runs the notebook."""


class ChunkingLabError(Exception):
    """Base of every error raised on purpose by this package."""


class PreflightError(ChunkingLabError):
    """The embedding model is not available; nothing was computed."""


class EmbeddingError(ChunkingLabError):
    """The embedding service answered something unusable."""


class CorpusError(ChunkingLabError):
    """The knowledge base directory has no usable documents."""


class InvalidQuestionsError(ChunkingLabError):
    """The test questions break one or more rules; `failures` lists them all as `(code, question id, message)`."""

    def __init__(self, failures: list[tuple[str, str, str]]):
        self.failures = failures
        lines = [f"{code} {question_id}: {message}" for code, question_id, message in failures]
        super().__init__("Las preguntas de prueba no son válidas:\n" + "\n".join(lines))
