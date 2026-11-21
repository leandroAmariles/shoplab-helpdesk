"""Text normalization: the key that decides whether two messages are «the same text» for duplicates and splits."""

import unicodedata


def normalize_text(text: str) -> str:
    """Lower-cases, strips accents and punctuation and collapses whitespace: ``¿Qué tal?`` and ``que tal`` are equal."""
    decomposed = unicodedata.normalize("NFKD", text)
    without_marks = "".join(char for char in decomposed if not unicodedata.combining(char))
    kept = "".join(char if char.isalnum() else " " for char in without_marks.casefold())
    return " ".join(kept.split())


def tokens(text: str) -> frozenset[str]:
    """The set of normalized words of a text (used to compare how similar two messages are)."""
    return frozenset(normalize_text(text).split())
