import pytest

from app.core.config import Settings
from app.domain.errors import ERROR_CODES, InvalidChunkingConfigError
from app.domain.models import ChunkingConfig


def test_a_valid_chunking_config_is_kept():
    config = ChunkingConfig(chunk_size=100, chunk_overlap=99)

    assert (config.chunk_size, config.chunk_overlap) == (100, 99)


@pytest.mark.parametrize(
    ("size", "overlap"),
    [(99, 0), (1000, -1), (1000, 1000), (1000, 1500)],
)
def test_an_impossible_chunking_config_is_rejected(size, overlap):
    with pytest.raises(InvalidChunkingConfigError) as error:
        ChunkingConfig(size, overlap)

    assert error.value.code == "INVALID_CHUNKING_CONFIG"
    assert error.value.status_code == 422


def test_the_defaults_are_the_documented_ones(monkeypatch):
    for variable in ("INGEST_CHUNK_SIZE", "INGEST_CHUNK_OVERLAP", "INGEST_MAX_DOCUMENT_BYTES"):
        monkeypatch.delenv(variable, raising=False)

    settings = Settings()

    assert (settings.chunk_size, settings.chunk_overlap, settings.max_document_bytes) == (1000, 100, 10 * 1024 * 1024)
    assert settings.chunking() == ChunkingConfig(1000, 100)


def test_the_environment_overrides_the_defaults(monkeypatch):
    monkeypatch.setenv("INGEST_CHUNK_SIZE", "500")
    monkeypatch.setenv("INGEST_CHUNK_OVERLAP", "50")
    monkeypatch.setenv("INGEST_MAX_DOCUMENT_BYTES", "2048")

    settings = Settings()

    assert (settings.chunk_size, settings.chunk_overlap, settings.max_document_bytes) == (500, 50, 2048)


def test_the_service_refuses_to_start_with_an_overlap_not_smaller_than_the_size(monkeypatch):
    monkeypatch.setenv("INGEST_CHUNK_SIZE", "200")
    monkeypatch.setenv("INGEST_CHUNK_OVERLAP", "200")

    with pytest.raises(InvalidChunkingConfigError):
        Settings()


def test_the_error_codes_are_the_published_ones():
    assert set(ERROR_CODES) == {
        "UNSUPPORTED_FORMAT",
        "DOCUMENT_TOO_LARGE",
        "EMPTY_DOCUMENT",
        "NO_EXTRACTABLE_TEXT",
        "INVALID_DOCUMENT",
        "INVALID_CHUNKING_CONFIG",
        "INTERNAL_ERROR",
    }
