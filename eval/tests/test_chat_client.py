"""The client of ``POST /api/chat`` against a local server that plays the backend (specs/LAB-29, research R3).

It is the real network code: a ``ThreadingHTTPServer`` in a thread sends real server-sent events, so nothing patches
``urllib``. The turn is what the customer would have received: the answer and the context handed to the model.
"""

import time

import pytest

from fake_backend import ChatReply, FakeBackend, chat_turn, source
from shoplab_eval.chat_client import ChatTurn, ContextItem, SseChatClient
from shoplab_eval.errors import MeasurementError

REQUEST_ID = "eval-20261118T153000Z-G001"
TITLES = {
    "Política de devoluciones": "returns-01-return-policy",
    "Pago en cuotas": "payments-02-installments",
}


def client_for(backend: FakeBackend, timeout: float = 5.0) -> SseChatClient:
    return SseChatClient(backend.url, timeout=timeout, title_to_id=TITLES)


# --- the answer and the context -------------------------------------------------------------------------------


def test_the_answer_is_the_tokens_in_order_and_the_context_is_the_sources_in_order(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Puedes devolverlo en 7 días corridos.",
        sources=[
            source(1, "Política de devoluciones", "Dentro de los 7 días corridos…", section="Cómo funciona"),
            source(2, "Pago en cuotas", "Hasta 12 cuotas…"),
        ],
        tokens=5,
    )

    turn = client_for(backend).ask("¿Puedo devolverlo?", REQUEST_ID)

    assert turn == ChatTurn(
        request_id=REQUEST_ID,
        answer="Puedes devolverlo en 7 días corridos.",
        context=(
            ContextItem(1, "Política de devoluciones", "Cómo funciona", "Dentro de los 7 días corridos…",
                        "returns-01-return-policy"),
            ContextItem(2, "Pago en cuotas", "", "Hasta 12 cuotas…", "payments-02-installments"),
        ),
    )


def test_a_title_that_is_not_in_the_corpus_has_no_document_id_and_is_not_an_error(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Respuesta", sources=[source(1, "Documento subido a mano", "Texto")]
    )

    turn = client_for(backend).ask("Pregunta", REQUEST_ID)

    assert turn.context[0].title == "Documento subido a mano"
    assert turn.context[0].document_id is None


def test_without_a_sources_event_the_context_is_empty_and_it_is_not_an_error(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: chat_turn("No encontré información sobre eso.")

    turn = client_for(backend).ask("Pregunta", REQUEST_ID)

    assert turn.context == ()
    assert turn.answer == "No encontré información sobre eso."


def test_accents_and_enyes_arrive_whole_even_when_a_token_ends_in_the_middle_of_a_word(backend: FakeBackend) -> None:
    answer = "El ñandú vuela más rápido: ¿envío estándar o exprés? Sí, también en días hábiles."
    backend.chat_handler = lambda message, headers: chat_turn(answer, tokens=7)

    assert client_for(backend).ask("Pregunta", REQUEST_ID).answer == answer


# --- what is sent ---------------------------------------------------------------------------------------------


def test_the_request_carries_the_request_id_asks_for_a_stream_and_sends_only_the_message(backend: FakeBackend) -> None:
    client_for(backend).ask("¿Cuántas cuotas?", REQUEST_ID)

    (request,) = backend.chat_requests
    assert request["headers"]["x-request-id"] == REQUEST_ID
    assert request["headers"]["accept"] == "text/event-stream"
    assert request["headers"]["content-type"].startswith("application/json")
    # No conversationId: the chat would keep every evaluation conversation forever (LAB-24), see research R3.
    assert request["body"] == {"message": "¿Cuántas cuotas?"}


def test_a_failed_turn_is_not_retried(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(
        events=[("error", {"code": "SLHD_CHAT_00003", "message": "The chat model failed"})]
    )

    with pytest.raises(MeasurementError):
        client_for(backend).ask("Pregunta", REQUEST_ID)

    assert len(backend.chat_requests) == 1


# --- a turn that is not a turn --------------------------------------------------------------------------------


def test_an_error_event_names_the_request_and_the_code_and_is_not_a_low_quality_answer(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(
        events=[("token", {"content": "Respuesta a medi"}), ("error", {"code": "SLHD_CHAT_00003", "message": "boom"})]
    )

    with pytest.raises(MeasurementError, match=rf"{REQUEST_ID}.*SLHD_CHAT_00003"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


@pytest.mark.parametrize(
    ("status", "code"),
    [(400, "SLHD_CHAT_00001"), (422, "SLHD_CHAT_00009"), (429, "SLHD_CHAT_00007"), (503, "SLHD_CHAT_00002")],
)
def test_an_http_error_before_the_stream_names_the_request_the_status_and_the_code(
    backend: FakeBackend, status: int, code: str
) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(status=status, body={"code": code, "message": "x"})

    with pytest.raises(MeasurementError, match=rf"{REQUEST_ID}.*{status}.*{code}"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


def test_an_http_error_without_a_json_body_still_names_the_status(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(status=502, body="<html>Bad Gateway</html>")

    with pytest.raises(MeasurementError, match="502"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


def test_a_stream_cut_before_done_is_an_error(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(events=[("token", {"content": "Respuesta a medi"})])

    with pytest.raises(MeasurementError, match=rf"{REQUEST_ID}.*done"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


def test_an_empty_answer_is_an_error_not_a_zero(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(events=[("done", {"fragments": 0})])

    with pytest.raises(MeasurementError, match="respuesta vacía"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


def test_an_unreadable_event_is_an_error(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(
        events=[("sources", "esto no es el objeto esperado"), ("done", {"fragments": 0})]
    )

    with pytest.raises(MeasurementError, match="ilegible"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


def test_a_response_that_does_not_echo_the_request_id_is_an_error_because_its_trace_would_not_be_the_expected_one(
    backend: FakeBackend,
) -> None:
    reply = chat_turn("Respuesta")
    reply.headers["X-Request-Id"] = "otro-identificador"
    backend.chat_handler = lambda message, headers: reply

    with pytest.raises(MeasurementError, match="X-Request-Id"):
        client_for(backend).ask("Pregunta", REQUEST_ID)


# --- the backend is not there ---------------------------------------------------------------------------------


def test_a_backend_that_is_down_is_reported_with_its_address() -> None:
    server = FakeBackend().start()
    url = server.url
    server.stop()

    # On Windows a closed port can also end in a timeout (LAB-28 documents it): either way the address is named.
    with pytest.raises(MeasurementError) as error:
        SseChatClient(url, timeout=0.5, title_to_id=TITLES).ask("Pregunta", REQUEST_ID)

    assert url in str(error.value)
    assert "backend" in str(error.value)


def test_a_backend_that_does_not_answer_in_time_is_reported_as_a_timeout(backend: FakeBackend) -> None:
    def slow(message: str, headers: dict[str, str]) -> ChatReply:
        time.sleep(1.0)
        return chat_turn("Respuesta")

    backend.chat_handler = slow

    with pytest.raises(MeasurementError, match="Tiempo agotado"):
        client_for(backend, timeout=0.2).ask("Pregunta", REQUEST_ID)


# --- the Host header of the ingress of DEV --------------------------------------------------------------------


def test_with_a_host_header_the_request_carries_it_even_though_the_url_is_an_ip_address(backend: FakeBackend) -> None:
    client = SseChatClient(backend.url, timeout=5.0, title_to_id=TITLES, host_header="shoplab.localtest.me")

    client.ask("Pregunta", REQUEST_ID)

    assert backend.chat_requests[0]["headers"]["host"] == "shoplab.localtest.me"


def test_without_a_host_header_the_request_carries_the_host_of_the_url(backend: FakeBackend) -> None:
    client_for(backend).ask("Pregunta", REQUEST_ID)

    assert backend.chat_requests[0]["headers"]["host"] == backend.url.removeprefix("http://")


# --- the versions of the prompts an evaluation fixes (LAB-85) --------------------------------------------------


def test_with_versions_every_request_carries_them_and_the_token(backend: FakeBackend) -> None:
    client = SseChatClient(backend.url, timeout=5.0, prompt_versions="chat-system=3,chat-context=2", prompt_token="tok")

    client.ask("Pregunta", REQUEST_ID)

    (request,) = backend.chat_requests
    assert request["headers"]["x-prompt-versions"] == "chat-system=3,chat-context=2"
    assert request["headers"]["x-prompt-token"] == "tok"
    assert request["body"] == {"message": "Pregunta"}  # the versions are never in the body


def test_without_versions_neither_header_is_sent(backend: FakeBackend) -> None:
    client_for(backend).ask("Pregunta", REQUEST_ID)

    (request,) = backend.chat_requests
    assert "x-prompt-versions" not in request["headers"]
    assert "x-prompt-token" not in request["headers"]


def test_a_403_for_the_versions_explains_the_token_and_never_echoes_it(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(status=403, body={"code": "SLHD_CHAT_00008"})
    client = SseChatClient(backend.url, timeout=5.0, prompt_versions="chat-system=3", prompt_token="tok-secreto")

    with pytest.raises(MeasurementError, match="override-token") as error:
        client.ask("Pregunta", REQUEST_ID)

    assert "SLHD_CHAT_00008" in str(error.value)
    assert "tok-secreto" not in str(error.value)


def test_a_422_says_that_a_fixed_version_could_not_be_read(backend: FakeBackend) -> None:
    backend.chat_handler = lambda message, headers: ChatReply(status=422, body={"code": "SLHD_CHAT_00010"})
    client = SseChatClient(backend.url, timeout=5.0, prompt_versions="chat-system=99", prompt_token="tok")

    with pytest.raises(MeasurementError, match="versiones de prompt fijadas"):
        client.ask("Pregunta", REQUEST_ID)
