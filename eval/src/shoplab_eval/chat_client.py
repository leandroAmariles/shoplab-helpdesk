"""Client of the assistant's chat (``POST /api/chat``) for the answer quality measurement (specs/LAB-29, research R3).

It asks the real assistant one question and keeps what the customer would have received: the answer (the ``token``
events in order) and the context handed to the model (the ``sources`` event, in order). It uses only the standard
library and never retries: a retry would hide exactly the failures the measurement has to report. Every failure is a
``MeasurementError`` that names the request, which carries the id of the case (``eval-<run>-<case>``), so a broken
environment is never mistaken for an answer of low quality.

No ``conversationId`` is sent: with it the chat keeps the conversation forever (LAB-24) and every run would leave 96
of them behind. Without it each turn is independent and nothing is stored.
"""

import json
import urllib.error
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

from shoplab_eval.constants import CHAT_PATH, DEFAULT_TURN_TIMEOUT_SECONDS
from shoplab_eval.errors import MeasurementError

REQUEST_ID_HEADER = "X-Request-Id"


@dataclass(frozen=True)
class ContextItem:
    """A fragment the model received, as the ``sources`` event describes it (LAB-23): its text is what was handed over.

    ``document_id`` is the id of the document in the corpus (the file name without extension), found through its
    title; it is ``None`` for a document that is not in the corpus (for example one uploaded by hand).
    """

    number: int
    title: str
    section: str
    text: str
    document_id: str | None


@dataclass(frozen=True)
class ChatTurn:
    """A complete turn: the answer and the context in the order it was delivered.

    ``context`` is empty when the chat sent no ``sources`` event (nothing passed the similarity threshold), which is not
    an error. A turn that did not finish with ``done`` never becomes a ``ChatTurn``.
    """

    request_id: str
    answer: str
    context: tuple[ContextItem, ...]


class ChatClient(Protocol):
    """What the measurement needs from the assistant; the tests replace it with a double that has no HTTP."""

    def ask(self, question: str, request_id: str) -> ChatTurn: ...


class SseChatClient:
    """The real ``ChatClient``: ``POST /api/chat`` read as a stream of server-sent events."""

    def __init__(
        self,
        base_url: str,
        timeout: float = DEFAULT_TURN_TIMEOUT_SECONDS,
        title_to_id: Mapping[str, str] | None = None,
        host_header: str | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.title_to_id = dict(title_to_id or {})
        self.host_header = host_header

    def ask(self, question: str, request_id: str) -> ChatTurn:
        headers = {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            REQUEST_ID_HEADER: request_id,
        }
        if self.host_header:
            headers["Host"] = self.host_header
        request = urllib.request.Request(
            f"{self.base_url}{CHAT_PATH}",
            data=json.dumps({"message": question}).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                echoed = response.headers.get(REQUEST_ID_HEADER)
                if echoed != request_id:
                    raise MeasurementError(
                        f"La petición {request_id} volvió con {REQUEST_ID_HEADER} {echoed!r}: el backend cambió el "
                        "identificador y la traza del turno no sería la esperada"
                    )
                events = list(_read_events(response, request_id))
        except urllib.error.HTTPError as error:
            raise MeasurementError(_http_error_message(request_id, error)) from error
        except TimeoutError as error:
            raise self._timeout_error() from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, TimeoutError):
                raise self._timeout_error() from error
            raise self._unreachable(error.reason) from error
        except OSError as error:
            raise self._unreachable(error) from error
        return self._turn(request_id, events)

    def _turn(self, request_id: str, events: list[tuple[str, Any]]) -> ChatTurn:
        context: tuple[ContextItem, ...] = ()
        pieces: list[str] = []
        done = False
        for name, data in events:
            if name == "sources":
                context = self._context(request_id, data)
            elif name == "token":
                pieces.append(_field(request_id, name, data, "content", str))
            elif name == "done":
                done = True
            elif name == "error":
                code = data.get("code", "?") if isinstance(data, dict) else "?"
                raise MeasurementError(
                    f"El asistente falló en la petición {request_id}: evento error {code} a mitad del turno; no se "
                    "cuenta como una respuesta de baja calidad"
                )
        if not done:
            raise MeasurementError(
                f"La petición {request_id} terminó sin `done`: el flujo se cortó antes de completar la respuesta"
            )
        answer = "".join(pieces)
        if not answer.strip():
            raise MeasurementError(f"El asistente devolvió una respuesta vacía en la petición {request_id}")
        return ChatTurn(request_id=request_id, answer=answer, context=context)

    def _context(self, request_id: str, data: Any) -> tuple[ContextItem, ...]:
        sources = _field(request_id, "sources", data, "sources", list)
        items = []
        for raw in sources:
            if not isinstance(raw, dict):
                raise _unreadable(request_id, "sources", raw)
            try:
                title = str(raw["title"])
                items.append(
                    ContextItem(
                        number=int(raw["number"]),
                        title=title,
                        section=str(raw.get("section") or ""),
                        text=str(raw["text"]),
                        document_id=self.title_to_id.get(title),
                    )
                )
            except (KeyError, TypeError, ValueError) as error:
                raise _unreadable(request_id, "sources", raw) from error
        return tuple(items)

    def _timeout_error(self) -> MeasurementError:
        return MeasurementError(
            f"Tiempo agotado ({self.timeout:g} s) esperando al backend en {self.base_url}{CHAT_PATH}; comprueba que "
            "está levantado en esa dirección (en Windows un puerto cerrado también acaba así) y que el modelo "
            "responde, o sube --turn-timeout"
        )

    def _unreachable(self, reason: object) -> MeasurementError:
        return MeasurementError(
            f"No se pudo conectar con el backend en {self.base_url} ({reason}); levanta el stack local "
            "(docker compose -f infra/docker-compose.yml up, o make up en shoplab-infra) o indica otra dirección con "
            "--base-url"
        )


def _read_events(response: Any, request_id: str):
    """The ``(name, data)`` of each server-sent event, with ``data`` already decoded from its one-line JSON."""
    name = "message"
    data_lines: list[str] = []
    for raw in response:
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data_lines:
                yield name, _decode(request_id, name, "\n".join(data_lines))
            name, data_lines = "message", []
        elif line.startswith("event:"):
            name = line[len("event:") :].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:") :].lstrip())
    if data_lines:  # a stream that ends without the blank line of its last event
        yield name, _decode(request_id, name, "\n".join(data_lines))


def _decode(request_id: str, name: str, text: str) -> Any:
    try:
        return json.loads(text)
    except ValueError as error:
        raise MeasurementError(
            f"Evento {name} ilegible en la petición {request_id}: no es JSON ({error})"
        ) from error


def _field(request_id: str, name: str, data: Any, key: str, kind: type) -> Any:
    if not isinstance(data, dict) or not isinstance(data.get(key), kind):
        raise _unreadable(request_id, name, data)
    return data[key]


def _unreadable(request_id: str, name: str, data: object) -> MeasurementError:
    return MeasurementError(f"Evento {name} ilegible en la petición {request_id}: no tiene la forma esperada ({data!r})")


def _http_error_message(request_id: str, error: urllib.error.HTTPError) -> str:
    """The status of a failed answer and, when the body is the typed error of the backend, its code."""
    code = ""
    try:
        body = json.loads(error.read().decode("utf-8"))
        if isinstance(body, dict) and body.get("code"):
            code = f" ({body['code']})"
    except (ValueError, OSError):
        pass
    return f"La petición {request_id} falló: el backend respondió {error.code}{code} a {CHAT_PATH}"
