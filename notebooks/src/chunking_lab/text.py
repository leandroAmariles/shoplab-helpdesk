"""Whitespace normalization shared by the question validation and the correctness check."""

import re

_WHITESPACE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """Collapses runs of whitespace (newlines included) into one space and trims the ends; case and accents stay."""
    return _WHITESPACE.sub(" ", text).strip()
