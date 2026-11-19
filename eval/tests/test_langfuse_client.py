"""The trace id of a turn is derived from its request id (ADR-0022 section 4): the evaluation never looks it up."""

import base64
import hashlib
import re
from collections.abc import Iterator

import pytest

from fake_langfuse import PUBLIC_KEY, SECRET_KEY, FakeLangfuse
from shoplab_eval.constants import TRACE_ID_LENGTH
from shoplab_eval.errors import MeasurementError
from shoplab_eval.langfuse_client import (
    LangfuseClient,
    LangfuseStatus,
    ScoreToPublish,
    score_id_for,
    trace_id_for,
)

REQUEST_ID = "eval-20261118T153000Z-G001"


def test_trace_id_is_the_first_32_hex_characters_of_the_sha256_of_the_request_id() -> None:
    expected = hashlib.sha256(REQUEST_ID.encode("utf-8")).hexdigest()[:32]

    assert trace_id_for(REQUEST_ID) == expected
    assert len(trace_id_for(REQUEST_ID)) == TRACE_ID_LENGTH == 32
    assert re.fullmatch(r"[0-9a-f]{32}", trace_id_for(REQUEST_ID))


def test_trace_id_has_a_fixed_known_value() -> None:
    """A literal vector (SHA-256 of the request id, first 32 hex digits) so a change of digest or length is noticed."""
    assert trace_id_for(REQUEST_ID) == "8dbde05b079b1903caf79c9963713ba7"


def test_trace_id_is_deterministic_and_changes_with_the_request_id() -> None:
    assert trace_id_for(REQUEST_ID) == trace_id_for(REQUEST_ID)
    assert trace_id_for(REQUEST_ID) != trace_id_for("eval-20261118T153000Z-G002")
    assert trace_id_for(REQUEST_ID) != trace_id_for("eval-20261118T153001Z-G001")


@pytest.mark.parametrize("request_id", ["", " "])
def test_trace_id_rejects_an_empty_request_id(request_id: str) -> None:
    with pytest.raises(ValueError, match="identificador de petición"):
        trace_id_for(request_id)


def test_langfuse_status_has_the_three_states_of_the_data_model() -> None:
    assert {status.name for status in LangfuseStatus} == {"ATTACHED", "NOT_ATTACHED", "SKIPPED"}
    assert LangfuseStatus.ATTACHED.value == "yes"
    assert LangfuseStatus.NOT_ATTACHED.value == "no"
    assert LangfuseStatus.SKIPPED.value == "skipped"


# --- the REST client (US3): access, finding a trace and publishing scores -------------------------------------

TRACE_ID = trace_id_for(REQUEST_ID)


@pytest.fixture
def langfuse() -> Iterator[FakeLangfuse]:
    server = FakeLangfuse().start()
    try:
        yield server
    finally:
        server.stop()


def langfuse_client(server: FakeLangfuse, wait: float = 1.0, secret: str = SECRET_KEY) -> LangfuseClient:
    return LangfuseClient(server.url, PUBLIC_KEY, secret, timeout=2.0, wait_seconds=wait, poll_seconds=0.01)


def a_score(**overrides: object) -> ScoreToPublish:
    values: dict[str, object] = {
        "score_id": score_id_for("20261118T153000Z", "G001", "faithfulness"),
        "trace_id": TRACE_ID,
        "name": "faithfulness",
        "value": 0.8333,
        "comment": "judge=judge; ragas=0.4.4",
        "metadata": {"run_id": "20261118T153000Z", "case_id": "G001", "judge_model": "judge"},
    }
    values.update(overrides)
    return ScoreToPublish(**values)  # type: ignore[arg-type]


def test_the_access_check_uses_the_projects_endpoint_with_basic_authentication(langfuse: FakeLangfuse) -> None:
    langfuse_client(langfuse).check_access()

    (request,) = langfuse.requests
    assert request["path"] == "/api/public/projects"
    expected = base64.b64encode(f"{PUBLIC_KEY}:{SECRET_KEY}".encode()).decode()
    assert request["authorization"] == f"Basic {expected}"


def test_wrong_credentials_name_the_host_and_the_variable_and_never_show_the_secret(langfuse: FakeLangfuse) -> None:
    with pytest.raises(MeasurementError) as error:
        langfuse_client(langfuse, secret="sk-lf-equivocada").check_access()

    message = str(error.value)
    assert langfuse.url in message
    assert "401" in message
    assert "SHOPLAB_EVAL_LANGFUSE_SECRET_KEY" in message
    assert "sk-lf-equivocada" not in message and SECRET_KEY not in message


def test_a_langfuse_that_is_down_is_reported_with_its_address() -> None:
    server = FakeLangfuse().start()
    url = server.url
    server.stop()

    with pytest.raises(MeasurementError) as error:
        LangfuseClient(url, PUBLIC_KEY, SECRET_KEY, timeout=0.5, wait_seconds=0.1, poll_seconds=0.01).check_access()

    assert url in str(error.value)
    assert "Langfuse" in str(error.value)


def test_a_trace_is_found_through_the_observations_endpoint_and_never_the_traces_one(langfuse: FakeLangfuse) -> None:
    langfuse.add_trace(TRACE_ID)

    langfuse_client(langfuse).wait_for_trace(TRACE_ID)

    paths = [request["path"] for request in langfuse.requests]
    assert paths == ["/api/public/v2/observations"]  # /api/public/traces does not exist in events_only mode


def test_a_trace_that_takes_a_moment_to_arrive_is_waited_for(langfuse: FakeLangfuse) -> None:
    langfuse.add_trace(TRACE_ID)
    langfuse.delay[TRACE_ID] = 3

    langfuse_client(langfuse).wait_for_trace(TRACE_ID)

    assert len(langfuse.requests) == 4


def test_a_trace_that_never_arrives_is_an_error_that_names_it(langfuse: FakeLangfuse) -> None:
    with pytest.raises(MeasurementError) as error:
        langfuse_client(langfuse, wait=0.15).wait_for_trace(TRACE_ID)

    assert TRACE_ID in str(error.value)
    assert "no apareció" in str(error.value)


def test_a_score_is_posted_with_the_fields_langfuse_expects(langfuse: FakeLangfuse) -> None:
    langfuse_client(langfuse).publish_score(a_score())

    (payload,) = langfuse.score_requests
    assert payload == {
        "id": a_score().score_id,
        "traceId": TRACE_ID,
        "name": "faithfulness",
        "value": 0.8333,
        "dataType": "NUMERIC",
        "comment": "judge=judge; ragas=0.4.4",
        "metadata": {"run_id": "20261118T153000Z", "case_id": "G001", "judge_model": "judge"},
    }
    assert langfuse.requests[-1]["path"] == "/api/public/scores"


def test_publishing_the_same_score_twice_leaves_one_score_with_the_last_value(langfuse: FakeLangfuse) -> None:
    client = langfuse_client(langfuse)

    client.publish_score(a_score(value=0.25))
    client.publish_score(a_score(value=0.75))

    assert len(langfuse.scores) == 1
    assert next(iter(langfuse.scores.values()))["value"] == 0.75


@pytest.mark.parametrize("status", [400, 401, 422, 500, 503])
def test_a_rejected_score_names_the_status_and_is_not_retried(langfuse: FakeLangfuse, status: int) -> None:
    langfuse.score_status = status

    with pytest.raises(MeasurementError, match=str(status)):
        langfuse_client(langfuse).publish_score(a_score())

    assert len(langfuse.score_requests) == 1


def test_a_score_id_is_the_first_32_hex_digits_of_the_sha256_of_run_case_and_metric() -> None:
    expected = hashlib.sha256(b"20261118T153000Z:G001:faithfulness").hexdigest()[:32]

    assert score_id_for("20261118T153000Z", "G001", "faithfulness") == expected
    assert len(expected) == 32


def test_a_score_id_changes_with_the_run_the_case_and_the_metric() -> None:
    base = score_id_for("20261118T153000Z", "G001", "faithfulness")

    assert base == score_id_for("20261118T153000Z", "G001", "faithfulness")
    assert base != score_id_for("20261118T153001Z", "G001", "faithfulness")
    assert base != score_id_for("20261118T153000Z", "G002", "faithfulness")
    assert base != score_id_for("20261118T153000Z", "G001", "answer_relevancy")
