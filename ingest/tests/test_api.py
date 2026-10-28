import threading

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import create_app
from tests.pdf_factory import build_blank_pdf, build_pdf, encrypt

MARKDOWN = (
    "# Política de devoluciones\n\nIntroducción general de la política.\n\n"
    "## Plazos\n\nTienes treinta días para devolver un producto sin usar.\n"
).encode()


@pytest.fixture
def client():
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: Settings(chunk_size=200, chunk_overlap=20,
                                                              max_document_bytes=4096)
    return TestClient(app)


def post(client, name, content, **fields):
    return client.post("/ingest", files={"file": (name, content, "application/octet-stream")}, data=fields)


def test_a_markdown_document_comes_back_as_chunks_with_metadata(client):
    response = post(client, "politica.md", MARKDOWN)

    assert response.status_code == 200
    body = response.json()
    assert body["document"] == {"name": "politica.md", "title": "Política de devoluciones", "format": "markdown"}
    assert [chunk["metadata"]["section"] for chunk in body["chunks"]] == [
        "Política de devoluciones", "Política de devoluciones > Plazos"]
    assert all(chunk["metadata"]["document"] == "politica.md" for chunk in body["chunks"])
    assert "treinta días" in body["chunks"][1]["text"]


def test_positions_are_consecutive_from_zero(client):
    body = post(client, "politica.md", MARKDOWN).json()

    assert [chunk["metadata"]["position"] for chunk in body["chunks"]] == list(range(len(body["chunks"])))


def test_the_same_document_gives_the_same_answer(client):
    assert post(client, "politica.md", MARKDOWN).json() == post(client, "politica.md", MARKDOWN).json()


def test_the_chunking_of_the_request_replaces_the_defaults(client):
    long_text = ("# T\n\n" + " ".join(f"Frase número {index} del documento." for index in range(30))).encode()

    default = post(client, "a.md", long_text).json()["chunks"]
    smaller = post(client, "a.md", long_text, chunk_size="100", chunk_overlap="0").json()["chunks"]

    assert len(smaller) > len(default)
    assert all(len(chunk["text"]) <= 100 for chunk in smaller)


def test_a_pdf_is_accepted_with_the_same_shape(client):
    content = build_pdf([("Garantía legal", 20), ("La garantía cubre defectos de fabricación del producto.", 11)])

    body = post(client, "garantia.pdf", content).json()

    assert body["document"]["format"] == "pdf"
    assert body["document"]["title"] == "Garantía legal"
    assert body["chunks"][0]["metadata"] == {"document": "garantia.pdf", "section": "Garantía legal", "position": 0}


@pytest.mark.parametrize(
    ("name", "content", "status", "code"),
    [
        ("notas.txt", b"contenido", 415, "UNSUPPORTED_FORMAT"),
        ("grande.md", b"x" * 5000, 413, "DOCUMENT_TOO_LARGE"),
        ("vacio.md", b"   \n", 422, "EMPTY_DOCUMENT"),
        ("solo-titulos.md", b"# Uno\n\n## Dos\n", 422, "EMPTY_DOCUMENT"),
        ("latin.md", "Política".encode("latin-1"), 422, "INVALID_DOCUMENT"),
        ("falso.pdf", b"esto no es un pdf", 422, "INVALID_DOCUMENT"),
        ("roto.pdf", b"%PDF-1.7\nroto \x00\x01\x02", 422, "INVALID_DOCUMENT"),
        ("escaneado.pdf", build_blank_pdf(), 422, "NO_EXTRACTABLE_TEXT"),
    ],
)
def test_a_document_that_cannot_be_ingested_is_rejected_with_its_code(client, name, content, status, code):
    response = post(client, name, content)

    assert response.status_code == status
    assert response.json()["code"] == code
    assert response.json()["message"]


def test_a_protected_pdf_is_rejected(client):
    response = post(client, "clave.pdf", encrypt(build_pdf([("T", 20), ("Texto de relleno del documento.", 11)])))

    assert (response.status_code, response.json()["code"]) == (422, "INVALID_DOCUMENT")


def test_an_oversized_upload_is_rejected_before_its_body_is_read(client):
    response = client.post("/ingest", content=b"x" * 80_000,
                           headers={"content-type": "multipart/form-data; boundary=x"})

    assert (response.status_code, response.json()["code"]) == (413, "DOCUMENT_TOO_LARGE")


@pytest.mark.parametrize("fields", [{"chunk_size": "200", "chunk_overlap": "200"}, {"chunk_size": "50"},
                                    {"chunk_overlap": "-1"}, {"chunk_size": "abc"}])
def test_an_invalid_chunking_configuration_is_rejected(client, fields):
    response = post(client, "politica.md", MARKDOWN, **fields)

    assert (response.status_code, response.json()["code"]) == (422, "INVALID_CHUNKING_CONFIG")


def test_a_request_without_a_document_is_rejected(client):
    response = client.post("/ingest", data={"chunk_size": "200"})

    assert (response.status_code, response.json()["code"]) == (422, "INVALID_DOCUMENT")


def test_an_unexpected_failure_is_an_internal_error_without_details(client, monkeypatch):
    monkeypatch.setattr("app.api.routes.ingest_document", lambda *args: (_ for _ in ()).throw(RuntimeError("secret")))
    quiet = TestClient(client.app, raise_server_exceptions=False)

    response = quiet.post("/ingest", files={"file": ("a.md", MARKDOWN)})

    assert response.status_code == 500
    assert response.json() == {"code": "INTERNAL_ERROR", "message": "Unexpected error"}


def test_health_answers_ok(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_the_request_id_is_echoed_and_generated_when_missing(client):
    assert client.get("/health", headers={"X-Request-Id": "abc-123"}).headers["X-Request-Id"] == "abc-123"
    assert client.get("/health").headers["X-Request-Id"]


def test_metrics_count_requests_by_outcome(client):
    post(client, "politica.md", MARKDOWN)
    post(client, "notas.txt", b"contenido")

    text = client.get("/metrics").text

    assert 'ingest_requests_total{outcome="ok"}' in text
    assert 'ingest_requests_total{outcome="rejected"}' in text
    assert "ingest_duration_seconds_bucket" in text
    assert "politica" not in text


def test_simultaneous_requests_do_not_interfere(client):
    documents = {f"doc{index}.md": f"# Título {index}\n\nTexto único número {index}.\n".encode() for index in range(20)}
    results: dict[str, dict] = {}

    def send(name: str) -> None:
        results[name] = post(client, name, documents[name]).json()

    threads = [threading.Thread(target=send, args=(name,)) for name in documents]
    [thread.start() for thread in threads]
    [thread.join() for thread in threads]

    for index in range(20):
        body = results[f"doc{index}.md"]
        assert body["document"]["title"] == f"Título {index}"
        assert f"Texto único número {index}." in body["chunks"][0]["text"]
