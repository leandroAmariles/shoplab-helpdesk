"""The HTTP client of the evaluation history against a local server that plays the backend (specs/LAB-31).

The real network code runs (a ``ThreadingHTTPServer`` in a thread), so no test patches ``urllib``, except the one that
needs a timeout. Every failure is a ``StoreError`` that names the cause, and none of them contains the token.
"""

import urllib.request

import pytest

from fake_backend import FakeBackend, eval_settings
from shoplab_eval.errors import StoreError
from shoplab_eval.store_client import EvalStoreClient, SavedRun
from shoplab_eval.store_payload import AssistantSettings

TOKEN = "token-secreto-de-prueba"
PAYLOAD = {"run_id": "t031-1", "kind": "QUALITY", "results": [{"case": {"question": "¿Cuántas cuotas, ñandú?"}}]}


def client(backend: FakeBackend, token: str = TOKEN, **options: object) -> EvalStoreClient:
    return EvalStoreClient(backend.url, token, **options)  # type: ignore[arg-type]


# --- what the assistant uses now --------------------------------------------------------------------------------


def test_the_settings_are_read_with_the_token_in_its_header(backend: FakeBackend) -> None:
    settings = client(backend).settings()

    assert isinstance(settings, AssistantSettings)
    assert (settings.top_k, settings.similarity_threshold, settings.max_context_characters) == (4, 0.6, 8000)
    assert [prompt.name for prompt in settings.prompts] == [
        "chat-system", "chat-small-talk", "chat-context", "chat-empty-context",
    ]  # fmt: skip
    request = backend.eval_requests[0]
    assert (request["method"], request["path"]) == ("GET", "/api/eval/settings")
    assert request["headers"]["X-Eval-Token"] == TOKEN  # type: ignore[index]


def test_the_default_text_of_a_prompt_comes_with_no_version(backend: FakeBackend) -> None:
    backend.settings_replies = [(200, eval_settings(versions={"chat-system": None}))]

    prompt = client(backend).settings().prompts[0]

    assert (prompt.version, prompt.source) == (None, "DEFAULT")


def test_a_backend_without_the_history_is_said_to_be_older(backend: FakeBackend) -> None:
    backend.settings_replies = [(404, {"code": "SLHD_GENE_00001"})]

    with pytest.raises(StoreError, match="versión anterior al historial"):
        client(backend).settings()


# --- is the run id free -----------------------------------------------------------------------------------------


def test_a_run_that_is_not_saved_is_free(backend: FakeBackend) -> None:
    assert client(backend).run_exists("t031-1") is False
    assert backend.eval_requests[0]["path"] == "/api/eval/runs/t031-1"


def test_a_run_that_is_saved_is_not_free(backend: FakeBackend) -> None:
    backend.run_reply = (200, {"run_id": "t031-1", "kind": "QUALITY"})

    assert client(backend).run_exists("t031-1") is True


def test_the_run_id_is_encoded_in_the_path(backend: FakeBackend) -> None:
    client(backend).run_exists("a b/c")

    assert backend.eval_requests[0]["path"] == "/api/eval/runs/a%20b%2Fc"


@pytest.mark.parametrize(
    ("status", "body", "message"),
    [
        (403, {"code": "SLHD_EVAL_00001"}, "rechazó el token"),
        (503, {"code": "SLHD_EVAL_00005"}, "no está disponible"),
        (404, {"code": "SLHD_GENE_00001"}, "versión anterior"),
        (500, {"code": "SLHD_GENE_00001"}, "respondió 500"),
    ],
)
def test_the_answers_that_are_not_a_yes_or_a_no_are_errors_with_their_cause(
    backend: FakeBackend, status: int, body: dict[str, str], message: str
) -> None:
    backend.run_reply = (status, body)

    with pytest.raises(StoreError, match=message):
        client(backend).run_exists("t031-1")


# --- saving ------------------------------------------------------------------------------------------------------


def test_a_run_is_sent_as_utf8_json_with_the_token_and_the_summary_comes_back(backend: FakeBackend) -> None:
    saved = client(backend).save(PAYLOAD)

    assert saved == SavedRun("t031-1", "QUALITY", 1, 1)
    request = backend.eval_requests[0]
    assert (request["method"], request["path"]) == ("POST", "/api/eval/runs")
    assert request["headers"]["X-Eval-Token"] == TOKEN  # type: ignore[index]
    assert request["headers"]["Content-Type"].startswith("application/json")  # type: ignore[index]
    assert request["body"] == PAYLOAD  # the accents and the ñ survive the trip


@pytest.mark.parametrize(
    ("status", "body", "message"),
    [
        (403, {"code": "SLHD_EVAL_00001"}, "shoplab.eval.persistence.token"),
        (409, {"code": "SLHD_EVAL_00003"}, "Ya hay una ejecución guardada con el identificador 't031-1'"),
        (422, {"code": "SLHD_EVAL_00002", "detail": "results[3].scores.faithfulness: fuera de rango"},
         "results\\[3\\].scores.faithfulness"),
        (503, {"code": "SLHD_EVAL_00005"}, "no se guardó nada"),
        (400, {"code": "SLHD_GENE_00002"}, "respondió 400 \\(SLHD_GENE_00002\\)"),
    ],
)  # fmt: skip
def test_a_run_the_backend_does_not_save_is_an_error_that_says_why(
    backend: FakeBackend, status: int, body: dict[str, str], message: str
) -> None:
    backend.save_handler = lambda payload: (status, body)

    with pytest.raises(StoreError, match=message):
        client(backend).save(PAYLOAD)


def test_an_answer_that_does_not_have_the_expected_shape_is_an_error(backend: FakeBackend) -> None:
    backend.save_handler = lambda payload: (201, {"unexpected": True})

    with pytest.raises(StoreError, match="ilegible"):
        client(backend).save(PAYLOAD)


# --- when the backend cannot be reached ------------------------------------------------------------------------


def test_a_backend_that_is_not_there_is_an_error_that_names_it() -> None:
    # On Windows a closed port ends as a timeout instead of a refused connection: both name the backend.
    with pytest.raises(StoreError, match="(No se pudo conectar con el backend en|Tiempo agotado.*backend en) http://127"):
        EvalStoreClient("http://127.0.0.1:1/", TOKEN, timeout=2).run_exists("t031-1")


def test_a_backend_that_does_not_answer_in_time_is_an_error_that_says_so(monkeypatch: pytest.MonkeyPatch) -> None:
    def slow(*args: object, **kwargs: object) -> None:
        raise TimeoutError("timed out")

    monkeypatch.setattr(urllib.request, "urlopen", slow)

    with pytest.raises(StoreError, match="Tiempo agotado"):
        EvalStoreClient("http://127.0.0.1:1", TOKEN, timeout=3).settings()


def test_the_host_header_reaches_the_backend(backend: FakeBackend) -> None:
    client(backend, host_header="shoplab.localtest.me").settings()

    assert backend.eval_requests[0]["headers"]["Host"] == "shoplab.localtest.me"  # type: ignore[index]


# --- the token never shows up -----------------------------------------------------------------------------------


@pytest.mark.parametrize("status", [403, 409, 422, 503, 404, 500])
def test_no_error_message_contains_the_token(backend: FakeBackend, status: int) -> None:
    backend.save_handler = lambda payload: (status, {"code": "SLHD_EVAL_0000X", "detail": "algo"})
    backend.run_reply = (status, {"code": "SLHD_EVAL_0000X"})
    backend.settings_replies = [(status, {"code": "SLHD_EVAL_0000X"})]
    messages = []
    for call in (lambda: client(backend).save(PAYLOAD), lambda: client(backend).run_exists("t031-1"),
                 lambda: client(backend).settings()):  # fmt: skip
        with pytest.raises(StoreError) as error:
            call()
        messages.append(str(error.value))

    assert all(TOKEN not in message for message in messages)
