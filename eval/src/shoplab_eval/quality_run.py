"""The answer quality measurement: asks the assistant, has the judge score each turn, builds the report (LAB-29).

It runs in two phases (research R5): first every turn of the assistant, one by one, and only then every call to the
judge. Two models (the chat and the judge) share the GPU of Ollama: alternating them case by case would load and unload
them all the time, and a broken assistant is found before spending a single call of the judge.

The rules that decide what a score *is* live here, as plain functions, so they are tested without a judge (research R6):

- an abstention (the answer only says it has no information) has nothing to check against the context, so its
  faithfulness is empty, not zero and not one;
- RAGAS cannot be called without a context, so an empty context is decided here: precision is 0 (nothing useful was
  delivered) and an answer that still affirms something has faithfulness 0 (no fragment can back it).

A failure of the assistant or of the judge stops the measurement with an error that names the case: it is never a score.
"""

import asyncio
import re
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace
from datetime import UTC, datetime

from shoplab_eval.chat_client import ChatClient, ChatTurn
from shoplab_eval.constants import DECIMALS, MAX_REQUEST_ID_LENGTH, REQUEST_ID_PREFIX, RUN_ID_PATTERN
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.langfuse_client import LangfuseStatus, ScoreToPublish, TraceScores, score_id_for, trace_id_for
from shoplab_eval.quality_judge import Judge, JudgeSettings
from shoplab_eval.quality_scores import CaseKind, CaseScores, QualityScopeSummary, classify_case, summarize_quality

_SAFE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
_CITATION = re.compile(r"\[\d+\]")
_RUN_ID = re.compile(RUN_ID_PATTERN)

Progress = Callable[[str], None]


def _silent(_: str) -> None:
    return


@dataclass(frozen=True)
class RunInfo:
    """What makes a result comparable with another (FR-018); the judge's key is never part of it."""

    run_id: str
    judge: JudgeSettings
    judge_underlying_model: str
    ragas_version: str
    abstention_prompt_version: str
    assistant_model: str
    embedding_model: str
    langfuse: str


@dataclass(frozen=True)
class QualityCaseResult:
    """One measured case: the turn the assistant gave, its scores and the Langfuse trace of the turn."""

    case: GoldenCase
    kind: CaseKind
    turn: ChatTurn
    scores: CaseScores
    trace_id: str
    langfuse: LangfuseStatus


@dataclass(frozen=True)
class QualityReport:
    """A finished measurement: everything the CSV files and the printed summary are made of.

    It exists only for a complete measurement, so nothing that looks like a result is ever written after a failure.
    ``attach_failures`` lists the cases whose scores did not reach their Langfuse trace: the files are written anyway
    (the scores cost hundreds of calls of the judge) and the command ends with a non-zero code (FR-014).
    """

    info: RunInfo
    results: tuple[QualityCaseResult, ...]
    summaries: tuple[QualityScopeSummary, ...]
    excluded: int
    attach_failures: tuple[str, ...] = ()


def new_run_id(now: datetime | None = None) -> str:
    """The default id of a run: the UTC moment it starts, which also keeps runs in order."""
    return (now or datetime.now(UTC)).strftime("%Y%m%dT%H%M%SZ")


def request_id_for(run_id: str, case_id: str) -> str:
    """``eval-<run>-<case>``: a safe ``X-Request-Id`` of the backend, from which the trace id is derived (ADR-0022)."""
    request_id = f"{REQUEST_ID_PREFIX}-{run_id}-{case_id}"
    if not _RUN_ID.match(run_id):
        raise ValueError(f"El identificador de ejecución {run_id!r} debe cumplir {RUN_ID_PATTERN}")
    if not _SAFE_ID.match(case_id) or len(request_id) > MAX_REQUEST_ID_LENGTH:
        raise ValueError(
            f"El identificador de petición {request_id!r} no es válido: solo letras, cifras, punto, guion y guion bajo, "
            f"hasta {MAX_REQUEST_ID_LENGTH} caracteres"
        )
    return request_id


def measure_quality(
    cases: Sequence[GoldenCase],
    chat: ChatClient,
    judge: Judge,
    info: RunInfo,
    progress: Progress = _silent,
    langfuse: TraceScores | None = None,
) -> QualityReport:
    """Asks the assistant every knowledge case, has the judge score each turn and attaches the scores to the traces.

    The conversation cases (courtesies and injection attempts, LAB-114) are neither asked nor scored: they have no
    context to judge, and they are counted as excluded. With ``langfuse``, the first turn is a probe: its trace has to
    arrive before the rest is asked, so a chat without traces fails after one question and not after the judge has
    spent hundreds of calls. Raises ``MeasurementError`` when the assistant, the judge or the probe fails (the message
    names the case); a score that cannot be attached is *not* an error here: it is listed in ``attach_failures``.
    """
    knowledge = [case for case in cases if classify_case(case) is not CaseKind.CONVERSATION]
    excluded_by_category = Counter(case.category for case in cases if case.is_conversation)

    turns = _ask_every_turn(knowledge, chat, info.run_id, progress, langfuse)
    scores = asyncio.run(_score_every_turn(knowledge, turns, judge, info.judge, progress))

    results = tuple(
        QualityCaseResult(
            case=case,
            kind=classify_case(case),
            turn=turn,
            scores=case_scores,
            trace_id=trace_id_for(turn.request_id),
            langfuse=LangfuseStatus.SKIPPED,
        )
        for case, turn, case_scores in zip(knowledge, turns, scores, strict=True)
    )
    failures: tuple[str, ...] = ()
    if langfuse is not None:
        results, failures = _attach_scores(results, langfuse, info, progress)
        info = replace(info, langfuse="partial" if failures else "attached")
    return QualityReport(
        info=info,
        results=results,
        summaries=summarize_quality([(result.case, result.scores) for result in results], excluded_by_category),
        excluded=sum(excluded_by_category.values()),
        attach_failures=failures,
    )


def _ask_every_turn(
    cases: Sequence[GoldenCase], chat: ChatClient, run_id: str, progress: Progress, langfuse: TraceScores | None
) -> list[ChatTurn]:
    turns = []
    for index, case in enumerate(cases, start=1):
        progress(f"[{index}/{len(cases)}] turno del asistente {case.id}")
        try:
            turns.append(chat.ask(case.question, request_id_for(run_id, case.id)))
        except MeasurementError as error:
            raise MeasurementError(f"Falló el turno del caso {case.id}: {error}") from error
        if index == 1 and langfuse is not None:
            progress(f"sonda de trazas: esperando la traza del caso {case.id}")
            try:
                langfuse.wait_for_trace(trace_id_for(turns[0].request_id))
            except MeasurementError as error:
                raise MeasurementError(f"La sonda de trazas falló en el caso {case.id}: {error}") from error
    return turns


def _attach_scores(
    results: Sequence[QualityCaseResult], langfuse: TraceScores, info: RunInfo, progress: Progress
) -> tuple[tuple[QualityCaseResult, ...], tuple[str, ...]]:
    """Publishes the scores with a value, case by case. A failure marks that case and goes on with the others."""
    attached: list[QualityCaseResult] = []
    failures: list[str] = []
    for index, result in enumerate(results, start=1):
        progress(f"[{index}/{len(results)}] langfuse {result.case.id}")
        try:
            langfuse.wait_for_trace(result.trace_id)  # a score without its trace is of no use to debug an answer
            for score in _scores_of(result, info):
                langfuse.publish_score(score)
            status = LangfuseStatus.ATTACHED
        except MeasurementError as error:
            failures.append(f"{result.case.id}: {error}")
            status = LangfuseStatus.NOT_ATTACHED
        attached.append(replace(result, langfuse=status))
    return tuple(attached), tuple(failures)


def _scores_of(result: QualityCaseResult, info: RunInfo) -> list[ScoreToPublish]:
    """One numeric score per metric with a value: an empty score is never published."""
    metadata = {
        "run_id": info.run_id,
        "case_id": result.case.id,
        "judge_model": info.judge.model,
        "judge_underlying_model": info.judge_underlying_model,
    }
    comment = f"judge={info.judge.model}; ragas={info.ragas_version}"
    return [
        ScoreToPublish(
            score_id_for(info.run_id, result.case.id, metric), result.trace_id, metric, value, comment, metadata
        )
        for metric, value in result.scores.by_metric().items()
        if value is not None
    ]


async def _score_every_turn(
    cases: Sequence[GoldenCase], turns: Sequence[ChatTurn], judge: Judge, settings: JudgeSettings, progress: Progress
) -> list[CaseScores]:
    gate = asyncio.Semaphore(settings.concurrency)
    finished = 0

    async def score(case: GoldenCase, turn: ChatTurn) -> CaseScores:
        nonlocal finished
        async with gate:
            try:
                scores = await score_case(case, turn, judge, settings.max_input_chars)
            except MeasurementError as error:
                raise MeasurementError(f"Falló el juez en el caso {case.id}: {error}") from error
        finished += 1
        progress(f"[{finished}/{len(cases)}] juez {case.id}")
        return scores

    return list(await asyncio.gather(*(score(case, turn) for case, turn in zip(cases, turns, strict=True))))


def answer_has_content(answer: str) -> bool:
    """Whether the answer says anything: citation markers such as ``[2]`` and punctuation alone do not.

    A small chat model sometimes ends a turn with only a marker. It answers nothing and claims nothing, so there is
    nothing for the faithfulness to check: that must not score as a perfect, faithful answer.
    """
    return any(character.isalnum() for character in _CITATION.sub("", answer))


def judge_input_size(case: GoldenCase, turn: ChatTurn) -> int:
    """Characters of everything one case sends to the judge: question, answer, delivered context and reference."""
    context = sum(len(item.text) for item in turn.context)
    return len(case.question) + len(turn.answer) + context + len(case.reference_answer)


async def score_case(case: GoldenCase, turn: ChatTurn, judge: Judge, max_input_chars: int) -> CaseScores:
    """The scores of one turn, following the rules of the module docstring (research R6)."""
    size = judge_input_size(case, turn)
    if size > max_input_chars:
        raise MeasurementError(
            f"Lo que se enviaría al juez ({size} caracteres) supera --max-input-chars ({max_input_chars}): sube el "
            "límite o acorta la respuesta, el contexto o la referencia; no se cuenta como una puntuación"
        )
    kind = classify_case(case)
    contexts = [item.text for item in turn.context]
    if not answer_has_content(turn.answer):
        return await _score_answer_without_content(case, kind, contexts, judge)
    abstained = await judge.abstains(case.question, turn.answer)

    faithfulness: float | None = None
    if not abstained:
        faithfulness = 0.0 if not contexts else await judge.faithfulness(case.question, turn.answer, contexts)

    answer_relevancy: float | None = None
    context_precision: float | None = None
    if kind is CaseKind.ANSWERED:
        answer_relevancy = await judge.answer_relevancy(case.question, turn.answer)
        if not contexts:
            context_precision = 0.0
        else:
            context_precision = await judge.context_precision(case.question, case.reference_answer, contexts)
    return CaseScores(_rounded(faithfulness), _rounded(answer_relevancy), _rounded(context_precision), abstained)


async def _score_answer_without_content(
    case: GoldenCase, kind: CaseKind, contexts: list[str], judge: Judge
) -> CaseScores:
    """No claim to check (faithfulness is empty) and nothing answered (relevancy 0); the context is judged on its own."""
    answered = kind is CaseKind.ANSWERED
    precision: float | None = None
    if answered:
        precision = await judge.context_precision(case.question, case.reference_answer, contexts) if contexts else 0.0
    return CaseScores(None, 0.0 if answered else None, _rounded(precision), abstained=False)


def _rounded(value: float | None) -> float | None:
    """A score with the decimals the CSV shows, so the files, the means and the scores of Langfuse are the same numbers.

    Rounding only at the end would let a spreadsheet and the summary disagree in the fourth decimal (a mean of rounded
    cells is not the rounded mean) and Langfuse would hold digits the CSV does not show (SC-004 and SC-005).
    """
    return None if value is None else round(value, DECIMALS)
