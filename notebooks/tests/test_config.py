from pathlib import Path

import pytest

from chunking_lab.config import ExperimentConfig, repo_root


def test_defaults_match_the_data_model(monkeypatch):
    monkeypatch.delenv("OLLAMA_EMBED_MODEL", raising=False)
    monkeypatch.delenv("OLLAMA_BASE_URL", raising=False)
    config = ExperimentConfig()
    assert (config.chunk_size, config.chunk_overlap, config.top_k) == (1000, 100, 4)
    assert (config.breakpoint_percentile, config.min_chunk_chars) == (80, 200)
    assert config.embed_batch_size == 32
    assert config.use_cache is True
    assert config.embed_model == "bge-m3"
    assert config.ollama_base_url == "http://localhost:11434"


def test_environment_overrides_model_and_url(monkeypatch):
    monkeypatch.setenv("OLLAMA_EMBED_MODEL", "other-model")
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://ollama:11434")
    config = ExperimentConfig()
    assert config.embed_model == "other-model"
    assert config.ollama_base_url == "http://ollama:11434"


@pytest.mark.parametrize(("field", "value"), [
    ("chunk_size", 99),
    ("chunk_overlap", -1),
    ("chunk_overlap", 1000),
    ("top_k", 0),
    ("breakpoint_percentile", 0),
    ("breakpoint_percentile", 100),
    ("min_chunk_chars", -1),
    ("min_chunk_chars", 1000),
    ("embed_batch_size", 0),
    ("embed_model", ""),
    ("ollama_base_url", "localhost:11434"),
])
def test_invalid_values_name_the_field(field, value):
    with pytest.raises(ValueError, match=field):
        ExperimentConfig(**{field: value})


def test_paths_are_resolved_from_the_repository_root_not_the_working_directory(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    config = ExperimentConfig()
    root = repo_root()
    assert (root / "data" / "kb").is_dir()
    assert config.kb_dir == root / "data" / "kb"
    assert config.questions_path == root / "notebooks" / "data" / "questions.toml"
    assert config.results_path == root / "notebooks" / "results" / "chunking-results.json"
    assert isinstance(config.kb_dir, Path)
