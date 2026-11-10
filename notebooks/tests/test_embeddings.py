import json

import httpx
import numpy as np
import pytest

from chunking_lab.config import ExperimentConfig
from chunking_lab.embeddings import OllamaEmbedder
from chunking_lab.errors import EmbeddingError, PreflightError

TAGS = {"models": [{"name": "bge-m3:latest", "digest": "abc123"}]}


class FakeOllama:
    def __init__(self, tags=TAGS, status=200, dim=3):
        self.tags, self.status, self.dim = tags, status, dim
        self.embed_calls: list[list[str]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            return httpx.Response(self.status, json=self.tags)
        payload = json.loads(request.content)
        self.embed_calls.append(payload["input"])
        if self.status != 200:
            return httpx.Response(self.status, text="boom")
        vectors = [[float(len(text)), float(i), 1.0][: self.dim] for i, text in enumerate(payload["input"])]
        return httpx.Response(200, json={"embeddings": vectors})


def make(handler, tmp_path, **overrides):
    config = ExperimentConfig(**{"embed_batch_size": 2, **overrides})
    client = httpx.Client(transport=httpx.MockTransport(handler), base_url=config.ollama_base_url)
    return OllamaEmbedder(config, client=client, cache_dir=tmp_path / "cache")


def test_batches_keep_order(tmp_path):
    handler = FakeOllama()
    embedder = make(handler, tmp_path, use_cache=False)
    vectors = embedder.embed(["a", "bb", "ccc", "dddd", "eeeee"])
    assert [len(c) for c in handler.embed_calls] == [2, 2, 1]
    assert isinstance(vectors, np.ndarray)
    assert vectors.shape == (5, 3)
    assert vectors[:, 0].tolist() == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_empty_input_returns_empty_matrix(tmp_path):
    embedder = make(FakeOllama(), tmp_path)
    assert embedder.embed([]).shape[0] == 0


def test_cached_texts_are_not_requested_again(tmp_path):
    handler = FakeOllama()
    embedder = make(handler, tmp_path)
    first = embedder.embed(["a", "bb"])
    second = embedder.embed(["bb", "a", "ccc"])
    assert handler.embed_calls == [["a", "bb"], ["ccc"]]
    assert second[0].tolist() == first[1].tolist()
    assert second[1].tolist() == first[0].tolist()


def test_cache_key_includes_the_model(tmp_path):
    handler = FakeOllama()
    make(handler, tmp_path, embed_model="m1").embed(["a"])
    make(handler, tmp_path, embed_model="m2").embed(["a"])
    assert len(handler.embed_calls) == 2


def test_without_cache_nothing_is_read_or_written(tmp_path):
    handler = FakeOllama()
    embedder = make(handler, tmp_path, use_cache=False)
    embedder.embed(["a"])
    embedder.embed(["a"])
    assert len(handler.embed_calls) == 2
    assert not (tmp_path / "cache").exists()


def test_preflight_returns_the_digest_and_matches_latest_tag(tmp_path):
    assert make(FakeOllama(), tmp_path).preflight() == "abc123"


def test_preflight_without_digest_returns_empty_string(tmp_path):
    handler = FakeOllama(tags={"models": [{"name": "bge-m3:latest"}]})
    assert make(handler, tmp_path).preflight() == ""


def test_preflight_unreachable_mentions_docker_compose(tmp_path):
    def boom(request):
        raise httpx.ConnectError("refused")

    with pytest.raises(PreflightError, match=r"docker compose -f infra/docker-compose.yml up -d --wait"):
        make(boom, tmp_path).preflight()


def test_preflight_missing_model_mentions_ollama_pull(tmp_path):
    with pytest.raises(PreflightError, match="ollama pull no-existe"):
        make(FakeOllama(), tmp_path, embed_model="no-existe").preflight()


def test_preflight_unexpected_response(tmp_path):
    with pytest.raises(PreflightError, match="inesperada"):
        make(FakeOllama(tags={"unexpected": 1}), tmp_path).preflight()
    with pytest.raises(PreflightError, match="inesperada"):
        make(FakeOllama(status=500), tmp_path).preflight()


def test_http_error_raises_embedding_error(tmp_path):
    with pytest.raises(EmbeddingError):
        make(FakeOllama(status=500), tmp_path, use_cache=False).embed(["a"])


def test_wrong_vector_count_raises_embedding_error(tmp_path):
    def short(request):
        return httpx.Response(200, json={"embeddings": [[1.0]]})

    with pytest.raises(EmbeddingError, match="vectores"):
        make(short, tmp_path, use_cache=False).embed(["a", "b"])
