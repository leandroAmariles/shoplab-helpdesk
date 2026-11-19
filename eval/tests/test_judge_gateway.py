"""The check of the gateway before the measurement and the lookup of the real model behind the judge (specs/LAB-29, R2).

The gateway is a local server that answers like LiteLLM. The point of the check is to fail **before** asking the
assistant anything, naming what is wrong: the gateway, the key or the model.
"""

from collections.abc import Iterator

import pytest

from fake_gateway import GATEWAY_KEY, FakeGateway
from shoplab_eval.errors import MeasurementError
from shoplab_eval.quality_judge import GatewayClient


@pytest.fixture
def gateway() -> Iterator[FakeGateway]:
    server = FakeGateway().start()
    try:
        yield server
    finally:
        server.stop()


def client_for(gateway: FakeGateway, key: str = GATEWAY_KEY) -> GatewayClient:
    return GatewayClient(gateway.url, key, timeout=2.0)


# --- the gateway is ready and has the judge -------------------------------------------------------------------


def test_a_gateway_with_the_judge_model_is_ready(gateway: FakeGateway) -> None:
    client_for(gateway).check_ready("judge")

    assert gateway.requests[0]["path"] == "/v1/models"


def test_the_key_travels_only_in_the_authorization_header(gateway: FakeGateway) -> None:
    client_for(gateway).check_ready("judge")

    assert gateway.requests[0]["authorization"] == f"Bearer {GATEWAY_KEY}"
    assert GATEWAY_KEY not in gateway.requests[0]["path"]


def test_an_invalid_key_names_the_gateway_and_the_variable_and_never_shows_the_key(gateway: FakeGateway) -> None:
    with pytest.raises(MeasurementError) as error:
        client_for(gateway, key="sk-clave-equivocada").check_ready("judge")

    message = str(error.value)
    assert gateway.url in message
    assert "401" in message
    assert "SHOPLAB_EVAL_JUDGE_API_KEY" in message
    assert "sk-clave-equivocada" not in message


def test_a_model_the_key_cannot_call_names_the_model_and_lists_what_it_can(gateway: FakeGateway) -> None:
    with pytest.raises(MeasurementError) as error:
        client_for(gateway).check_ready("chat")

    message = str(error.value)
    assert "chat" in message
    assert "judge" in message and "embeddings" in message


def test_the_embeddings_model_is_checked_too_because_the_relevancy_needs_it(gateway: FakeGateway) -> None:
    gateway.models = {"judge": "ollama_chat/qwen2.5:7b"}

    with pytest.raises(MeasurementError, match="embeddings"):
        client_for(gateway).check_ready("judge")


def test_a_gateway_that_is_down_is_reported_with_its_address() -> None:
    server = FakeGateway().start()
    url = server.url
    server.stop()

    with pytest.raises(MeasurementError) as error:
        GatewayClient(url, GATEWAY_KEY, timeout=0.5).check_ready("judge")

    assert url in str(error.value)
    assert "gateway" in str(error.value)


def test_a_server_error_names_the_status(gateway: FakeGateway) -> None:
    gateway.models_status = 503

    with pytest.raises(MeasurementError, match="503"):
        client_for(gateway).check_ready("judge")


# --- the real model behind the logical name -------------------------------------------------------------------


def test_the_real_model_behind_the_logical_name_is_read_from_the_model_info(gateway: FakeGateway) -> None:
    assert client_for(gateway).underlying_model("judge") == "ollama_chat/qwen2.5:7b"
    assert gateway.requests[-1]["path"] == "/model/info"  # at the root of the gateway, not under /v1


def test_changing_the_model_behind_the_name_changes_what_is_recorded_without_changing_the_name(
    gateway: FakeGateway,
) -> None:
    gateway.models["judge"] = "ollama_chat/llama3.1:8b"

    assert client_for(gateway).underlying_model("judge") == "ollama_chat/llama3.1:8b"


def test_a_logical_name_the_gateway_does_not_list_has_no_known_model(gateway: FakeGateway) -> None:
    assert client_for(gateway).underlying_model("otro") is None


@pytest.mark.parametrize("status", [401, 403, 404, 500])
def test_when_the_model_info_cannot_be_read_the_model_is_unknown_and_it_is_not_an_error(
    gateway: FakeGateway, status: int
) -> None:
    gateway.info_status = status

    assert client_for(gateway).underlying_model("judge") is None


def test_an_unreadable_model_info_is_unknown_and_not_an_error(gateway: FakeGateway) -> None:
    gateway.info_body = {"inesperado": True}

    assert client_for(gateway).underlying_model("judge") is None


def test_a_gateway_that_is_down_has_no_known_model_either() -> None:
    server = FakeGateway().start()
    url = server.url
    server.stop()

    assert GatewayClient(url, GATEWAY_KEY, timeout=0.5).underlying_model("judge") is None
