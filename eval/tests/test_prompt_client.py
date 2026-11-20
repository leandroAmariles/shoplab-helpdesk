"""The resolution of the prompts a run fixes, against a local server that plays Langfuse (specs/LAB-85, FR-019, FR-023).

It is the real network code. A label turns into the version it points to now; a version has to exist; and with Langfuse
down, or without a usable answer, it is an error: the run is never measured with the default text.
"""

import pytest

from fake_langfuse import PUBLIC_KEY, SECRET_KEY, FakeLangfuse
from shoplab_eval.errors import MeasurementError
from shoplab_eval.prompt_client import PromptClient
from shoplab_eval.prompt_selection import PromptRequest


@pytest.fixture
def langfuse():
    server = FakeLangfuse().start()
    try:
        yield server
    finally:
        server.stop()


def client_for(langfuse: FakeLangfuse) -> PromptClient:
    return PromptClient(langfuse.url, PUBLIC_KEY, SECRET_KEY, timeout=2.0)


def test_a_version_that_exists_is_confirmed_and_keeps_what_was_written(langfuse: FakeLangfuse) -> None:
    langfuse.add_prompt("chat-system", 3)

    choice = client_for(langfuse).resolve(PromptRequest("chat-system", "3"))

    assert (choice.name, choice.requested, choice.version) == ("chat-system", "3", 3)
    assert langfuse.prompt_requests == [("chat-system", "version", "3")]


def test_a_label_is_turned_into_the_version_it_points_to(langfuse: FakeLangfuse) -> None:
    langfuse.add_prompt("chat-context", 5, label="staging")

    choice = client_for(langfuse).resolve(PromptRequest("chat-context", "staging"))

    assert (choice.requested, choice.version) == ("staging", 5)
    assert langfuse.prompt_requests == [("chat-context", "label", "staging")]


def test_a_version_that_does_not_exist_names_the_prompt_and_the_version(langfuse: FakeLangfuse) -> None:
    with pytest.raises(MeasurementError, match="chat-system.*versión 99"):
        client_for(langfuse).resolve(PromptRequest("chat-system", "99"))


def test_a_label_that_points_nowhere_names_the_prompt_and_the_label(langfuse: FakeLangfuse) -> None:
    with pytest.raises(MeasurementError, match="chat-system.*etiqueta 'staging'"):
        client_for(langfuse).resolve(PromptRequest("chat-system", "staging"))


def test_a_prompt_of_type_chat_is_refused_because_the_backend_only_uses_text(langfuse: FakeLangfuse) -> None:
    langfuse.add_prompt("chat-system", 2, prompt_type="chat")

    with pytest.raises(MeasurementError, match="no es de tipo texto"):
        client_for(langfuse).resolve(PromptRequest("chat-system", "2"))


def test_langfuse_that_refuses_the_keys_says_which_variables_to_check(langfuse: FakeLangfuse) -> None:
    langfuse.add_prompt("chat-system", 1)

    with pytest.raises(MeasurementError, match="SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY") as error:
        PromptClient(langfuse.url, "pk-mala", "sk-mala-secreta").resolve(PromptRequest("chat-system", "1"))

    assert "sk-mala-secreta" not in str(error.value)


def test_a_langfuse_that_is_down_is_an_error_that_says_it_is_not_measured_with_the_default() -> None:
    down = PromptClient("http://127.0.0.1:1", PUBLIC_KEY, SECRET_KEY, timeout=1.0)

    with pytest.raises(MeasurementError, match="no se mide con el texto por defecto") as error:
        down.resolve(PromptRequest("chat-system", "1"))

    assert SECRET_KEY not in str(error.value)


def test_an_answer_that_is_not_json_is_an_error(langfuse: FakeLangfuse) -> None:
    langfuse.prompts[("chat-system", "version", "1")] = (200, "esto no es un objeto json")

    with pytest.raises(MeasurementError):
        client_for(langfuse).resolve(PromptRequest("chat-system", "1"))
