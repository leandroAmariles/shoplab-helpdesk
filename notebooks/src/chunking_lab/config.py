"""Every parameter of the experiment in one place (FR-016)."""

import os
from dataclasses import dataclass, field
from pathlib import Path

MIN_CHUNK_SIZE = 100


def repo_root() -> Path:
    """The repository root, found from this file so it does not depend on the working directory."""
    return Path(__file__).resolve().parents[3]


def _default_model() -> str:
    return os.environ.get("OLLAMA_EMBED_MODEL", "bge-m3")


def _default_url() -> str:
    return os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")


@dataclass(frozen=True)
class ExperimentConfig:
    """Parameters shared by the three strategies and the measurement.

    `chunk_size` caps a whole chunk in every strategy; `chunk_overlap` is used by fixed-size and headings (as in
    production) and not by the semantic one; `top_k` is the SHOPLAB_CHAT_RAG_TOP_K of the chat.
    """

    chunk_size: int = 1000
    chunk_overlap: int = 100
    top_k: int = 4
    breakpoint_percentile: int = 80
    min_chunk_chars: int = 200
    embed_model: str = field(default_factory=_default_model)
    ollama_base_url: str = field(default_factory=_default_url)
    embed_batch_size: int = 32
    use_cache: bool = True
    kb_dir: Path = field(default_factory=lambda: repo_root() / "data" / "kb")
    questions_path: Path = field(default_factory=lambda: repo_root() / "notebooks" / "data" / "questions.toml")
    results_path: Path = field(
        default_factory=lambda: repo_root() / "notebooks" / "results" / "chunking-results.json")

    def __post_init__(self) -> None:
        if self.chunk_size < MIN_CHUNK_SIZE:
            raise ValueError(f"chunk_size must be at least {MIN_CHUNK_SIZE}, got {self.chunk_size}")
        if self.chunk_overlap < 0:
            raise ValueError(f"chunk_overlap must not be negative, got {self.chunk_overlap}")
        if self.chunk_overlap >= self.chunk_size:
            raise ValueError(
                f"chunk_overlap ({self.chunk_overlap}) must be smaller than chunk_size ({self.chunk_size})")
        if self.top_k < 1:
            raise ValueError(f"top_k must be at least 1, got {self.top_k}")
        if not 1 <= self.breakpoint_percentile <= 99:
            raise ValueError(f"breakpoint_percentile must be between 1 and 99, got {self.breakpoint_percentile}")
        if not 0 <= self.min_chunk_chars < self.chunk_size:
            raise ValueError(
                f"min_chunk_chars must be between 0 and chunk_size ({self.chunk_size}), got {self.min_chunk_chars}")
        if self.embed_batch_size < 1:
            raise ValueError(f"embed_batch_size must be at least 1, got {self.embed_batch_size}")
        if not self.embed_model.strip():
            raise ValueError("embed_model must not be empty")
        if not self.ollama_base_url.startswith(("http://", "https://")):
            raise ValueError(f"ollama_base_url must start with http:// or https://, got {self.ollama_base_url}")
