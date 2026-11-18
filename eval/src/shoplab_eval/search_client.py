"""HTTP client of the knowledge search and of the document catalog (research R1 and R9).

It uses only the standard library: it is 70 sequential reads, and a retry would hide exactly the failures the
measurement has to report. The search is the real one of the backend; nothing here approximates it. Every failure is a
``MeasurementError`` that names the cause, so a broken environment is never mistaken for a search that finds nothing.
"""

import json
import urllib.error
import urllib.request
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlencode

from shoplab_eval.constants import DEFAULT_TIMEOUT_SECONDS, DOCUMENTS_PATH, SEARCH_PATH
from shoplab_eval.errors import MeasurementError


@dataclass(frozen=True)
class SearchHit:
    """A chunk the search returned. Only what the measurement uses is kept: the text is never stored."""

    source: str
    score: float


@dataclass(frozen=True)
class SearchResponse:
    """What the search answered: the embedding model it compared with and the chunks, closest first."""

    model: str
    hits: tuple[SearchHit, ...]


@dataclass(frozen=True)
class IndexedDocument:
    """A document of the knowledge base catalog: its file name and its indexing state (``INDEXED``, ``PENDING``…)."""

    name: str
    status: str


class Searcher(Protocol):
    """What the measurement needs from the backend; the tests replace it with a fake that has no HTTP."""

    def search(self, query: str, limit: int) -> SearchResponse: ...

    def list_documents(self) -> Sequence[IndexedDocument]: ...


class KnowledgeSearchClient:
    """The real ``Searcher``: ``GET /api/knowledge/search`` and ``GET /api/documents`` of the backend."""

    def __init__(self, base_url: str, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def search(self, query: str, limit: int) -> SearchResponse:
        url, body = self._get_json(SEARCH_PATH, {"query": query, "limit": limit})
        try:
            hits = tuple(SearchHit(str(item["source"]), float(item["score"])) for item in body["results"])
            return SearchResponse(str(body["model"]), hits)
        except (KeyError, TypeError, ValueError) as error:
            raise _unreadable(url, error) from error

    def list_documents(self) -> list[IndexedDocument]:
        url, body = self._get_json(DOCUMENTS_PATH, {})
        try:
            return [IndexedDocument(str(item["name"]), str(item["status"])) for item in body]
        except (KeyError, TypeError, ValueError) as error:
            raise _unreadable(url, error) from error

    def _get_json(self, path: str, params: dict[str, Any]) -> tuple[str, Any]:
        """The URL asked and the JSON of its answer; any failure is a ``MeasurementError`` that names the cause."""
        url = f"{self.base_url}{path}" + (f"?{urlencode(params)}" if params else "")
        request = urllib.request.Request(url, headers={"Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return url, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise MeasurementError(_http_error_message(path, error)) from error
        except TimeoutError as error:
            raise self._timeout_error(path) from error
        except urllib.error.URLError as error:
            if isinstance(error.reason, TimeoutError):
                raise self._timeout_error(path) from error
            raise self._unreachable(error.reason) from error
        except OSError as error:
            raise self._unreachable(error) from error
        except ValueError as error:  # json.JSONDecodeError and UnicodeDecodeError
            raise _unreadable(url, error) from error

    def _timeout_error(self, path: str) -> MeasurementError:
        return MeasurementError(
            f"Tiempo agotado ({self.timeout:g} s) esperando al backend en {self.base_url}{path}; comprueba que está "
            "levantado en esa dirección (en Windows un puerto cerrado también acaba así) y que el modelo de "
            "embeddings responde, o sube --timeout"
        )

    def _unreachable(self, reason: object) -> MeasurementError:
        return MeasurementError(
            f"No se pudo conectar con el backend en {self.base_url} ({reason}); levanta el stack local "
            "(docker compose -f infra/docker-compose.yml up) o indica otra dirección con --base-url"
        )


def _http_error_message(path: str, error: urllib.error.HTTPError) -> str:
    """The status of a failed answer and, when the body is the typed error of the backend, its code."""
    code = ""
    try:
        body = json.loads(error.read().decode("utf-8"))
        if isinstance(body, dict) and body.get("code"):
            code = f" ({body['code']})"
    except (ValueError, OSError):
        pass
    return f"El backend respondió {error.code}{code} a {path}"


def _unreadable(url: str, error: Exception) -> MeasurementError:
    return MeasurementError(f"Respuesta ilegible de {url}: no tiene la forma esperada ({error!r})")
