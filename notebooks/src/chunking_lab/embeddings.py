"""Embeddings with the same model as the system (bge-m3 through Ollama), with an optional on-disk cache."""

import hashlib
from pathlib import Path
from typing import Protocol

import httpx
import numpy as np

from chunking_lab.config import ExperimentConfig, repo_root
from chunking_lab.errors import EmbeddingError, PreflightError

DEFAULT_CACHE_DIR = repo_root() / "notebooks" / ".cache" / "embeddings"
COMPOSE_HINT = "docker compose -f infra/docker-compose.yml up -d --wait"


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> np.ndarray:
        """One row per text, in the same order."""


class OllamaEmbedder:
    def __init__(self, config: ExperimentConfig, client: httpx.Client | None = None,
                 cache_dir: Path | None = None):
        self._config = config
        self._client = client or httpx.Client(base_url=config.ollama_base_url, timeout=300.0)
        self._cache_dir = Path(cache_dir) if cache_dir else DEFAULT_CACHE_DIR

    def preflight(self) -> str:
        """Checks Ollama answers and the model is downloaded; returns the model digest ('' if not reported)."""
        model = self._config.embed_model
        try:
            response = self._client.get("/api/tags")
        except httpx.HTTPError as error:
            raise PreflightError(
                f"No se puede conectar con Ollama en {self._config.ollama_base_url} ({error}). "
                f"Arráncalo con: {COMPOSE_HINT}") from error
        models = _models_of(response)
        if models is None:
            raise PreflightError(
                f"Respuesta inesperada de Ollama en {self._config.ollama_base_url}/api/tags "
                f"(HTTP {response.status_code}). Comprueba que es un servidor Ollama.")
        for entry in models:
            if _same_model(entry.get("name", ""), model):
                return str(entry.get("digest", ""))
        raise PreflightError(f"El modelo de embeddings '{model}' no está descargado en Ollama. "
                             f"Descárgalo con: ollama pull {model}")

    def embed(self, texts: list[str]) -> np.ndarray:
        if not texts:
            return np.zeros((0, 0), dtype=np.float32)
        cached: dict[int, np.ndarray] = {}
        missing: list[int] = []
        for index, text in enumerate(texts):
            vector = self._read_cache(text)
            if vector is None:
                missing.append(index)
            else:
                cached[index] = vector
        batch_size = self._config.embed_batch_size
        for start in range(0, len(missing), batch_size):
            indices = missing[start:start + batch_size]
            vectors = self._request([texts[i] for i in indices])
            for index, vector in zip(indices, vectors, strict=True):
                cached[index] = vector
                self._write_cache(texts[index], vector)
        return np.vstack([cached[i] for i in range(len(texts))])

    def _request(self, batch: list[str]) -> list[np.ndarray]:
        try:
            response = self._client.post("/api/embed", json={"model": self._config.embed_model, "input": batch})
        except httpx.HTTPError as error:
            raise EmbeddingError(f"Fallo al llamar a Ollama /api/embed: {error}") from error
        if response.status_code != 200:
            raise EmbeddingError(f"Ollama /api/embed respondió HTTP {response.status_code}: {response.text[:200]}")
        vectors = response.json().get("embeddings")
        if not isinstance(vectors, list) or len(vectors) != len(batch):
            raise EmbeddingError(
                f"Ollama devolvió {len(vectors) if isinstance(vectors, list) else 0} vectores para "
                f"{len(batch)} textos")
        return [np.asarray(vector, dtype=np.float32) for vector in vectors]

    def _cache_file(self, text: str) -> Path:
        key = hashlib.sha256(f"{self._config.embed_model}\n{text}".encode()).hexdigest()
        return self._cache_dir / f"{key}.npy"

    def _read_cache(self, text: str) -> np.ndarray | None:
        if not self._config.use_cache:
            return None
        path = self._cache_file(text)
        return np.load(path) if path.is_file() else None

    def _write_cache(self, text: str, vector: np.ndarray) -> None:
        if not self._config.use_cache:
            return
        self._cache_dir.mkdir(parents=True, exist_ok=True)
        np.save(self._cache_file(text), vector)


def _models_of(response: httpx.Response) -> list[dict] | None:
    if response.status_code != 200:
        return None
    try:
        models = response.json().get("models")
    except (ValueError, AttributeError):
        return None
    return models if isinstance(models, list) else None


def _same_model(listed: str, wanted: str) -> bool:
    if listed == wanted:
        return True
    return ":" not in wanted and listed == f"{wanted}:latest"
