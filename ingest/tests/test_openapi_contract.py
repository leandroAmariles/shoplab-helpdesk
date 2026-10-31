"""ingest/openapi.json is the contract of POST /ingest (specs/LAB-20, FR-016): the service must match it, and the
retrieval module checks its client against the same file."""

import json
from pathlib import Path

from app.domain.errors import ERROR_CODES
from app.main import create_app
from app.openapi_export import CONTRACT, render

REGENERATE = "uv run python -m app.openapi_export"


def contract() -> dict:
    return json.loads(Path(CONTRACT).read_text(encoding="utf-8"))


def test_the_versioned_contract_matches_the_service():
    assert Path(CONTRACT).read_text(encoding="utf-8") == render(), f"ingest/openapi.json is stale: run `{REGENERATE}`"


def test_ingest_is_a_multipart_post_with_the_document_and_the_optional_chunking():
    operation = contract()["paths"]["/ingest"]["post"]
    content = operation["requestBody"]["content"]
    assert list(content) == ["multipart/form-data"]
    body = contract()["components"]["schemas"][content["multipart/form-data"]["schema"]["$ref"].rsplit("/", 1)[-1]]

    assert body["required"] == ["file"]
    assert set(body["properties"]) == {"file", "chunk_size", "chunk_overlap"}
    assert body["properties"]["file"]["contentMediaType"] == "application/octet-stream"


def test_ingest_declares_the_success_and_every_error_answer():
    responses = contract()["paths"]["/ingest"]["post"]["responses"]

    assert set(responses) == {"200", "413", "415", "422", "500"}
    for status in ("413", "415", "422", "500"):
        assert responses[status]["content"]["application/json"]["schema"]["$ref"].endswith("/ErrorResponse")
    assert responses["200"]["content"]["application/json"]["schema"]["$ref"].endswith("/IngestResponse")


def test_the_chunks_carry_document_section_and_position():
    schemas = contract()["components"]["schemas"]

    assert set(schemas["IngestResponse"]["required"]) == {"document", "chunks"}
    assert set(schemas["DocumentInfo"]["required"]) == {"name", "title", "format"}
    assert set(schemas["Chunk"]["required"]) == {"text", "metadata"}
    assert set(schemas["ChunkMetadata"]["required"]) == {"document", "section", "position"}
    assert schemas["ChunkMetadata"]["properties"]["position"]["minimum"] == 0


def test_the_error_codes_of_the_contract_are_the_ones_of_the_service():
    assert set(contract()["components"]["schemas"]["ErrorResponse"]["properties"]["code"]["enum"]) == set(ERROR_CODES)


def test_the_health_probe_is_published_and_the_metrics_are_not():
    paths = contract()["paths"]

    assert "/health" in paths
    assert "/metrics" not in paths


def test_the_example_response_is_valid_for_the_schema():
    example = contract()["components"]["schemas"]["IngestResponse"]["examples"][0]
    schema_chunks = example["chunks"]

    assert example["document"]["format"] in {"markdown", "pdf"}
    assert [chunk["metadata"]["position"] for chunk in schema_chunks] == list(range(len(schema_chunks)))


def test_the_application_builds_the_same_schema_twice():
    assert create_app().openapi() == create_app().openapi()
