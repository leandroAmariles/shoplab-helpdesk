"""Shared fixtures. `ingest/` goes at the END of sys.path so its own `tests/` package never shadows this one."""

import hashlib
import sys
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
INGEST_DIR = str(REPO_ROOT / "ingest")
if INGEST_DIR not in sys.path:
    sys.path.append(INGEST_DIR)


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


@pytest.fixture
def fake_embedder() -> FakeEmbedder:
    return FakeEmbedder()


@pytest.fixture
def real_kb_dir() -> Path:
    return REPO_ROOT / "data" / "kb"


@pytest.fixture
def tmp_kb(tmp_path) -> Path:
    """A minimal corpus: two topics, one document each."""
    (tmp_path / "returns-01-policy.md").write_text(
        "---\ntitle: Devoluciones\ntopic: returns\n---\n# Devoluciones\n\n## Plazos\nPuedes devolver en 7 días.\n",
        encoding="utf-8")
    (tmp_path / "payments-01-methods.md").write_text(
        "---\ntitle: Pagos\ntopic: payments\n---\n# Pagos\n\n## Tarjeta\nAceptamos Visa y Mastercard.\n",
        encoding="utf-8")
    return tmp_path
