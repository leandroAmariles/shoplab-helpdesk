"""One JSON log line per gateway request, with consumer, conversation, models, tokens and cost (LAB-84).

The schema is ``specs/LAB-84-litellm-model-gateway/contracts/log-schema.md``. The line never contains the text of
questions, answers or retrieved context, nor keys or authorization headers (FR-026): this module only reads metadata
and counts from LiteLLM's ``standard_logging_object`` and never touches ``messages``, ``response`` or ``input``.

Missing data is ``null``, never an invented 0 (FR-028). A failure of the callback itself must not break the request.

Successes come from ``async_log_success_event``. Failures come from ``async_post_call_failure_hook``, which the proxy
calls once, when the request finally fails: the failure events of the router fire for every failed attempt, including
the ones a fallback then rescues, and would log a request that the customer saw succeed.

The field names follow LiteLLM's documented StandardLoggingPayload; the exact placement of the request headers and of
the fallback information in the pinned version is confirmed by spike S-4 (research R-06).
"""

from __future__ import annotations

import json
import re
import sys
from collections import deque
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

from litellm.integrations.custom_logger import CustomLogger

EVENT = "llm_request"
REQUEST_ID_HEADER = "x-shoplab-request-id"
PURPOSE_PREFIX = "purpose:"
FALLBACK_SUFFIX = "-fallback"
PRIMARY_DEPLOYMENT_SUFFIX = "-primary"
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
SEEN_LIMIT = 2048


def _get(mapping: Any, *path: str) -> Any:
    current = mapping
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _to_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _to_float(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _seconds(value: Any) -> float | None:
    """Accepts epoch seconds (float) or datetime."""
    if isinstance(value, datetime):
        return value.timestamp()
    return _to_float(value)


def _iso(value: Any) -> str:
    seconds = _seconds(value)
    moment = datetime.fromtimestamp(seconds, UTC) if seconds is not None else datetime.now(UTC)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def _millis(start: Any, end: Any) -> int | None:
    begin, finish = _seconds(start), _seconds(end)
    if begin is None or finish is None:
        return None
    return max(0, round((finish - begin) * 1000))


def _header(kwargs: dict, payload: dict, name: str) -> str | None:
    """Case-insensitive lookup of a request header in the places LiteLLM may keep it."""
    candidates = (
        _get(kwargs, "litellm_params", "metadata", "headers"),
        _get(payload, "metadata", "requester_custom_headers"),
        _get(payload, "metadata", "headers"),
        _get(kwargs, "proxy_server_request", "headers"),
    )
    for headers in candidates:
        if isinstance(headers, dict):
            for key, value in headers.items():
                if str(key).lower() == name and value:
                    return str(value)
    return None


def _classify_error(payload: dict) -> tuple[str, str | None]:
    """(result, error_type): only the class of the error, never its message (it may quote the prompt)."""
    info = payload.get("error_information") or {}
    error_class = str(info.get("error_class") or "") or None
    code = str(info.get("error_code") or "")
    lowered = (error_class or "").lower()
    if "budget" in lowered:
        return "budget_exceeded", error_class
    if "authentication" in lowered or code in ("401", "403"):
        return "auth_failed", error_class
    if "notfound" in lowered or "model_not_found" in lowered or code == "404":
        return "model_not_found", error_class
    return "error", error_class


def build_record(kwargs: dict, start_time: Any, end_time: Any, *, failed: bool) -> dict:
    payload = kwargs.get("standard_logging_object") or {}
    end_user = payload.get("end_user") or kwargs.get("user") or _get(kwargs, "litellm_params", "metadata", "user")
    end_user = str(end_user) if end_user else None

    operation, conversation_id = "chat", None
    if end_user and end_user.startswith(PURPOSE_PREFIX):
        operation = end_user[len(PURPOSE_PREFIX) :] or "unknown"
    elif end_user and UUID_PATTERN.match(end_user):
        conversation_id = end_user
    elif "embedding" in str(payload.get("call_type") or kwargs.get("call_type") or ""):
        operation = "embedding"

    served_group = payload.get("model_group") or kwargs.get("model")
    served_id = payload.get("model_id") or _get(kwargs, "litellm_params", "model_info", "id")
    previous = _get(kwargs, "litellm_params", "metadata", "previous_models")
    fallback = bool(previous) or (isinstance(served_group, str) and served_group.endswith(FALLBACK_SUFFIX))
    if isinstance(served_group, str) and served_group.endswith(FALLBACK_SUFFIX):
        requested = served_group[: -len(FALLBACK_SUFFIX)]
    else:
        requested = _get(kwargs, "litellm_params", "metadata", "model_group") or served_group

    prompt_tokens = None if failed else _to_int(payload.get("prompt_tokens"))
    completion_tokens = None if failed else _to_int(payload.get("completion_tokens"))
    total_tokens = None if failed else _to_int(payload.get("total_tokens"))
    if total_tokens is None and prompt_tokens is not None and completion_tokens is not None:
        total_tokens = prompt_tokens + completion_tokens
    unknown_usage = prompt_tokens is None and completion_tokens is None

    cost = None if failed else _to_float(payload.get("response_cost"))
    model_info = _get(kwargs, "litellm_params", "model_info") or {}
    has_price = model_info.get("input_cost_per_token") is not None or model_info.get("output_cost_per_token") is not None
    if failed or unknown_usage:
        cost, cost_status = None, "unknown_usage"
    elif not has_price or cost is None:
        cost, cost_status = None, "unpriced"
    else:
        cost_status = "priced"

    result, error_type = ("success", None) if not failed else _classify_error(payload)
    alias = (
        payload.get("metadata", {}).get("user_api_key_alias") if isinstance(payload.get("metadata"), dict) else None
    ) or _get(kwargs, "litellm_params", "metadata", "user_api_key_alias")

    return {
        "event": EVENT,
        "ts": _iso(end_time),
        "consumer": alias,
        "conversation_id": conversation_id,
        "operation": operation,
        "request_id": _header(kwargs, payload, REQUEST_ID_HEADER),
        "requested_model": requested,
        "served_model": None if failed else _get(kwargs, "litellm_params", "model") or payload.get("model"),
        "served_model_id": None if failed else served_id,
        "fallback": False if failed else fallback,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "cost_usd": cost,
        "cost_status": cost_status,
        "latency_ms": _millis(start_time, end_time),
        "ttft_ms": None if failed else _millis(start_time, payload.get("completionStartTime")),
        "result": result,
        "error_type": error_type,
    }


def _status_of(error: BaseException) -> int | None:
    for attribute in ("status_code", "code"):
        value = getattr(error, attribute, None)
        try:
            return int(value)
        except (TypeError, ValueError):
            continue
    return None


def _failure_result(error: BaseException) -> tuple[str, str]:
    """(result, error_type) of an exception that ended a request: only the class of the error, never its message."""
    error_type = type(error).__name__
    lowered = error_type.lower()
    status = _status_of(error)
    if "budget" in lowered:
        return "budget_exceeded", error_type
    if "authentication" in lowered or "keynotfound" in lowered or status in (401, 403):
        return "auth_failed", error_type
    if "notfound" in lowered or status == 404:
        return "model_not_found", error_type
    return "error", error_type


def build_failure_record(request_data: dict, error: BaseException, key_info: Any, now: Any = None) -> dict:
    """The line of a request that ended in an error: a rejection (key, budget, model) or a model that is down."""
    data = request_data if isinstance(request_data, dict) else {}
    end_user = data.get("user")
    end_user = str(end_user) if end_user else None
    operation, conversation_id = "chat", None
    if end_user and end_user.startswith(PURPOSE_PREFIX):
        operation = end_user[len(PURPOSE_PREFIX) :] or "unknown"
    elif end_user and UUID_PATTERN.match(end_user):
        conversation_id = end_user
    headers = _get(data, "proxy_server_request", "headers") or _get(data, "metadata", "headers") or {}
    request_id = None
    if isinstance(headers, dict):
        request_id = next((str(v) for k, v in headers.items() if str(k).lower() == REQUEST_ID_HEADER and v), None)
    alias = getattr(key_info, "key_alias", None)
    if alias is None and isinstance(key_info, dict):
        alias = key_info.get("key_alias")
    result, error_type = _failure_result(error)
    return {
        "event": EVENT,
        "ts": _iso(now),
        "consumer": alias,
        "conversation_id": conversation_id,
        "operation": operation,
        "request_id": request_id,
        "requested_model": data.get("model"),
        "served_model": None,
        "served_model_id": None,
        "fallback": False,
        "prompt_tokens": None,
        "completion_tokens": None,
        "total_tokens": None,
        "cost_usd": None,
        "cost_status": "unknown_usage",
        "latency_ms": None,
        "ttft_ms": None,
        "result": result,
        "error_type": error_type,
    }


class ShoplabLogger(CustomLogger):
    def __init__(self, write: Callable[[str], None] | None = None) -> None:
        super().__init__()
        self._write = write or self._stdout
        self._seen: deque[str] = deque(maxlen=SEEN_LIMIT)

    @staticmethod
    def _stdout(line: str) -> None:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()

    def _emit(self, kwargs: dict, start_time: Any, end_time: Any, *, failed: bool) -> None:
        try:
            call_id = str(kwargs.get("litellm_call_id") or "")
            if call_id:
                if call_id in self._seen:
                    return
                self._seen.append(call_id)
            self._write(json.dumps(build_record(kwargs, start_time, end_time, failed=failed), separators=(",", ":")))
        except Exception as error:  # the callback must never break a request
            self._write(json.dumps({"event": "callback_error", "error_type": type(error).__name__}))

    async def async_log_success_event(self, kwargs, response_obj, start_time, end_time):  # noqa: ARG002
        self._emit(kwargs, start_time, end_time, failed=False)

    async def async_log_failure_event(self, kwargs, response_obj, start_time, end_time):
        """A failed attempt is not logged: a fallback may still answer (see async_post_call_failure_hook)."""

    async def async_post_call_failure_hook(
        self, request_data, original_exception, user_api_key_dict, traceback_str=None
    ):
        try:
            record = build_failure_record(request_data, original_exception, user_api_key_dict)
            self._write(json.dumps(record, separators=(",", ":")))
        except Exception as error:  # the callback must never break a request
            self._write(json.dumps({"event": "callback_error", "error_type": type(error).__name__}))


shoplab_logger = ShoplabLogger()
