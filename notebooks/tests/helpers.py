"""Test doubles shared by the test modules (tests/ is on sys.path; it is not a package)."""

import hashlib

import numpy as np


class FakeEmbedder:
    """Deterministic vectors: from an explicit `vectors` mapping when the text is there, else from its sha256."""

    def __init__(self, vectors: dict[str, list[float]] | None = None, dim: int = 8):
        self.vectors = vectors or {}
        self.dim = dim
        self.calls: list[str] = []

    def embed(self, texts):
        self.calls.extend(texts)
        rows = [self._vector(text) for text in texts]
        return np.array(rows, dtype=np.float32).reshape(len(rows), -1)

    def _vector(self, text: str) -> list[float]:
        if text in self.vectors:
            return self.vectors[text]
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        return [(byte - 127.5) / 127.5 for byte in digest[: self.dim]]
