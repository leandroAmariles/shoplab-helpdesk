"""Resolves and checks the versions of the prompts a quality run fixes (specs/LAB-85-versioned-prompts, FR-019, FR-023).

It asks Langfuse, before the assistant is asked anything, which version each ``--prompt`` means: a number must exist, a
label is turned into the number it points to *now* (that number is what the result records, so the run can be
reproduced). A prompt that is not of type text is refused, like the backend would. When Langfuse does not answer the run
stops with an error: measuring with the default text without saying so would falsify the comparison.

Only the standard library; the keys never appear in a message.
"""

import base64
import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Protocol

from shoplab_eval.constants import (
    LANGFUSE_HOST_ENV,
    LANGFUSE_PUBLIC_KEY_ENV,
    LANGFUSE_SECRET_KEY_ENV,
    PROMPT_TEXT_TYPE,
    PROMPTS_PATH,
)
from shoplab_eval.errors import MeasurementError
from shoplab_eval.prompt_selection import PromptChoice, PromptRequest


class PromptResolver(Protocol):
    """What the command needs to fix the prompts: an interface the tests replace without a network."""

    def resolve(self, request: PromptRequest) -> PromptChoice: ...


class PromptClient:
    """The real resolver: ``GET /api/public/v2/prompts/{name}?version=N`` or ``?label=…`` of Langfuse."""

    def __init__(self, host: str, public_key: str, secret_key: str, timeout: float = 10.0) -> None:
        self.host = host.rstrip("/")
        self.timeout = timeout
        credentials = base64.b64encode(f"{public_key}:{secret_key}".encode()).decode()
        self._authorization = f"Basic {credentials}"

    def resolve(self, request: PromptRequest) -> PromptChoice:
        """The version ``request`` means. Raises ``MeasurementError`` naming the prompt and the version or label."""
        parameter = "version" if request.is_version else "label"
        name = urllib.parse.quote(request.name, safe="")
        query = urllib.parse.urlencode({parameter: request.value})
        path = f"{PROMPTS_PATH}/{name}?{query}"
        what = f"la versión {request.value}" if request.is_version else f"la etiqueta {request.value!r}"
        payload = self._get(path, request, what)
        prompt_type = payload.get("type")
        version = payload.get("version")
        if prompt_type != PROMPT_TEXT_TYPE:
            raise MeasurementError(
                f"El prompt {request.name} ({what}) no es de tipo texto sino {prompt_type!r}: el backend solo usa "
                "prompts de texto"
            )
        if not isinstance(version, int) or version < 1:
            raise MeasurementError(f"Langfuse no dijo a qué versión de {request.name} apunta {what}")
        return PromptChoice(request.name, request.value, version)

    def _get(self, path: str, request: PromptRequest, what: str) -> dict[str, Any]:
        http_request = urllib.request.Request(
            f"{self.host}{path}", headers={"Authorization": self._authorization, "Accept": "application/json"}
        )
        try:
            with urllib.request.urlopen(http_request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise MeasurementError(f"El prompt {request.name} no tiene {what} en Langfuse ({self.host})") from None
            if error.code in (401, 403):
                raise MeasurementError(
                    f"Langfuse {self.host} rechazó las claves ({error.code}): comprueba {LANGFUSE_PUBLIC_KEY_ENV} y "
                    f"{LANGFUSE_SECRET_KEY_ENV}"
                ) from None
            raise MeasurementError(f"Langfuse {self.host} respondió {error.code} al pedir {request.name}") from None
        except (TimeoutError, urllib.error.URLError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise MeasurementError(
                f"No se pudo consultar a Langfuse en {self.host} ({reason}): sin él no se puede saber qué versión de "
                f"{request.name} se mide, y no se mide con el texto por defecto (revisa {LANGFUSE_HOST_ENV})"
            ) from None
        except ValueError:
            raise MeasurementError(f"Respuesta ilegible de Langfuse al pedir {request.name}: no es JSON") from None
        return payload if isinstance(payload, dict) else {}
