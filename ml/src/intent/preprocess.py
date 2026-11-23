"""Deterministic text preprocessing transformer for the intent classifier (LAB-118)."""

import re
import unicodedata
from collections.abc import Iterable

from sklearn.base import BaseEstimator, TransformerMixin

from intent.constants import MASK_TOKENS
from intent.errors import EmptyTextError

_CONTROL_OR_WHITESPACE_RE = re.compile(r"[\x00-\x1f\x7f-\x9f\s]+")
_MASK_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(re.escape(placeholder), flags=re.IGNORECASE), f" {token} ")
    for placeholder, token in MASK_TOKENS.items()
)


def preprocess_text(text: str) -> str:
    """Normalize a single customer message while preserving Spanish/Portuguese chars and LAB-117 mask tokens."""
    if not isinstance(text, str):
        raise EmptyTextError("El mensaje de entrada debe ser una cadena de texto (str).")

    normalized = unicodedata.normalize("NFC", text)
    cleaned = _CONTROL_OR_WHITESPACE_RE.sub(" ", normalized).strip()
    if not cleaned:
        raise EmptyTextError("El mensaje de entrada no puede estar vacío ni contener solo espacios.")

    for pattern, replacement in _MASK_PATTERNS:
        cleaned = pattern.sub(replacement, cleaned)

    lowered = cleaned.lower()
    collapsed = _CONTROL_OR_WHITESPACE_RE.sub(" ", lowered).strip()
    if not collapsed:
        raise EmptyTextError("El mensaje quedó vacío tras el preprocesado.")
    return collapsed


class TextPreprocessor(BaseEstimator, TransformerMixin):
    """scikit-learn compatible stateless text preprocessor without lambdas (safe for joblib)."""

    def fit(self, x: Iterable[str], y: object = None) -> "TextPreprocessor":  # noqa: ARG002
        for item in x:
            preprocess_text(item)
        return self

    def transform(self, x: Iterable[str]) -> list[str]:
        return [preprocess_text(item) for item in x]
