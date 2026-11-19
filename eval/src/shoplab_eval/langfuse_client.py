"""Langfuse side of the answer quality measurement (specs/LAB-29-answer-quality-metrics, research R4).

The trace id of a turn is derived from the request id the evaluation chose, so the trace is known without looking it up
(ADR-0022 section 4). The client uses only the standard library and the three calls that exist in Langfuse 4.x in
``events_only`` mode, verified in ``validation.md`` (S-3): ``GET /api/public/projects`` to check the keys,
``GET /api/public/v2/observations?traceId=…`` to know whether a trace has arrived and ``POST /api/public/scores`` to
attach a score. The read endpoints of traces and scores do not exist in that mode, so nothing here reads a score back.

A score has a deterministic id, so publishing it twice updates it instead of duplicating it. Nothing is retried: a
failure is reported, never hidden. The keys never appear in a message.
"""

import base64
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol

from shoplab_eval.constants import (
    LANGFUSE_HOST_ENV,
    LANGFUSE_PUBLIC_KEY_ENV,
    LANGFUSE_SCORES_PATH,
    LANGFUSE_SECRET_KEY_ENV,
    SCORE_ID_LENGTH,
    TRACE_ID_LENGTH,
    TRACE_POLL_SECONDS,
    TRACE_WAIT_SECONDS,
)
from shoplab_eval.errors import MeasurementError

PROJECTS_PATH = "/api/public/projects"
OBSERVATIONS_PATH = "/api/public/v2/observations"


class LangfuseStatus(Enum):
    """Whether the scores of a case reached its trace (the ``langfuse`` column of the detail CSV)."""

    ATTACHED = "yes"
    NOT_ATTACHED = "no"
    SKIPPED = "skipped"


def trace_id_for(request_id: str) -> str:
    """The id of the Langfuse trace of a turn: the first 32 hex digits of SHA-256(``X-Request-Id``) (ADR-0022)."""
    if not request_id.strip():
        raise ValueError("Falta el identificador de petición: sin él no se puede derivar la traza del turno")
    return hashlib.sha256(request_id.encode("utf-8")).hexdigest()[:TRACE_ID_LENGTH]


def score_id_for(run_id: str, case_id: str, metric: str) -> str:
    """The id of a score: one per run, case and metric, so publishing it again updates it and never duplicates it."""
    return hashlib.sha256(f"{run_id}:{case_id}:{metric}".encode()).hexdigest()[:SCORE_ID_LENGTH]


@dataclass(frozen=True)
class ScoreToPublish:
    """A numeric score of one trace, as ``POST /api/public/scores`` takes it."""

    score_id: str
    trace_id: str
    name: str
    value: float
    comment: str
    metadata: Mapping[str, str]


class TraceScores(Protocol):
    """What the measurement needs from Langfuse; the tests replace it with a double that has no HTTP."""

    def check_access(self) -> None: ...

    def wait_for_trace(self, trace_id: str) -> None: ...

    def publish_score(self, score: ScoreToPublish) -> None: ...


class LangfuseClient:
    """The real ``TraceScores``: Basic authentication with the public and the secret key of the Langfuse project."""

    def __init__(
        self,
        host: str,
        public_key: str,
        secret_key: str,
        timeout: float = 10.0,
        wait_seconds: float = TRACE_WAIT_SECONDS,
        poll_seconds: float = TRACE_POLL_SECONDS,
    ) -> None:
        self.host = host.rstrip("/")
        self.timeout = timeout
        self.wait_seconds = wait_seconds
        self.poll_seconds = poll_seconds
        self._authorization = "Basic " + base64.b64encode(f"{public_key}:{secret_key}".encode()).decode()

    def check_access(self) -> None:
        """Raises ``MeasurementError`` when Langfuse is not there or does not accept the keys."""
        self._request("GET", PROJECTS_PATH)

    def wait_for_trace(self, trace_id: str) -> None:
        """Returns when Langfuse lists an observation of the trace; raises ``MeasurementError`` if it never does."""
        path = f"{OBSERVATIONS_PATH}?{urllib.parse.urlencode({'traceId': trace_id, 'limit': 1})}"
        deadline = time.monotonic() + self.wait_seconds
        while True:
            if self._request("GET", path).get("data"):
                return
            if time.monotonic() >= deadline:
                raise MeasurementError(
                    f"La traza {trace_id} no apareció en Langfuse tras {self.wait_seconds:g} s: comprueba que las "
                    "trazas del chat están activas (SHOPLAB_CHAT_TRACING_ENABLED=true) y que Langfuse las recibe"
                )
            time.sleep(self.poll_seconds)

    def publish_score(self, score: ScoreToPublish) -> None:
        body = {
            "id": score.score_id,
            "traceId": score.trace_id,
            "name": score.name,
            "value": score.value,
            "dataType": "NUMERIC",
            "comment": score.comment,
            "metadata": dict(score.metadata),
        }
        self._request("POST", LANGFUSE_SCORES_PATH, body)

    def _request(self, method: str, path: str, body: Mapping[str, Any] | None = None) -> dict[str, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Authorization": self._authorization, "Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.host}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as error:
            raise self._http_error(path, error.code) from None
        except (TimeoutError, urllib.error.URLError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise MeasurementError(
                f"No se pudo conectar con Langfuse en {self.host} ({reason}); levanta el perfil llmops "
                f"(docker compose -f infra/docker-compose.yml --profile llmops up) o indica otro con {LANGFUSE_HOST_ENV}"
            ) from None
        except ValueError:
            raise MeasurementError(f"Respuesta ilegible de Langfuse en {self.host}{path}: no es JSON") from None
        return payload if isinstance(payload, dict) else {}

    def _http_error(self, path: str, status: int) -> MeasurementError:
        if status in (401, 403):
            return MeasurementError(
                f"Langfuse {self.host} rechazó las claves ({status}): comprueba {LANGFUSE_PUBLIC_KEY_ENV} y "
                f"{LANGFUSE_SECRET_KEY_ENV} (las del proyecto de Langfuse)"
            )
        return MeasurementError(f"Langfuse {self.host} respondió {status} a {path.split('?')[0]}")
