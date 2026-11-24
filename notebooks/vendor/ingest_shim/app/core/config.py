"""Service configuration, read from INGEST_* environment variables."""

from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.domain.models import ChunkingConfig


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="INGEST_")

    chunk_size: int = 1000
    chunk_overlap: int = 100
    max_document_bytes: int = 10 * 1024 * 1024

    @model_validator(mode="after")
    def _chunking_is_valid(self) -> "Settings":
        # Raises InvalidChunkingConfigError: the service refuses to start with an impossible combination.
        ChunkingConfig(self.chunk_size, self.chunk_overlap)
        return self

    def chunking(self) -> ChunkingConfig:
        return ChunkingConfig(self.chunk_size, self.chunk_overlap)


@lru_cache
def get_settings() -> Settings:
    return Settings()
