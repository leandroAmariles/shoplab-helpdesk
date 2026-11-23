"""Paraphrases and translations of published examples, generated with the local model through the LAB-84 gateway.

The gateway speaks the OpenAI-compatible API; ``judge`` is a logical model name, so changing the model is a change of the
gateway or of ``SHOPLAB_ML_LLM_MODEL``, not of this code (research R5). The HTTP call is injectable (``post``) so tests
never touch the network, like ``shoplab_eval``.

Everything this module returns is a **draft**: automatic filters drop the unusable variants, and a person still reviews
the rest (``decision`` stays empty). The API key comes from the environment, has no default and is never printed.
"""

import json
import os
import urllib.error
import urllib.request
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

from intent_dataset.constants import PARAPHRASE
from intent_dataset.dataset import Example
from intent_dataset.errors import ConfigurationError, ParaphraseError
from intent_dataset.masking import mask_text
from intent_dataset.review import Candidate
from intent_dataset.text import normalize_text, tokens

PARAPHRASE_PROMPT_VERSION = "v1"
API_KEY_VARIABLE = "SHOPLAB_ML_LLM_API_KEY"
BASE_URL_VARIABLE = "SHOPLAB_ML_LLM_BASE_URL"
MODEL_VARIABLE = "SHOPLAB_ML_LLM_MODEL"
DEFAULT_BASE_URL = "http://localhost:4000/v1"
DEFAULT_MODEL = "judge"
DEFAULT_TEMPERATURE = 0.7
TIMEOUT_SECONDS = 120
MIN_VARIANT_CHARS = 3
MAX_VARIANT_CHARS = 280
MAX_SIMILARITY = 0.85  # Jaccard of tokens against the original, in the same language

_LANGUAGE_NAMES = {"es": "español", "pt": "portugués"}
_OTHER_LANGUAGE = {"es": "pt", "pt": "es"}
_INTENTS = {
    "politica": "pregunta por una regla general de la tienda (pagos, privacidad, devoluciones, envíos, garantías)",
    "producto": "pregunta o comenta las características de un artículo o del catálogo",
    "pedido": "se refiere a un pedido concreto del cliente (estado, plazo, cancelación o problema con lo recibido)",
    "escalado": "pide o exige hablar con una persona, o amenaza con acciones si no se resuelve",
    "charla": "saludo, agradecimiento, despedida, pregunta ajena a la tienda o intento de cambiar las instrucciones",
}
_SYSTEM = (
    "Eres un asistente que reescribe mensajes de clientes de una tienda en línea para construir un dataset de "
    "clasificación. Responde SOLO con un array JSON de cadenas, sin texto antes ni después."
)

Post = Callable[[str, dict[str, str], dict[str, Any]], dict[str, Any]]


@dataclass(frozen=True)
class LlmSettings:
    base_url: str
    model: str
    api_key: str = field(repr=False)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "LlmSettings":
        source = os.environ if env is None else env
        api_key = source.get(API_KEY_VARIABLE) or ""
        if not api_key:
            raise ConfigurationError(
                f"Falta la variable {API_KEY_VARIABLE}: es la clave virtual `eval` del gateway de modelos "
                "(infra/.env). Defínela en el entorno; no tiene valor por defecto."
            )
        return cls(
            base_url=(source.get(BASE_URL_VARIABLE) or DEFAULT_BASE_URL).rstrip("/"),
            model=source.get(MODEL_VARIABLE) or DEFAULT_MODEL,
            api_key=api_key,
        )


def _http_post(url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310 - fixed http(s) gateway
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise ParaphraseError(f"El gateway de modelos respondió {error.code}. Revisa la clave y el modelo.") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ParaphraseError(
            "No se pudo llamar al gateway de modelos. Levántalo con "
            "`docker compose -f infra/docker-compose.yml up -d --wait` o revisa SHOPLAB_ML_LLM_BASE_URL."
        ) from error
    except json.JSONDecodeError as error:
        raise ParaphraseError("El gateway de modelos devolvió una respuesta que no es JSON.") from error


class LlmClient:
    """Asks the gateway for one chat completion; the only place that knows the HTTP shape."""

    def __init__(self, settings: LlmSettings, post: Post | None = None) -> None:
        self._settings = settings
        self._post = post or _http_post

    def complete(
        self, system: str, user: str, *, temperature: float = DEFAULT_TEMPERATURE, seed: int | None = None
    ) -> str:
        payload: dict[str, Any] = {
            "model": self._settings.model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": temperature,
        }
        if seed is not None:
            payload["seed"] = seed
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self._settings.api_key}"}
        answer = self._post(f"{self._settings.base_url}/chat/completions", headers, payload)
        try:
            return str(answer["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError) as error:
            raise ParaphraseError("La respuesta del gateway no tiene el formato esperado (choices).") from error


@dataclass
class ParaphraseResult:
    candidates: list[Candidate] = field(default_factory=list)
    discarded: Counter[str] = field(default_factory=Counter)


def _prompt(parent: Example, language: str, count: int) -> str:
    intent = _INTENTS[parent.label]
    return (
        f"Mensaje original ({_LANGUAGE_NAMES[parent.language]}): «{parent.text}»\n"
        f"Intención del mensaje: {parent.label} — {intent}.\n"
        f"Escribe {count} variantes en {_LANGUAGE_NAMES[language]} que conserven exactamente esa intención, cambien las "
        "palabras y la estructura, y suenen como las escribiría un cliente real (informal, con alguna falta).\n"
        "Reglas: no incluyas nombres de personas, correos, teléfonos, direcciones ni números de pedido; "
        "no inventes datos personales; no cambies la intención; no repitas el mensaje original.\n"
        f"Responde SOLO con un array JSON de {count} cadenas."
    )


def _parse_variants(answer: str) -> list[str] | None:
    start, end = answer.find("["), answer.rfind("]")
    if start < 0 or end < start:
        return None
    try:
        parsed = json.loads(answer[start : end + 1])
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
        return None
    return parsed


def _jaccard(first: frozenset[str], second: frozenset[str]) -> float:
    union = first | second
    return len(first & second) / len(union) if union else 1.0


def _accept(variant: str, parent: Example, language: str, kept: set[str]) -> str | None:
    """The reason to discard a variant, or ``None`` if it is usable."""
    text = variant.strip()
    if not MIN_VARIANT_CHARS <= len(text) <= MAX_VARIANT_CHARS:
        return "longitud"
    key = normalize_text(text)
    if key == normalize_text(parent.text):
        return "igual_al_original"
    if key in kept:
        return "duplicada"
    if language == parent.language and _jaccard(tokens(text), tokens(parent.text)) >= MAX_SIMILARITY:
        return "similar"
    if mask_text(text) != text:
        return "dato_personal"  # the model invented personal data
    return None


def generate_paraphrases(
    parents: list[Example], client: LlmClient, *, per_parent: int, translate: bool, seed: int
) -> ParaphraseResult:
    """Drafts of paraphrases (and one translation per parent when ``translate``) as candidates awaiting review."""
    result = ParaphraseResult()
    for index, parent in enumerate(parents):
        requests = []
        if per_parent > 0:
            requests.append((parent.language, per_parent))
        if translate:
            requests.append((_OTHER_LANGUAGE[parent.language], 1))
        kept: set[str] = set()
        for language, count in requests:
            answer = client.complete(_SYSTEM, _prompt(parent, language, count), seed=seed + index)
            variants = _parse_variants(answer)
            if variants is None:
                result.discarded["json_invalido"] += 1
                continue
            number = 0
            for variant in variants:
                reason = _accept(variant, parent, language, kept)
                if reason:
                    result.discarded[reason] += 1
                    continue
                kept.add(normalize_text(variant))
                number += 1
                result.candidates.append(
                    Candidate(
                        candidate_id=f"P{parent.id}{language}{number}",
                        source=PARAPHRASE,
                        group_id=parent.group_id,
                        parent_id=parent.id,
                        golden_id=parent.golden_id or "",
                        language=language,
                        text=variant.strip(),
                        proposed_label=parent.label,
                        hint="",
                        decision="",
                        label="",
                        text_edit="",
                        pii_checked="",
                        note="",
                    )
                )
    return result
