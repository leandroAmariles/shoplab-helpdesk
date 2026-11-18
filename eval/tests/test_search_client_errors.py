"""What the client reports when the backend cannot give an answer (FR-014): a typed error that names the cause."""

import socket
import time

import pytest

from fake_backend import FakeBackend
from shoplab_eval.errors import MeasurementError
from shoplab_eval.search_client import KnowledgeSearchClient


def _closed_port_url() -> str:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    return f"http://127.0.0.1:{port}"


def test_a_refused_connection_names_the_base_url() -> None:
    url = _closed_port_url()

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(url, timeout=0.3).search("pregunta", 10)

    assert url in str(error.value)
    assert "backend" in str(error.value)


def test_a_refused_connection_when_listing_documents_names_the_base_url_too() -> None:
    url = _closed_port_url()

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(url, timeout=0.3).list_documents()

    assert url in str(error.value)


@pytest.mark.parametrize(
    ("status", "code"), [(503, "SLHD_RETR_00006"), (422, "SLHD_RETR_00009"), (502, "SLHD_RETR_00007")]
)
def test_an_error_status_is_reported_with_its_status_and_code(backend: FakeBackend, status: int, code: str) -> None:
    backend.search_handler = lambda query, limit: (status, {"code": code, "message": "algo falló"})

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=5).search("pregunta", 10)

    assert str(status) in str(error.value)
    assert code in str(error.value)


def test_an_error_status_without_a_json_body_is_still_reported_with_its_status(backend: FakeBackend) -> None:
    backend.search_handler = lambda query, limit: (500, "<html>boom</html>")

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=5).search("pregunta", 10)

    assert "500" in str(error.value)


def test_an_error_when_listing_documents_is_reported_with_its_status(backend: FakeBackend) -> None:
    backend.documents_reply = (503, {"code": "SLHD_GENE_00002"})

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=5).list_documents()

    assert "503" in str(error.value)
    assert "SLHD_GENE_00002" in str(error.value)


@pytest.mark.parametrize("body", ["<html>no es json</html>", "", '{"model": "bge-m3"}', '{"results": []}', "[1, 2]"])
def test_an_unreadable_search_response_is_reported_as_such(backend: FakeBackend, body: str) -> None:
    backend.search_handler = lambda query, limit: (200, body)

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=5).search("pregunta", 10)

    assert "ilegible" in str(error.value)


def test_an_unreadable_document_list_is_reported_as_such(backend: FakeBackend) -> None:
    backend.documents_reply = (200, '{"no": "una lista"}')

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=5).list_documents()

    assert "ilegible" in str(error.value)


def test_a_search_that_takes_longer_than_the_timeout_is_reported_without_retrying(backend: FakeBackend) -> None:
    def slow(query: str, limit: int | None) -> tuple[int, object]:
        time.sleep(1.0)
        return 200, {"model": "bge-m3", "results": []}

    backend.search_handler = slow

    with pytest.raises(MeasurementError) as error:
        KnowledgeSearchClient(backend.url, timeout=0.2).search("pregunta", 10)

    assert "Tiempo agotado" in str(error.value)
    assert len(backend.search_requests) == 1
