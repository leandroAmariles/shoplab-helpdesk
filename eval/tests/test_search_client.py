"""The HTTP client of the knowledge search, against a local server that plays the backend."""

from fake_backend import MODEL, FakeBackend, indexed_documents, ok_search
from shoplab_eval.search_client import IndexedDocument, KnowledgeSearchClient, SearchHit


def test_search_sends_the_query_and_the_limit_and_keeps_the_order(backend: FakeBackend) -> None:
    question = "¿Cuántos días tengo para devolver un envío, o cambiarlo?"
    backend.search_handler = ok_search({question: ["returns-01.md", "payments-03.md", "returns-01.md"]})
    client = KnowledgeSearchClient(backend.url, timeout=5)

    response = client.search(question, 50)

    assert backend.search_requests == [{"query": question, "limit": "50"}]
    assert response.model == MODEL
    assert [hit.source for hit in response.hits] == ["returns-01.md", "payments-03.md", "returns-01.md"]
    assert response.hits[0] == SearchHit("returns-01.md", 1.0)


def test_search_with_no_results_is_an_empty_response(backend: FakeBackend) -> None:
    client = KnowledgeSearchClient(backend.url, timeout=5)

    response = client.search("pregunta sin resultados", 10)

    assert response.model == MODEL
    assert response.hits == ()


def test_search_keeps_accents_enes_and_question_marks_intact(backend: FakeBackend) -> None:
    question = "¿Qué garantía cubre una reparación del año pasado?"
    client = KnowledgeSearchClient(backend.url, timeout=5)

    client.search(question, 5)

    assert backend.search_requests[0]["query"] == question


def test_list_documents_reads_the_name_and_the_state_of_each_document(backend: FakeBackend) -> None:
    backend.documents_reply = (
        200,
        indexed_documents("payments-01", "returns-01") + indexed_documents("privacy-01", status="PENDING"),
    )
    client = KnowledgeSearchClient(backend.url, timeout=5)

    documents = client.list_documents()

    assert documents == [
        IndexedDocument("payments-01.md", "INDEXED"),
        IndexedDocument("returns-01.md", "INDEXED"),
        IndexedDocument("privacy-01.md", "PENDING"),
    ]


def test_a_trailing_slash_in_the_base_url_makes_no_difference(backend: FakeBackend) -> None:
    backend.search_handler = ok_search({"q": ["a.md"]})
    client = KnowledgeSearchClient(backend.url + "/", timeout=5)

    assert [hit.source for hit in client.search("q", 5).hits] == ["a.md"]


# --- the Host header of the ingress of DEV --------------------------------------------------------------------


def test_with_a_host_header_the_search_and_the_catalog_carry_it(backend: FakeBackend) -> None:
    backend.documents_reply = (200, [])
    client = KnowledgeSearchClient(backend.url, host_header="shoplab.localtest.me")

    client.search("pregunta", 5)
    client.list_documents()

    assert [headers["host"] for headers in backend.get_headers] == ["shoplab.localtest.me"] * 2


def test_without_a_host_header_the_host_is_the_one_of_the_url(backend: FakeBackend) -> None:
    backend.documents_reply = (200, [])

    KnowledgeSearchClient(backend.url).list_documents()

    assert backend.get_headers[0]["host"] == backend.url.removeprefix("http://")
