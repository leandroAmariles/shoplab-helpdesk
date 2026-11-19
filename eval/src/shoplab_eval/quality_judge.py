"""The judge of the answer quality measurement: the only module that knows RAGAS and the OpenAI client (specs/LAB-29).

The judge is a model of the LiteLLM gateway (LAB-84) asked through its OpenAI-compatible API: ``judge`` is a logical
name, so changing the model that judges is a change of the gateway, not of this code (research R2). RAGAS computes the
three metrics with it; the abstention check is a small prompt of ours (RAGAS would score «no tengo información» as an
unsupported claim, research R6).

This module only *translates*. It calls the metrics with the data of a turn, turns ``NaN`` into an empty score and turns
every failure of the judge into a ``MeasurementError`` that names the metric: a judge that cannot judge is never a zero.
The rules about an empty context or an abstention live in ``quality_run`` so they can be tested without a judge.
"""

import asyncio
import importlib.metadata
import json
import math
import os
import urllib.error
import urllib.request
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from shoplab_eval.constants import (
    DEFAULT_CONCURRENCY,
    DEFAULT_JUDGE_BASE_URL,
    DEFAULT_JUDGE_MODEL,
    DEFAULT_JUDGE_TEMPERATURE,
    DEFAULT_JUDGE_TIMEOUT_SECONDS,
    DEFAULT_MAX_INPUT_CHARS,
    EMBEDDINGS_MODEL,
    JUDGE_API_KEY_ENV,
    JUDGE_BASE_URL_ENV,
    JUDGE_MODEL_ENV,
    JUDGE_SEED,
    MAX_CONCURRENCY,
    MAX_JUDGE_TEMPERATURE,
    METRIC_ANSWER_RELEVANCY,
    METRIC_CONTEXT_PRECISION,
    METRIC_FAITHFULNESS,
    MIN_CONCURRENCY,
    MODEL_INFO_PATH,
    MODELS_PATH,
    UNKNOWN,
)
from shoplab_eval.errors import MeasurementError

# Longest piece of an error of the judge that is shown: enough to name the cause, short enough not to dump a response.
_MAX_ERROR_TEXT = 200


@dataclass(frozen=True)
class JudgeSettings:
    """How the judge is asked. The API key is not here: it comes from the environment and is never stored or shown."""

    model: str = DEFAULT_JUDGE_MODEL
    base_url: str = DEFAULT_JUDGE_BASE_URL
    temperature: float = DEFAULT_JUDGE_TEMPERATURE
    timeout: float = DEFAULT_JUDGE_TIMEOUT_SECONDS
    max_input_chars: int = DEFAULT_MAX_INPUT_CHARS
    concurrency: int = DEFAULT_CONCURRENCY


class Judge(Protocol):
    """What the measurement needs from the judge; the tests replace it with a double that has no model."""

    async def abstains(self, question: str, answer: str) -> bool: ...

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None: ...

    async def answer_relevancy(self, question: str, answer: str) -> float | None: ...

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None: ...


def abstention_decision(abstains: bool, concrete_claims: Sequence[str]) -> bool:
    """An answer that lists any concrete claim of the store is not an abstention, whatever the judge's flag says.

    The first version asked only for a yes or no and the judge took a partial answer («puedes combinarlo con un vale,
    pero no se indica lo de las cuotas») for an abstention. Asking for the claims first and enforcing the consistency
    here makes the decision follow from what the judge itself listed.
    """
    return bool(abstains) and not [claim for claim in concrete_claims if claim.strip()]


def abstention_prompt(question: str, answer: str) -> str:
    """The question of the abstention check. Bump ``ABSTENTION_PROMPT_VERSION`` whenever this text changes."""
    return (
        "Eres un revisor de las respuestas de un asistente de atención al cliente de una tienda.\n"
        "Primero, en concrete_claims, lista cada dato concreto de la tienda que la RESPUESTA da por cierto: un plazo, "
        "un importe, una condición, una política, un paso o algo que sí se puede hacer. Decir que no tiene la "
        "información, que no se indica algo o que consulte con atención al cliente NO es un dato concreto.\n"
        "Después, en abstains, responde true solo si concrete_claims está vacía: la respuesta se limita a decir que no "
        "tiene la información o que no puede ayudar. Si da por cierto algo concreto, aunque también diga que otra "
        "parte no se indica, abstains es false.\n\n"
        f"PREGUNTA: {question}\n\n"
        f"RESPUESTA: {answer}\n"
    )


class RagasJudge:
    """The real ``Judge``: RAGAS metrics and the abstention check, behind the timeout and the error rules.

    The HTTP client of the gateway is asynchronous and stays tied to the event loop where it is first used, so one
    judge is used inside a single ``asyncio.run`` (the measurement does it): build a new one for another loop.
    """

    def __init__(
        self,
        settings: JudgeSettings,
        *,
        faithfulness: Any,
        answer_relevancy: Any,
        context_precision: Any,
        abstention: Callable[[str], Awaitable[bool]],
        redact: Sequence[str] = (),
    ) -> None:
        self._settings = settings
        self._faithfulness = faithfulness
        self._answer_relevancy = answer_relevancy
        self._context_precision = context_precision
        self._abstention = abstention
        self._redact = tuple(secret for secret in redact if secret)

    def __repr__(self) -> str:
        return f"RagasJudge(model={self._settings.model!r})"

    async def abstains(self, question: str, answer: str) -> bool:
        try:
            return bool(await asyncio.wait_for(self._abstention(abstention_prompt(question, answer)), self._timeout))
        except TimeoutError:
            raise self._timed_out("la comprobación de abstención") from None
        except MeasurementError:
            raise
        except Exception as error:  # noqa: BLE001 - whatever the judge raises must name the check and stay readable
            raise MeasurementError(
                f"La comprobación de abstención del juez falló: {self._describe(error)}"
            ) from None

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return await self._score(
            METRIC_FAITHFULNESS,
            self._faithfulness,
            user_input=question,
            response=answer,
            retrieved_contexts=list(contexts),
        )

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return await self._score(METRIC_ANSWER_RELEVANCY, self._answer_relevancy, user_input=question, response=answer)

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return await self._score(
            METRIC_CONTEXT_PRECISION,
            self._context_precision,
            user_input=question,
            reference=reference,
            retrieved_contexts=list(contexts),
        )

    @property
    def _timeout(self) -> float:
        return self._settings.timeout

    async def _score(self, metric: str, scorer: Any, **data: Any) -> float | None:
        try:
            result = await asyncio.wait_for(scorer.ascore(**data), self._timeout)
        except TimeoutError:
            raise self._timed_out(metric) from None
        except MeasurementError:
            raise
        except Exception as error:  # noqa: BLE001 - whatever the judge raises must name the metric and stay readable
            raise MeasurementError(f"El juez falló calculando {metric}: {self._describe(error)}") from None
        return _number(getattr(result, "value", result))

    def _timed_out(self, what: str) -> MeasurementError:
        return MeasurementError(
            f"Tiempo agotado ({self._timeout:g} s) esperando al juez en {what}; comprueba que el gateway y el modelo "
            "del juez responden, o sube --judge-timeout"
        )

    def _describe(self, error: Exception) -> str:
        """``Type: message`` on one line, without the API key and cut short."""
        text = " ".join(str(error).split())
        for secret in self._redact:
            text = text.replace(secret, "***")
        return f"{type(error).__name__}: {text[:_MAX_ERROR_TEXT]}"


def _number(value: object) -> float | None:
    """The score as a float, or ``None`` when RAGAS has no number to give (it answers ``NaN``)."""
    if value is None:
        return None
    number = float(value)  # type: ignore[arg-type]
    return None if math.isnan(number) else number


def build_ragas_judge(settings: JudgeSettings, api_key: str) -> RagasJudge:
    """Builds the judge over the gateway. RAGAS and the OpenAI client are imported here, after its telemetry is off."""
    # RAGAS reports usage to its own servers unless told not to; it must be off before the library is imported.
    os.environ["RAGAS_DO_NOT_TRACK"] = "true"
    try:
        from openai import AsyncOpenAI
        from pydantic import BaseModel, Field
        from ragas.embeddings.base import embedding_factory
        from ragas.llms import llm_factory
        from ragas.metrics.collections import AnswerRelevancy, ContextPrecision, Faithfulness
    except ImportError as error:
        raise MeasurementError(
            "Falta la biblioteca de evaluación (RAGAS): ejecuta `uv sync` en eval/ para instalar el grupo `quality`"
        ) from error

    client = AsyncOpenAI(base_url=settings.base_url, api_key=api_key, timeout=settings.timeout, max_retries=0)
    llm = llm_factory(settings.model, client=client, temperature=settings.temperature, seed=JUDGE_SEED)
    embeddings = embedding_factory("openai", EMBEDDINGS_MODEL, client=client, interface="modern")

    class Abstention(BaseModel):
        # The claims come first: the model has to list them before it decides, which is what makes the decision reliable.
        concrete_claims: list[str] = Field(description="datos concretos de la tienda que la respuesta da por ciertos")
        abstains: bool = Field(description="true solo si concrete_claims está vacía")

    async def ask_abstention(prompt: str) -> bool:
        result = await llm.agenerate(prompt, Abstention)
        return abstention_decision(result.abstains, result.concrete_claims)

    return RagasJudge(
        settings,
        faithfulness=Faithfulness(llm=llm),
        answer_relevancy=AnswerRelevancy(llm=llm, embeddings=embeddings),
        context_precision=ContextPrecision(llm=llm),
        abstention=ask_abstention,
        redact=(api_key,),
    )


def ragas_version() -> str:
    """The installed version of RAGAS, recorded in every result because scores are only comparable with the same one."""
    try:
        return importlib.metadata.version("ragas")
    except importlib.metadata.PackageNotFoundError:
        return UNKNOWN



def resolve_judge_settings(args: Any, env: Mapping[str, str]) -> JudgeSettings:
    """The settings of the judge: a flag wins over the environment and the environment over the default (FR-007).

    ``args`` carries ``None`` for an option that was not given. Raises ``ValueError`` naming the option and what is
    accepted, so a wrong value is a usage error found before anything is asked of the assistant or the judge.
    """
    model = _first(args.judge_model, env.get(JUDGE_MODEL_ENV), DEFAULT_JUDGE_MODEL).strip()
    if not model:
        raise ValueError(f"--judge-model (o {JUDGE_MODEL_ENV}) no puede estar vacío: indica el nombre del gateway")
    base_url = _first(args.judge_base_url, env.get(JUDGE_BASE_URL_ENV), DEFAULT_JUDGE_BASE_URL).strip()
    if not base_url.startswith(("http://", "https://")):
        raise ValueError(f"--judge-base-url {base_url!r} no es válido: debe empezar por http:// o https://")
    temperature = _first(args.judge_temperature, None, DEFAULT_JUDGE_TEMPERATURE)
    if not 0 <= temperature <= MAX_JUDGE_TEMPERATURE:
        raise ValueError(
            f"--judge-temperature {temperature} no es válido: debe estar entre 0 y {MAX_JUDGE_TEMPERATURE:g}"
        )
    timeout = _first(args.judge_timeout, None, DEFAULT_JUDGE_TIMEOUT_SECONDS)
    if timeout <= 0:
        raise ValueError(f"--judge-timeout {timeout} no es válido: debe ser mayor que 0 (segundos)")
    max_input_chars = _first(args.max_input_chars, None, DEFAULT_MAX_INPUT_CHARS)
    if max_input_chars <= 0:
        raise ValueError(f"--max-input-chars {max_input_chars} no es válido: debe ser mayor que 0")
    concurrency = _first(args.concurrency, None, DEFAULT_CONCURRENCY)
    if not MIN_CONCURRENCY <= concurrency <= MAX_CONCURRENCY:
        raise ValueError(
            f"--concurrency {concurrency} no es válido: debe ser un entero entre {MIN_CONCURRENCY} y {MAX_CONCURRENCY}"
        )
    return JudgeSettings(model, base_url, temperature, timeout, max_input_chars, concurrency)


def _first(flag: Any, environment: Any, default: Any) -> Any:
    if flag is not None:
        return flag
    return environment if environment is not None else default


def read_judge_api_key(env: Mapping[str, str]) -> str:
    """The key of the judge comes from the environment only: with no option for it, it never shows in a command."""
    key = env.get(JUDGE_API_KEY_ENV, "").strip()
    if not key:
        raise ValueError(
            f"Falta la clave del juez: define la variable {JUDGE_API_KEY_ENV} (la clave virtual `eval` del gateway; "
            "en local es sk-shoplab-eval-local, ver infra/.env.example)"
        )
    return key


class GatewayClient:
    """What the measurement asks the gateway before it starts: is it there, does the key reach the judge and what is
    the real model behind the logical name. It uses the standard library and never prints the key."""

    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._api_key = api_key
        self.timeout = timeout

    def check_ready(self, model: str) -> None:
        """Raises ``MeasurementError`` naming the gateway, the key or the model when the judge cannot be asked."""
        available = {
            str(entry.get("id")) for entry in self._get(f"{self.base_url}{MODELS_PATH}").get("data", [])
        }
        if model not in available:
            raise MeasurementError(
                f"El gateway {self.base_url} no deja a esta clave llamar al modelo {model!r} del juez; modelos "
                f"disponibles: {', '.join(sorted(available)) or 'ninguno'}"
            )
        if EMBEDDINGS_MODEL not in available:
            raise MeasurementError(
                f"El gateway {self.base_url} no deja a esta clave llamar al modelo {EMBEDDINGS_MODEL!r}, que la "
                f"relevancia de la respuesta necesita; modelos disponibles: {', '.join(sorted(available)) or 'ninguno'}"
            )

    def underlying_model(self, model: str) -> str | None:
        """The real model behind a logical name (``litellm_params.model``), or ``None`` when it cannot be read.

        It is only a label for the result, so any failure is ``None`` and not an error: the key of the evaluation may
        not be allowed to read the model info, and a result without the label is still a result.
        """
        root = self.base_url.removesuffix("/v1")
        try:
            data = self._get(f"{root}{MODEL_INFO_PATH}").get("data", [])
            for entry in data:
                if entry.get("model_name") == model:
                    real = entry["litellm_params"]["model"]
                    return str(real) if real else None
        except (MeasurementError, AttributeError, KeyError, TypeError):
            return None
        return None

    def _get(self, url: str) -> dict[str, Any]:
        request = urllib.request.Request(
            url, headers={"Authorization": f"Bearer {self._api_key}", "Accept": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise MeasurementError(
                    f"El gateway {self.base_url} rechazó la clave del juez ({error.code}): comprueba la variable "
                    f"{JUDGE_API_KEY_ENV} (la clave virtual `eval`)"
                ) from None
            raise MeasurementError(f"El gateway {self.base_url} respondió {error.code} a {url}") from None
        except (TimeoutError, urllib.error.URLError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise MeasurementError(
                f"No se pudo conectar con el gateway en {self.base_url} ({reason}); levanta el stack local "
                "(docker compose -f infra/docker-compose.yml up) o indica otro con --judge-base-url"
            ) from None
        except ValueError:
            raise MeasurementError(f"Respuesta ilegible del gateway en {url}: no es JSON") from None
        if not isinstance(body, dict):
            raise MeasurementError(f"Respuesta ilegible del gateway en {url}: no tiene la forma esperada")
        return body
