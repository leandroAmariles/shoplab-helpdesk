"""Unit tests for EmbeddingCache, EmbeddingGatewayClient and EmbeddingTransformer (LAB-118)."""

from pathlib import Path
from typing import Any

import numpy as np
import pytest

from intent.embeddings import (
    EmbeddingCache,
    EmbeddingGatewayClient,
    EmbeddingSettings,
    EmbeddingTransformer,
    build_deterministic_embeddings_cache,
    load_embeddings_cache,
    save_embeddings_cache,
    text_sha256,
)
from intent.errors import ClassifierConfigurationError, EmbeddingUnavailableError
from intent.preprocess import preprocess_text
from intent_dataset.dataset import Example


def test_save_and_load_embeddings_cache_roundtrip(tmp_path: Path) -> None:
    t1 = preprocess_text("Mi pedido [ID] no llegó")
    t2 = preprocess_text("Hola, buenos días")
    vectors = {
        text_sha256(t1): np.array([1.0, 0.0, 0.0], dtype=np.float32),
        text_sha256(t2): np.array([0.0, 1.0, 0.0], dtype=np.float32),
    }
    cache = EmbeddingCache(model_name="bge-m3", dimension=3, vectors=vectors)
    cache_path = tmp_path / "cache.npz"
    save_embeddings_cache(cache, cache_path)

    loaded = load_embeddings_cache(cache_path)
    assert loaded.model_name == "bge-m3"
    assert loaded.dimension == 3
    np.testing.assert_allclose(loaded.vectors[text_sha256(t1)], [1.0, 0.0, 0.0])
    np.testing.assert_allclose(loaded.vectors[text_sha256(t2)], [0.0, 1.0, 0.0])


def test_embedding_transformer_offline_resolves_cached_vectors_and_rejects_missing(
    tmp_path: Path,
    synthetic_examples: list[Example],
) -> None:
    cache_path = tmp_path / "embeddings_cache.npz"
    build_deterministic_embeddings_cache(synthetic_examples, cache_path, dim=32, seed=118)

    transformer = EmbeddingTransformer(cache_path=str(cache_path), offline_only=True)
    preprocessed = [preprocess_text(ex.text) for ex in synthetic_examples[:5]]
    matrix = transformer.fit_transform(preprocessed)

    assert matrix.shape == (5, 32)
    assert matrix.dtype == np.float32
    norms = np.linalg.norm(matrix, axis=1)
    np.testing.assert_allclose(norms, np.ones(5), atol=1e-5)

    with pytest.raises(EmbeddingUnavailableError, match="caché"):
        transformer.transform([preprocess_text("mensaje nunca antes visto en la caché 99999")])


def test_embedding_gateway_client_calls_fake_http_and_never_leaks_api_key() -> None:
    captured: list[tuple[str, dict[str, str], dict[str, Any]]] = []

    def fake_post(url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
        captured.append((url, headers, payload))
        inputs = payload["input"]
        return {
            "data": [
                {"index": i, "embedding": [float(i + 1), 0.5, -0.5, 0.25]}
                for i in range(len(inputs))
            ]
        }

    settings = EmbeddingSettings(
        base_url="http://localhost:4000/v1",
        model="embed",
        api_key="sk-secret-do-not-print",
    )
    client = EmbeddingGatewayClient(settings, post=fake_post)
    out = client.embed_texts(["hola", "mi pedido"])

    assert out.shape == (2, 4)
    assert captured[0][0] == "http://localhost:4000/v1/embeddings"
    assert captured[0][2]["model"] == "embed"

    def failing_post(url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
        raise EmbeddingUnavailableError("Fallo simulado del gateway de embeddings")

    bad_client = EmbeddingGatewayClient(settings, post=failing_post)
    with pytest.raises(EmbeddingUnavailableError) as exc_info:
        bad_client.embed_texts(["hola"])
    assert "sk-secret-do-not-print" not in str(exc_info.value)


def test_embedding_settings_from_env_requires_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SHOPLAB_ML_EMBED_API_KEY", raising=False)
    monkeypatch.delenv("SHOPLAB_ML_LLM_API_KEY", raising=False)
    with pytest.raises(ClassifierConfigurationError, match="SHOPLAB_ML_LLM_API_KEY"):
        EmbeddingSettings.from_env()
