"""What the backend receives to save a run in the evaluation history (specs/LAB-31, contracts/eval-runs-api.md).

Everything here is a pure function of the reports of the two measurements and of a few facts the command gathers (the
settings the assistant uses, the moments of the run, the commit): no network and no clock, so the translation is tested
without a backend, a model or a database (FR-019). The payload never carries the text of an answer or of a fragment: the
trace of Langfuse holds them, and ``trace_id`` points to it (FR-009).
"""

import hashlib
import re
import subprocess
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from shoplab_eval.constants import (
    COMMIT_PATTERN,
    DECIMALS,
    EVALUATION_LIBRARY,
    GIT_TIMEOUT_SECONDS,
    JUDGE_SEED,
    RUN_KIND_QUALITY,
    RUN_KIND_RETRIEVAL,
    UNKNOWN,
    UNKNOWN_DOCUMENT,
)
from shoplab_eval.errors import StoreError
from shoplab_eval.golden import GoldenCase
from shoplab_eval.prompt_selection import PromptChoice
from shoplab_eval.quality_run import QualityReport
from shoplab_eval.quality_scores import applicable_metrics
from shoplab_eval.retrieval_run import RetrievalReport

_COMMIT = re.compile(COMMIT_PATTERN)
LANGFUSE = "LANGFUSE"


@dataclass(frozen=True)
class ServedPrompt:
    """The version of a prompt the assistant serves now; ``version`` is ``None`` for the packaged default text."""

    name: str
    version: int | None
    source: str


@dataclass(frozen=True)
class AssistantSettings:
    """What the assistant uses at one moment: how it retrieves context and which prompt versions it serves."""

    top_k: int
    similarity_threshold: float
    max_context_characters: int
    prompts: tuple[ServedPrompt, ...]

    def retrieval_config(self) -> dict[str, Any]:
        return {
            "top_k": self.top_k,
            "similarity_threshold": self.similarity_threshold,
            "max_context_characters": self.max_context_characters,
        }


def parse_settings(body: Any) -> AssistantSettings:
    """The answer of ``GET /api/eval/settings`` as settings; a different shape is a ``StoreError``."""
    try:
        retrieval = body["retrieval"]
        prompts = tuple(
            ServedPrompt(
                name=str(item["name"]),
                version=None if item.get("version") is None else int(item["version"]),
                source=str(item["source"]),
            )
            for item in body["prompts"]
        )
        return AssistantSettings(
            top_k=int(retrieval["top_k"]),
            similarity_threshold=float(retrieval["similarity_threshold"]),
            max_context_characters=int(retrieval["max_context_characters"]),
            prompts=prompts,
        )
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise StoreError(
            f"La configuración del asistente que devolvió el backend no tiene la forma esperada ({error!r}); "
            "¿el backend es de una versión sin el historial de evaluación?"
        ) from error


def merge_prompts(settings: AssistantSettings, pinned: Iterable[PromptChoice]) -> list[dict[str, Any]]:
    """The prompts of the run: the version the backend serves, except those the run fixed in its requests."""
    fixed = {choice.name: choice.version for choice in pinned}
    merged: list[dict[str, Any]] = []
    for prompt in settings.prompts:
        if prompt.name in fixed:
            merged.append({"name": prompt.name, "version": fixed[prompt.name], "source": LANGFUSE, "pinned": True})
        else:
            merged.append(
                {"name": prompt.name, "version": prompt.version, "source": prompt.source, "pinned": False}
            )
    return merged


def settings_drift(start: AssistantSettings, end: AssistantSettings, pinned_names: Iterable[str]) -> list[str]:
    """What changed in the assistant between two readings, ignoring the prompts the run fixed itself (R6).

    A change means some turns were answered with one configuration and the rest with another, so the run is not valid.
    """
    ignored = set(pinned_names)
    changes = [
        f"retrieval.{field}"
        for field in ("top_k", "similarity_threshold", "max_context_characters")
        if getattr(start, field) != getattr(end, field)
    ]
    before = {prompt.name: prompt for prompt in start.prompts if prompt.name not in ignored}
    after = {prompt.name: prompt for prompt in end.prompts if prompt.name not in ignored}
    for name in sorted(before.keys() | after.keys()):
        if before.get(name) != after.get(name):
            changes.append(f"prompt {name}")
    return changes


def dataset_hash(path: Path) -> str:
    """The SHA-256 of the bytes of the dataset file, which is what the run measured."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_commit(directory: Path) -> str | None:
    """The commit of the repository that holds ``directory``, or ``None`` when it cannot be known (never invented)."""
    try:
        done = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=directory,
            capture_output=True,
            text=True,
            timeout=GIT_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    commit = done.stdout.strip()
    return commit if done.returncode == 0 and _COMMIT.match(commit) else None


def iso_utc(moment: datetime) -> str:
    """``2026-11-21T14:02:11.000Z``: the moment in UTC with the ``Z`` the backend reads."""
    return moment.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def quality_payload(
    report: QualityReport,
    *,
    assistant: AssistantSettings,
    pinned: Sequence[PromptChoice],
    started_at: datetime,
    finished_at: datetime,
    origin: str,
    commit_sha: str | None,
    dataset_digest: str,
) -> dict[str, Any]:
    """A quality measurement as the run the backend saves: a result per measured case, empty scores kept as ``None``."""
    info = report.info
    results = []
    for result in report.results:
        by_metric = result.scores.by_metric()
        results.append(
            {
                "case": _case(result.case),
                "case_kind": result.kind.value,
                "scores": {metric: _rounded(by_metric[metric]) for metric in applicable_metrics(result.kind)},
                # One id per delivered fragment, in order; a fragment of a document outside the corpus has no id.
                "retrieved_documents": [item.document_id or UNKNOWN_DOCUMENT for item in result.turn.context],
                "abstained": result.scores.abstained,
                "trace_id": result.trace_id,
            }
        )
    return {
        "run_id": info.run_id,
        "kind": RUN_KIND_QUALITY,
        "started_at": iso_utc(started_at),
        "finished_at": iso_utc(finished_at),
        "origin": origin,
        "commit_sha": commit_sha,
        "dataset_hash": dataset_digest,
        "cases_measured": len(results),
        "cases_excluded": report.excluded,
        "assistant_model": None if info.assistant_model == UNKNOWN else info.assistant_model,
        "embedding_model": info.embedding_model,
        "prompts": merge_prompts(assistant, pinned),
        "judge": {
            "model": info.judge.model,
            "underlying_model": info.judge_underlying_model,
            "temperature": info.judge.temperature,
            "seed": JUDGE_SEED,
            "max_input_chars": info.judge.max_input_chars,
        },
        "evaluator": {
            "library": EVALUATION_LIBRARY,
            "library_version": info.ragas_version,
            "judgment_instructions_version": info.abstention_prompt_version,
        },
        "retrieval_config": assistant.retrieval_config(),
        "results": results,
    }


def retrieval_payload(
    report: RetrievalReport,
    *,
    run_id: str,
    assistant: AssistantSettings,
    assistant_model: str | None,
    started_at: datetime,
    finished_at: datetime,
    origin: str,
    commit_sha: str | None,
    dataset_digest: str,
) -> dict[str, Any]:
    """A retrieval measurement as the run the backend saves: hit, recall and rank of each case for every k."""
    results = []
    for result in report.results:
        scores: dict[str, float | None] = {}
        for k in report.ks:
            scores[f"hit_at_{k}"] = float(result.metrics.hit[k])
            scores[f"recall_at_{k}"] = _rounded(result.metrics.recall[k])
        scores["reciprocal_rank"] = _rounded(result.metrics.reciprocal_rank)
        results.append(
            {
                "case": _case(result.case),
                "case_kind": "answered",
                "scores": scores,
                "retrieved_documents": list(result.retrieved),
                "abstained": None,
                "trace_id": None,
            }
        )
    return {
        "run_id": run_id,
        "kind": RUN_KIND_RETRIEVAL,
        "started_at": iso_utc(started_at),
        "finished_at": iso_utc(finished_at),
        "origin": origin,
        "commit_sha": commit_sha,
        "dataset_hash": dataset_digest,
        "cases_measured": len(results),
        "cases_excluded": report.excluded,
        "assistant_model": assistant_model,
        "embedding_model": report.model,
        "prompts": [],
        "judge": None,
        "evaluator": None,
        "retrieval_config": {**assistant.retrieval_config(), "depth": report.depth, "ks": list(report.ks)},
        "results": results,
    }


def _case(case: GoldenCase) -> Mapping[str, Any]:
    return {
        "id": case.id,
        "category": case.category,
        "question": case.question,
        "reference_answer": case.reference_answer,
        "expected_documents": list(case.expected_documents),
    }


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(value, DECIMALS)
