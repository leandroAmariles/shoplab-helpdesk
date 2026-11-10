"""Shared fixtures. `ingest/` goes at the END of sys.path so its own `tests/` package never shadows this one."""

import sys
from pathlib import Path

import pytest

from helpers import FakeEmbedder

REPO_ROOT = Path(__file__).resolve().parents[2]
INGEST_DIR = str(REPO_ROOT / "ingest")
if INGEST_DIR not in sys.path:
    sys.path.append(INGEST_DIR)


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
