"""HTTP client of the evaluation history of the backend (specs/LAB-31, contracts/eval-runs-api.md).

Like the search client it uses only the standard library and never retries: a retry could hide exactly the failure the
command has to report, and saving twice is refused by the backend anyway. The token travels in a header and is never part
of a message: every failure is a ``StoreError`` that names the cause and what to do, and none of them contains the token.
"""

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

from shoplab_eval.constants import (
    DEFAULT_TIMEOUT_SECONDS,
    EVAL_RUNS_PATH,
    EVAL_SETTINGS_PATH,
    EVAL_TOKEN_ENV,
    EVAL_TOKEN_HEADER,
)
from shoplab_eval.errors import StoreError
from shoplab_eval.store_payload import AssistantSettings, parse_settings


@dataclass(frozen=True)
class SavedRun:
    """What the backend answers when it saves a run."""

    run_id: str
    kind: str
    results: int
    new_case_revisions: int


class EvalStoreClient:
    """``GET /api/eval/settings``, ``GET /api/eval/runs/{id}`` and ``POST /api/eval/runs`` of the backend."""

    def __init__(
        self, base_url: str, token: str, timeout: float = DEFAULT_TIMEOUT_SECONDS, host_header: str | None = None
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.host_header = host_header
        self._token = token

    def settings(self) -> AssistantSettings:
        """What the assistant uses now: its retrieval parameters and the prompt version it serves for each prompt."""
        status, body = self._request("GET", EVAL_SETTINGS_PATH)
        if status != 200:
            raise self._refused(status, body, EVAL_SETTINGS_PATH)
        return parse_settings(body)

    def run_exists(self, run_id: str) -> bool:
        """Whether a run with that id is already saved. It also proves the token, the database and the schema work."""
        path = f"{EVAL_RUNS_PATH}/{quote(run_id, safe='')}"
        status, body = self._request("GET", path)
        if status == 200:
            return True
        if status == 404 and _code(body) == "SLHD_EVAL_00004":
            return False
        raise self._refused(status, body, path)

    def save(self, payload: dict[str, Any]) -> SavedRun:
        status, body = self._request("POST", EVAL_RUNS_PATH, payload)
        if status != 201:
            raise self._refused(status, body, EVAL_RUNS_PATH, str(payload.get("run_id", "")))
        try:
            return SavedRun(
                str(body["run_id"]), str(body["kind"]), int(body["results"]), int(body["new_case_revisions"])
            )
        except (KeyError, TypeError, ValueError) as error:
            raise StoreError(
                f"Respuesta ilegible de {EVAL_RUNS_PATH}: no tiene la forma esperada ({error!r})"
            ) from error

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, Any]:
        """The status and the JSON of an answer, whatever it is; only a failure to reach the backend is an error."""
        headers = {"Accept": "application/json", EVAL_TOKEN_HEADER: self._token}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if self.host_header:
            headers["Host"] = self.host_header
        request = urllib.request.Request(f"{self.base_url}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, _json(response.read())
        except urllib.error.HTTPError as error:
            return error.code, _json(error.read())
        except TimeoutError as error:
            raise self._timeout_error(path) from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, TimeoutError):
                raise self._timeout_error(path) from error
            raise self._unreachable(error.reason) from error
        except OSError as error:
            raise self._unreachable(error) from error

    def _refused(self, status: int, body: Any, path: str, run_id: str = "") -> StoreError:
        code = _code(body)
        suffix = f" ({code})" if code else ""
        if status == 403:
            return StoreError(
                f"El backend rechazó el token{suffix}: {EVAL_TOKEN_ENV} debe valer lo mismo que "
                "shoplab.eval.persistence.token del backend, y ese valor no puede estar vacío allí (el guardado "
                "está apagado por omisión)"
            )
        if status == 409:
            return StoreError(
                f"Ya hay una ejecución guardada con el identificador {run_id!r}{suffix}: indica otro con --run-id "
                "(una ejecución guardada no se sobrescribe)"
            )
        if status == 422:
            detail = body.get("detail") if isinstance(body, dict) else None
            return StoreError(f"El backend no aceptó la ejecución{suffix}: {detail or 'no cumple las reglas'}")
        if status == 503:
            return StoreError(
                f"El historial de evaluación no está disponible{suffix}: la base de datos del backend no responde; "
                "no se guardó nada"
            )
        if status == 404:
            return StoreError(
                f"El backend no tiene {path}{suffix}: es de una versión anterior al historial de evaluación "
                "(LAB-31); despliega una que lo incluya o ejecuta con --no-save"
            )
        return StoreError(f"El backend respondió {status}{suffix} a {path}")

    def _timeout_error(self, path: str) -> StoreError:
        return StoreError(
            f"Tiempo agotado ({self.timeout:g} s) esperando al backend en {self.base_url}{path}; comprueba que "
            "está levantado en esa dirección"
        )

    def _unreachable(self, reason: object) -> StoreError:
        return StoreError(
            f"No se pudo conectar con el backend en {self.base_url} ({reason}); levanta el stack o indica otra "
            "dirección con --base-url"
        )


def _json(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:  # json.JSONDecodeError and UnicodeDecodeError
        return None


def _code(body: Any) -> str:
    return str(body.get("code", "")) if isinstance(body, dict) else ""
