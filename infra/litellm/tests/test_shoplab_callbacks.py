import asyncio
import json

import shoplab_callbacks
from shoplab_callbacks import ShoplabLogger, build_record

CONVERSATION = "3f1c7a52-5b8e-4d0e-9a47-2f6d1c0b8e11"
SECRET_PROMPT = "¿Cuál es el plazo de devolución? token-secreto-123"
SECRET_ANSWER = "Tiene 30 días para devolverlo"


def _kwargs(**overrides):
    payload = {
        "end_user": CONVERSATION,
        "model_group": "chat",
        "model_id": "chat-primary",
        "model": "ollama_chat/llama3.2:3b",
        "prompt_tokens": 812,
        "completion_tokens": 143,
        "total_tokens": 955,
        "response_cost": 0.0000694,
        "completionStartTime": 1_700_000_000.93,
        "metadata": {"user_api_key_alias": "backend-local"},
        "call_type": "acompletion",
        "messages": [{"role": "user", "content": SECRET_PROMPT}],
        "response": {"choices": [{"message": {"content": SECRET_ANSWER}}]},
    }
    kwargs = {
        "litellm_call_id": "call-1",
        "standard_logging_object": payload,
        "messages": [{"role": "user", "content": SECRET_PROMPT}],
        "litellm_params": {
            "model": "ollama_chat/llama3.2:3b",
            "metadata": {"headers": {"X-Shoplab-Request-Id": "0d3c-request", "Authorization": "Bearer sk-secret"}},
            "model_info": {"id": "chat-primary", "input_cost_per_token": 1.5e-07, "output_cost_per_token": 6e-07},
        },
    }
    for key, value in overrides.items():
        payload[key] = value
    return kwargs


START, END = 1_700_000_000.0, 1_700_000_005.23


def _run(kwargs, *, failed=False):
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    method = logger.async_log_failure_event if failed else logger.async_log_success_event
    asyncio.run(method(kwargs, None, START, END))
    return [json.loads(line) for line in lines]


def test_success_of_chat_has_every_field_of_the_contract():
    (record,) = _run(_kwargs())
    assert record == {
        "event": "llm_request",
        "ts": "2023-11-14T22:13:25.230Z",
        "consumer": "backend-local",
        "conversation_id": CONVERSATION,
        "operation": "chat",
        "request_id": "0d3c-request",
        "requested_model": "chat",
        "served_model": "ollama_chat/llama3.2:3b",
        "served_model_id": "chat-primary",
        "fallback": False,
        "prompt_tokens": 812,
        "completion_tokens": 143,
        "total_tokens": 955,
        "cost_usd": 0.0000694,
        "cost_status": "priced",
        "latency_ms": 5230,
        "ttft_ms": 930,
        "result": "success",
        "error_type": None,
    }


def test_embeddings_are_attributed_to_the_operation_not_to_a_conversation():
    kwargs = _kwargs(end_user="purpose:document-index", call_type="aembedding", model_group="embeddings")
    (record,) = _run(kwargs)
    assert record["conversation_id"] is None
    assert record["operation"] == "document-index"
    kwargs = _kwargs(end_user="purpose:search-query", call_type="aembedding", model_group="embeddings")
    assert _run(kwargs)[0]["operation"] == "search-query"


def test_missing_conversation_is_null():
    (record,) = _run(_kwargs(end_user=None))
    assert record["conversation_id"] is None
    assert record["operation"] == "chat"


def test_unknown_tokens_are_null_never_zero():
    kwargs = _kwargs(prompt_tokens=None, completion_tokens=None, total_tokens=None, response_cost=None)
    (record,) = _run(kwargs)
    assert record["prompt_tokens"] is None
    assert record["completion_tokens"] is None
    assert record["total_tokens"] is None
    assert record["cost_usd"] is None
    assert record["cost_status"] == "unknown_usage"


def test_model_without_price_is_flagged_not_priced_as_zero():
    kwargs = _kwargs()
    kwargs["litellm_params"]["model_info"] = {"id": "chat-primary"}
    (record,) = _run(kwargs)
    assert record["cost_usd"] is None
    assert record["cost_status"] == "unpriced"


def test_fallback_logs_one_line_with_the_model_that_really_answered():
    kwargs = _kwargs(model_group="chat-fallback", model_id="chat-fallback")
    kwargs["litellm_params"]["model"] = "ollama_chat/llama3.2:1b"
    kwargs["litellm_params"]["model_info"]["id"] = "chat-fallback"
    records = _run(kwargs)
    assert len(records) == 1
    assert records[0]["fallback"] is True
    assert records[0]["requested_model"] == "chat"
    assert records[0]["served_model"] == "ollama_chat/llama3.2:1b"
    assert records[0]["served_model_id"] == "chat-fallback"


def test_the_same_call_is_not_logged_twice():
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_log_success_event(_kwargs(), None, START, END))
    asyncio.run(logger.async_log_success_event(_kwargs(), None, START, END))
    assert len(lines) == 1


class _Key:
    key_alias = "backend-local"


class BudgetExceededError(Exception):
    pass


class AuthenticationError(Exception):
    status_code = 401


class NotFoundError(Exception):
    status_code = 404


HEADERS = {"X-Shoplab-Request-Id": "req-9", "Authorization": "Bearer sk-secret"}


def _request():
    return {
        "model": "chat",
        "user": CONVERSATION,
        "messages": [{"role": "user", "content": SECRET_PROMPT}],
        "proxy_server_request": {"headers": dict(HEADERS)},
    }


def _fail(error):
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_post_call_failure_hook(_request(), error, _Key()))
    return lines


def test_a_failed_attempt_is_not_logged_because_a_fallback_may_still_answer():
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_log_failure_event(_kwargs(), None, START, END))
    assert lines == []


def test_a_fallback_success_is_logged_even_after_the_primary_attempt_failed():
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_log_failure_event(_kwargs(), None, START, END))
    fallback = _kwargs(model_group="chat-fallback", model_id="chat-fallback")
    asyncio.run(logger.async_log_success_event(fallback, None, START, END))
    assert [json.loads(line)["served_model_id"] for line in lines] == ["chat-fallback"]


def test_budget_exceeded_is_a_rejection_with_null_tokens_and_cost():
    (line,) = _fail(BudgetExceededError("Budget has been exceeded " + SECRET_PROMPT))
    record = json.loads(line)
    assert record["result"] == "budget_exceeded"
    assert record["error_type"] == "BudgetExceededError"
    assert record["consumer"] == "backend-local"
    assert record["conversation_id"] == CONVERSATION
    assert record["request_id"] == "req-9"
    assert record["requested_model"] == "chat"
    assert record["served_model"] is None
    assert record["prompt_tokens"] is None
    assert record["cost_usd"] is None


def test_auth_and_unknown_model_rejections_are_classified():
    assert json.loads(_fail(AuthenticationError("bad key"))[0])["result"] == "auth_failed"
    assert json.loads(_fail(NotFoundError("no model"))[0])["result"] == "model_not_found"
    assert json.loads(_fail(ConnectionError("down"))[0])["result"] == "error"


def test_no_content_keys_or_authorization_headers_reach_the_output():
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_log_success_event(_kwargs(error_str=SECRET_PROMPT), None, START, END))
    lines.extend(_fail(RuntimeError(SECRET_PROMPT)))
    output = "\n".join(lines)
    assert len(lines) == 2
    assert SECRET_PROMPT not in output
    assert SECRET_ANSWER not in output
    assert "token-secreto-123" not in output
    assert "sk-secret" not in output
    assert "Bearer" not in output
    assert "Authorization" not in output


def test_a_broken_payload_never_raises_and_logs_no_data():
    lines: list[str] = []
    logger = ShoplabLogger(write=lines.append)
    asyncio.run(logger.async_log_success_event({"standard_logging_object": "not-a-dict"}, None, START, END))
    # build_record tolerates the malformed payload; force a real internal error to check the safety net.
    original = shoplab_callbacks.build_record
    shoplab_callbacks.build_record = lambda *a, **k: (_ for _ in ()).throw(RuntimeError("secret " + SECRET_PROMPT))
    try:
        asyncio.run(logger.async_log_success_event(_kwargs(), None, START, END))
    finally:
        shoplab_callbacks.build_record = original
    last = json.loads(lines[-1])
    assert last == {"event": "callback_error", "error_type": "RuntimeError"}


def test_build_record_handles_missing_times():
    record = build_record(_kwargs(), None, None, failed=False)
    assert record["latency_ms"] is None
