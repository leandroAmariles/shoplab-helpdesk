"""Retrieval embedding cache, gateway client and scikit-learn EmbeddingTransformer (LAB-118)."""

import hashlib
import json
import os
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.feature_extraction.text import HashingVectorizer

from intent.constants import DEFAULT_SEED
from intent.errors import ClassifierConfigurationError, EmbeddingUnavailableError
from intent.preprocess import preprocess_text
from intent_dataset.dataset import Example

PostFn = Callable[[str, dict[str, str], dict[str, Any]], dict[str, Any]]


def text_sha256(preprocessed_text: str) -> str:
    """Return the SHA-256 hex digest of a preprocessed text."""
    return hashlib.sha256(preprocessed_text.encode("utf-8")).hexdigest()


# Alias for backward and cross-module compatibility
sha256_text = text_sha256


@dataclass(frozen=True)
class EmbeddingCache:
    """In-memory representation of a local dataset embedding cache."""

    model_name: str
    dimension: int
    vectors: dict[str, np.ndarray]

    def __len__(self) -> int:
        return len(self.vectors)

    def save(self, path: Path) -> None:
        save_embeddings_cache(self, path)


def save_embeddings_cache(cache: EmbeddingCache, path: Path) -> None:
    """Persist an EmbeddingCache atomically as a compressed .npz archive."""
    path.parent.mkdir(parents=True, exist_ok=True)
    keys = sorted(cache.vectors.keys())
    if keys:
        matrix = np.vstack([np.asarray(cache.vectors[k], dtype=np.float32) for k in keys]).astype(np.float32)
    else:
        matrix = np.zeros((0, cache.dimension), dtype=np.float32)

    tmp_path = path.with_suffix(".tmp.npz")
    np.savez_compressed(
        tmp_path,
        model_name=np.array(cache.model_name),
        dimension=np.array(cache.dimension, dtype=np.int32),
        keys=np.array(keys),
        matrix=matrix,
    )
    tmp_path.replace(path)


def load_embeddings_cache(path: Path) -> EmbeddingCache:
    """Load an EmbeddingCache from a .npz archive."""
    if not path.exists():
        raise EmbeddingUnavailableError(
            f"No existe la caché de embeddings en {path}. Ejecuta 'uv run python -m intent embed-cache' primero."
        )
    with np.load(path, allow_pickle=False) as archive:
        model_name = str(archive["model_name"].item())
        dimension = int(archive["dimension"].item())
        keys = [str(k) for k in archive["keys"].tolist()]
        matrix = np.asarray(archive["matrix"], dtype=np.float32)

    vectors = {key: matrix[idx] for idx, key in enumerate(keys)}
    return EmbeddingCache(model_name=model_name, dimension=dimension, vectors=vectors)


@dataclass(frozen=True)
class EmbeddingSettings:
    """Connection settings for the OpenAI-compatible embedding endpoint (LAB-21 / LAB-84)."""

    base_url: str = "http://localhost:4000/v1"
    model: str = "embed"
    api_key: str = ""
    timeout_seconds: float = 30.0

    @classmethod
    def from_env(cls) -> "EmbeddingSettings":
        base_url = os.environ.get("SHOPLAB_ML_EMBED_BASE_URL") or os.environ.get(
            "SHOPLAB_ML_LLM_BASE_URL", "http://localhost:4000/v1"
        )
        model = os.environ.get("SHOPLAB_ML_EMBED_MODEL", "embed")
        api_key = os.environ.get("SHOPLAB_ML_EMBED_API_KEY") or os.environ.get("SHOPLAB_ML_LLM_API_KEY", "")
        if not api_key.strip():
            raise ClassifierConfigurationError(
                "Falta la variable de entorno SHOPLAB_ML_LLM_API_KEY (o SHOPLAB_ML_EMBED_API_KEY) "
                "para consultar el gateway de embeddings."
            )
        return cls(base_url=base_url.rstrip("/"), model=model, api_key=api_key.strip())


def _default_http_post(url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30.0) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise EmbeddingUnavailableError(
            f"No se pudo obtener embeddings desde {url}: {type(exc).__name__}."
        ) from exc


class EmbeddingGatewayClient:
    """Minimal injectable client for the OpenAI-compatible /embeddings endpoint."""

    def __init__(self, settings: EmbeddingSettings, *, post: PostFn | None = None) -> None:
        self._settings = settings
        self._post = post or _default_http_post

    def embed_texts(self, texts: Sequence[str], *, batch_size: int = 64) -> np.ndarray:
        if not texts:
            return np.zeros((0, 0), dtype=np.float32)

        url = f"{self._settings.base_url.rstrip('/')}/embeddings"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._settings.api_key}",
        }
        batches: list[np.ndarray] = []
        for start in range(0, len(texts), batch_size):
            chunk = list(texts[start : start + batch_size])
            payload = {"model": self._settings.model, "input": chunk}
            try:
                raw = self._post(url, headers, payload)
            except EmbeddingUnavailableError:
                raise
            except Exception as exc:
                raise EmbeddingUnavailableError(
                    f"Error al consultar el servicio de embeddings en {url}: {type(exc).__name__}."
                ) from exc

            data_items = sorted(raw.get("data", []), key=lambda item: int(item.get("index", 0)))
            if len(data_items) != len(chunk):
                raise EmbeddingUnavailableError(
                    f"Respuesta incompleta del servicio de embeddings: esperados {len(chunk)}, "
                    f"recibidos {len(data_items)}."
                )
            vectors = np.asarray([item["embedding"] for item in data_items], dtype=np.float32)
            batches.append(vectors)
        return np.vstack(batches).astype(np.float32)


def build_deterministic_embeddings_cache(
    examples: Sequence[Example],
    cache_path: Path,
    *,
    dim: int = 256,
    seed: int = DEFAULT_SEED,
    model_name: str = "bge-m3-deterministic-projection",
) -> EmbeddingCache:
    """Build a 100% data-independent, leak-free deterministic dense embedding cache for CPU/CI."""
    word_hasher = HashingVectorizer(
        analyzer="word",
        ngram_range=(1, 2),
        n_features=2048,
        norm=None,
        alternate_sign=True,
        lowercase=False,
        token_pattern=r"(?u)\b\w+\b",
    )
    char_hasher = HashingVectorizer(
        analyzer="char_wb",
        ngram_range=(3, 5),
        n_features=2048,
        norm=None,
        alternate_sign=True,
        lowercase=False,
    )

    unique_texts: dict[str, str] = {}
    for ex in examples:
        prep = preprocess_text(ex.text)
        unique_texts[text_sha256(prep)] = prep

    ordered_keys = sorted(unique_texts.keys())
    ordered_texts = [unique_texts[k] for k in ordered_keys]

    rng = np.random.default_rng(seed)
    proj_word = rng.standard_normal((2048, dim), dtype=np.float32) / np.float32(np.sqrt(dim))
    proj_char = rng.standard_normal((2048, dim), dtype=np.float32) / np.float32(np.sqrt(dim))

    w_mat = word_hasher.transform(ordered_texts).astype(np.float32)
    c_mat = char_hasher.transform(ordered_texts).astype(np.float32)
    dense = (w_mat @ proj_word) + np.float32(0.75) * (c_mat @ proj_char)

    norms = np.linalg.norm(dense, axis=1, keepdims=True)
    norms = np.where(norms > 1e-12, norms, 1.0)
    normalized = (dense / norms).astype(np.float32)

    vectors = {key: normalized[idx] for idx, key in enumerate(ordered_keys)}
    cache = EmbeddingCache(model_name=model_name, dimension=dim, vectors=vectors)
    save_embeddings_cache(cache, cache_path)
    return cache


class EmbeddingTransformer(BaseEstimator, TransformerMixin):
    """scikit-learn transformer mapping preprocessed strings to dense retrieval embeddings."""

    def __init__(
        self,
        cache_path: str = "",
        model_name: str = "embed",
        offline_only: bool = True,
        base_url: str = "http://localhost:4000/v1",
    ) -> None:
        self.cache_path = cache_path
        self.model_name = model_name
        self.offline_only = offline_only
        self.base_url = base_url

    def _load_cache(self) -> EmbeddingCache:
        if not self.cache_path:
            raise EmbeddingUnavailableError("No se configuró cache_path en EmbeddingTransformer.")
        return load_embeddings_cache(Path(self.cache_path))

    def fit(self, x: Iterable[str], y: object = None) -> "EmbeddingTransformer":  # noqa: ARG002
        cache = self._load_cache()
        self.dimension_ = cache.dimension
        self.resolved_model_name_ = cache.model_name
        return self

    def transform(self, x: Iterable[str]) -> np.ndarray:
        cache = self._load_cache()
        texts = list(x)
        if not texts:
            return np.zeros((0, cache.dimension), dtype=np.float32)

        rows: list[np.ndarray] = []
        missing_indices: list[int] = []
        missing_texts: list[str] = []

        for idx, item in enumerate(texts):
            prep = preprocess_text(item)
            digest = text_sha256(prep)
            vec = cache.vectors.get(digest)
            if vec is not None:
                rows.append(np.asarray(vec, dtype=np.float32))
            else:
                if self.offline_only:
                    raise EmbeddingUnavailableError(
                        f"El texto con hash {digest[:12]} no figura en la caché de embeddings "
                        f"({self.cache_path}) y offline_only=True."
                    )
                rows.append(np.zeros(cache.dimension, dtype=np.float32))
                missing_indices.append(idx)
                missing_texts.append(prep)

        if missing_texts:
            settings = EmbeddingSettings.from_env()
            client = EmbeddingGatewayClient(settings)
            fetched = client.embed_texts(missing_texts)
            for pos, vec in zip(missing_indices, fetched, strict=True):
                rows[pos] = np.asarray(vec, dtype=np.float32)

        return np.vstack(rows).astype(np.float32)
