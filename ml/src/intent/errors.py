"""Typed errors for the intent classifier package (LAB-118).

Error messages are in Spanish, explain how to solve the issue and never include secrets.
"""


class IntentClassifierError(Exception):
    """Base error for the intent classifier package."""


class EmptyTextError(IntentClassifierError, ValueError):
    """Input message is None, not a string, or empty after stripping whitespace and control characters."""


class DataLeakageError(IntentClassifierError):
    """Dataset partitions overlap by group/text or test examples were passed into training."""


class DataIntegrityError(IntentClassifierError):
    """Dataset content violates schema or missing required partitions."""


class EmbeddingUnavailableError(IntentClassifierError):
    """An embedding vector is missing from the local cache or the embedding gateway failed."""


class EvaluationTargetError(IntentClassifierError):
    """The evaluated model did not reach the minimum required macro-F1 threshold on test."""


class ClassifierConfigurationError(IntentClassifierError):
    """A required CLI file, environment variable or argument is missing or invalid (exit code 2)."""
