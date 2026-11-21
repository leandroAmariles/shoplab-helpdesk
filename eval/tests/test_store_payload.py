"""What the backend receives to save a run (specs/LAB-31, FR-003 to FR-009, FR-015 and FR-019).

The reports are the same ones the CSV tests use, built by hand, so the numbers in the payload can be checked against
what the CSV of that very report says. Nothing here needs a backend, a model or a clock.
"""

import csv
import hashlib
import io
import json
import subprocess
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import pytest

from shoplab_eval.constants import JUDGE_SEED, UNKNOWN_DOCUMENT
from shoplab_eval.errors import StoreError
from shoplab_eval.prompt_selection import PromptChoice
from shoplab_eval.quality_report import summary_csv as quality_summary_csv
from shoplab_eval.quality_run import QualityReport
from shoplab_eval.retrieval_report import summary_csv as retrieval_summary_csv
from shoplab_eval.store_payload import (
    AssistantSettings,
    ServedPrompt,
    dataset_hash,
    git_commit,
    iso_utc,
    merge_prompts,
    parse_settings,
    quality_payload,
    retrieval_payload,
    settings_drift,
)
from test_quality_report import ANSWER_TEXT, FRAGMENT_TEXT, RUN_ID, worked_report
from test_retrieval_report import _report as retrieval_report

STARTED = datetime(2026, 11, 21, 14, 2, 11, tzinfo=UTC)
FINISHED = STARTED + timedelta(minutes=9)
DIGEST = "d" * 64
COMMIT = "5" * 40

SERVED = (
    ServedPrompt("chat-system", 3, "LANGFUSE"),
    ServedPrompt("chat-small-talk", 1, "LANGFUSE"),
    ServedPrompt("chat-context", 2, "LANGFUSE"),
    ServedPrompt("chat-empty-context", None, "DEFAULT"),
)


def assistant(top_k: int = 4, prompts: tuple[ServedPrompt, ...] = SERVED) -> AssistantSettings:
    return AssistantSettings(top_k, 0.6, 8000, prompts)


def quality(pinned: tuple[PromptChoice, ...] = (), report: QualityReport | None = None) -> dict:
    return quality_payload(
        report or worked_report(),
        assistant=assistant(),
        pinned=pinned,
        started_at=STARTED,
        finished_at=FINISHED,
        origin="local",
        commit_sha=COMMIT,
        dataset_digest=DIGEST,
    )


def retrieval(assistant_model: str | None = "llama3.2:3b") -> dict:
    return retrieval_payload(
        retrieval_report(),
        run_id="r1",
        assistant=assistant(),
        assistant_model=assistant_model,
        started_at=STARTED,
        finished_at=FINISHED,
        origin="dev",
        commit_sha=None,
        dataset_digest=DIGEST,
    )


def rows(text: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(text)))


def means(payload: dict, metric: str, category: str | None = None) -> tuple[float | None, int]:
    values = [
        result["scores"].get(metric)
        for result in payload["results"]
        if category is None or result["case"]["category"] == category
    ]
    present = [value for value in values if value is not None]
    return (round(sum(present) / len(present), 4) if present else None), len(present)


# --- the run of a quality measurement ---------------------------------------------------------------------------


def test_a_quality_run_has_the_identity_the_parameters_and_the_counts_of_the_report() -> None:
    payload = quality()

    assert payload["run_id"] == RUN_ID
    assert payload["kind"] == "QUALITY"
    assert payload["started_at"] == "2026-11-21T14:02:11.000Z"
    assert payload["finished_at"] == "2026-11-21T14:11:11.000Z"
    assert (payload["origin"], payload["commit_sha"], payload["dataset_hash"]) == ("local", COMMIT, DIGEST)
    assert (payload["cases_measured"], payload["cases_excluded"]) == (5, 3)
    assert (payload["assistant_model"], payload["embedding_model"]) == ("llama3.2:3b", "bge-m3")
    assert payload["retrieval_config"] == {"top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000}


def test_the_judge_and_the_library_are_recorded_with_their_settings() -> None:
    payload = quality()

    assert payload["judge"] == {
        "model": "judge",
        "underlying_model": "qwen2.5:7b",
        "temperature": 0.0,
        "seed": JUDGE_SEED,
        "max_input_chars": 24000,
    }
    assert payload["evaluator"] == {
        "library": "ragas",
        "library_version": "0.4.4",
        "judgment_instructions_version": "1",
    }


def test_an_assistant_model_that_is_not_known_is_left_empty_and_never_invented() -> None:
    report = worked_report()
    unknown = QualityReport(
        info=type(report.info)(**{**report.info.__dict__, "assistant_model": "unknown"}),
        results=report.results,
        summaries=report.summaries,
        excluded=report.excluded,
    )

    assert quality(report=unknown)["assistant_model"] is None


def test_every_measured_case_is_a_result_and_the_conversation_cases_are_not() -> None:
    payload = quality()

    assert [result["case"]["id"] for result in payload["results"]] == ["G001", "G002", "G003", "G004", "G005"]
    assert {result["case_kind"] for result in payload["results"]} == {"answered", "unanswered"}
    assert payload["cases_excluded"] == 3


def test_an_empty_score_stays_empty_and_a_zero_stays_a_zero() -> None:
    results = {result["case"]["id"]: result for result in quality()["results"]}

    assert results["G001"]["scores"] == {"faithfulness": 0.8, "answer_relevancy": 0.6, "context_precision": 1.0}
    assert results["G002"]["scores"] == {"faithfulness": None, "answer_relevancy": 0.3, "context_precision": 0.0}
    assert results["G004"]["scores"] == {"faithfulness": None}


def test_a_case_without_answer_only_carries_faithfulness_and_the_abstention() -> None:
    results = {result["case"]["id"]: result for result in quality()["results"]}

    assert set(results["G003"]["scores"]) == {"faithfulness"}
    assert results["G003"]["abstained"] is False
    assert results["G004"]["abstained"] is True


def test_the_delivered_documents_keep_their_order_and_repetitions_and_a_document_without_id_has_a_placeholder() -> None:
    results = {result["case"]["id"]: result for result in quality()["results"]}

    assert results["G001"]["retrieved_documents"] == [
        "payments-02-installments",
        "payments-02-installments",
        UNKNOWN_DOCUMENT,
    ]
    assert results["G004"]["retrieved_documents"] == []


def test_the_trace_of_each_turn_is_the_one_the_csv_shows() -> None:
    report = worked_report()
    payload = quality()

    assert [result["trace_id"] for result in payload["results"]] == [result.trace_id for result in report.results]


def test_neither_the_text_of_an_answer_nor_the_one_of_a_fragment_is_in_the_payload() -> None:
    text = json.dumps(quality(), ensure_ascii=False)

    assert ANSWER_TEXT not in text
    assert FRAGMENT_TEXT not in text


def test_the_dataset_case_travels_whole_because_it_is_what_was_measured() -> None:
    first = quality()["results"][0]["case"]

    assert first == {
        "id": "G001",
        "category": "payments",
        "question": "Pregunta G001",
        "reference_answer": "Referencia G001",
        "expected_documents": ["d1"],
    }


@pytest.mark.parametrize("metric", ["faithfulness", "answer_relevancy", "context_precision"])
def test_the_means_recalculated_from_the_payload_are_the_ones_of_the_summary_csv(metric: str) -> None:
    report = worked_report()
    payload = quality(report=report)
    summary = rows(quality_summary_csv(report))

    for row in summary:
        category = None if row["scope"] == "all" else row["scope"]
        mean, count = means(payload, metric, category)
        assert count == int(row[f"{metric}_n"]), row["scope"]
        expected = row[f"{metric}_mean"]
        assert (expected == "" and mean is None) or (expected != "" and mean == pytest.approx(float(expected))), (
            row["scope"]
        )


# --- the prompts ----------------------------------------------------------------------------------------------


def test_without_fixed_versions_every_prompt_has_the_version_the_backend_serves() -> None:
    assert merge_prompts(assistant(), ()) == [
        {"name": "chat-system", "version": 3, "source": "LANGFUSE", "pinned": False},
        {"name": "chat-small-talk", "version": 1, "source": "LANGFUSE", "pinned": False},
        {"name": "chat-context", "version": 2, "source": "LANGFUSE", "pinned": False},
        {"name": "chat-empty-context", "version": None, "source": "DEFAULT", "pinned": False},
    ]


def test_a_prompt_the_run_fixed_has_that_version_and_is_marked_as_fixed_while_the_rest_keep_the_served_one() -> None:
    merged = {prompt["name"]: prompt for prompt in merge_prompts(
        assistant(), (PromptChoice("chat-system", "5", 5), PromptChoice("chat-empty-context", "9", 9))
    )}  # fmt: skip

    assert merged["chat-system"] == {"name": "chat-system", "version": 5, "source": "LANGFUSE", "pinned": True}
    assert merged["chat-empty-context"]["version"] == 9
    assert merged["chat-empty-context"]["source"] == "LANGFUSE"
    assert merged["chat-context"] == {"name": "chat-context", "version": 2, "source": "LANGFUSE", "pinned": False}


def test_the_quality_payload_carries_the_merged_prompts() -> None:
    payload = quality(pinned=(PromptChoice("chat-context", "2", 2),))

    assert [prompt["pinned"] for prompt in payload["prompts"]] == [False, False, True, False]


# --- the run of a retrieval measurement -------------------------------------------------------------------------


def test_a_retrieval_run_has_no_prompts_no_judge_and_no_traces() -> None:
    payload = retrieval()

    assert payload["kind"] == "RETRIEVAL"
    assert (payload["prompts"], payload["judge"], payload["evaluator"]) == ([], None, None)
    assert payload["retrieval_config"] == {
        "top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000, "depth": 50, "ks": [1, 3, 5],
    }  # fmt: skip
    assert all(
        result["abstained"] is None and result["trace_id"] is None and result["case_kind"] == "answered"
        for result in payload["results"]
    )
    assert payload["embedding_model"] == "bge-m3"
    assert payload["commit_sha"] is None


def test_a_retrieval_result_has_hit_recall_and_rank_for_each_k() -> None:
    results = {result["case"]["id"]: result for result in retrieval()["results"]}

    assert set(results["G001"]["scores"]) == {
        "hit_at_1", "hit_at_3", "hit_at_5", "recall_at_1", "recall_at_3", "recall_at_5", "reciprocal_rank",
    }  # fmt: skip
    assert results["G001"]["scores"]["hit_at_1"] == 1.0
    assert results["G001"]["scores"]["reciprocal_rank"] == 1.0
    assert results["G002"]["scores"]["recall_at_1"] == 0.0
    assert results["G002"]["scores"]["recall_at_3"] == 0.5
    assert results["G002"]["retrieved_documents"] == ["returns-01", "payments-02"]


def test_the_cases_without_expected_documents_are_not_results_of_a_retrieval_run() -> None:
    payload = retrieval()

    assert payload["cases_measured"] == 4
    assert payload["cases_excluded"] == 2
    assert {result["case"]["id"] for result in payload["results"]} == {"G001", "G002", "G003", "G004"}


def test_the_assistant_model_of_a_retrieval_run_is_whatever_the_environment_said() -> None:
    assert retrieval(assistant_model=None)["assistant_model"] is None


def test_the_means_recalculated_from_a_retrieval_payload_are_the_ones_of_its_summary_csv() -> None:
    payload = retrieval()
    summary = {row["scope"]: row for row in rows(retrieval_summary_csv(retrieval_report()))}

    for column, metric in (("hit_rate@3", "hit_at_3"), ("recall@3", "recall_at_3"), ("mrr", "reciprocal_rank")):
        mean, _ = means(payload, metric)
        assert mean == pytest.approx(float(summary["all"][column]))


# --- the identity of the dataset and of the code ----------------------------------------------------------------


def test_the_dataset_hash_is_the_sha256_of_the_bytes_of_the_file(tmp_path: Path) -> None:
    golden = tmp_path / "golden.jsonl"
    golden.write_bytes(b'{"id": "G001"}\n')

    assert dataset_hash(golden) == hashlib.sha256(golden.read_bytes()).hexdigest()
    before = dataset_hash(golden)
    golden.write_bytes(b'{"id": "G002"}\n')
    assert dataset_hash(golden) != before


def test_the_commit_is_empty_when_it_cannot_be_known(tmp_path: Path) -> None:
    assert git_commit(tmp_path) is None


def test_the_commit_is_empty_when_git_is_not_installed(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    def missing(*args: object, **kwargs: object) -> None:
        raise FileNotFoundError("git")

    monkeypatch.setattr(subprocess, "run", missing)

    assert git_commit(tmp_path) is None


@pytest.mark.parametrize(("output", "code", "expected"), [("a" * 40 + "\n", 0, "a" * 40), ("not a commit\n", 0, None),
                                                          ("a" * 40, 128, None), ("", 0, None)])  # fmt: skip
def test_only_a_commit_of_40_hexadecimal_characters_is_accepted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, output: str, code: int, expected: str | None
) -> None:
    monkeypatch.setattr(
        subprocess, "run", lambda *args, **kwargs: subprocess.CompletedProcess(args, code, stdout=output, stderr="")
    )

    assert git_commit(tmp_path) == expected


def test_a_moment_is_written_in_utc_with_a_z() -> None:
    other_zone = datetime(2026, 11, 21, 9, 2, 11, 123000, tzinfo=timezone(timedelta(hours=-5)))

    assert iso_utc(other_zone) == "2026-11-21T14:02:11.123Z"


# --- the settings the assistant uses ---------------------------------------------------------------------------


def test_the_answer_of_the_backend_becomes_settings() -> None:
    body = {
        "retrieval": {"top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000},
        "prompts": [{"name": "chat-system", "version": 3, "source": "LANGFUSE"},
                    {"name": "chat-empty-context", "version": None, "source": "DEFAULT"}],
    }  # fmt: skip

    assert parse_settings(body) == AssistantSettings(
        4, 0.6, 8000, (ServedPrompt("chat-system", 3, "LANGFUSE"), ServedPrompt("chat-empty-context", None, "DEFAULT"))
    )


@pytest.mark.parametrize("body", [None, {}, {"retrieval": {}, "prompts": []}, {"retrieval": 1, "prompts": []},
                                  {"retrieval": {"top_k": "x", "similarity_threshold": 1, "max_context_characters": 1},
                                   "prompts": []}])  # fmt: skip
def test_an_answer_without_the_expected_shape_is_an_error_that_says_so(body: object) -> None:
    with pytest.raises(StoreError, match="forma esperada"):
        parse_settings(body)


def test_no_change_is_no_drift() -> None:
    assert settings_drift(assistant(), assistant(), ()) == []


def test_a_change_of_the_retrieval_parameters_is_a_drift() -> None:
    assert settings_drift(assistant(top_k=4), assistant(top_k=6), ()) == ["retrieval.top_k"]


def test_a_prompt_that_changed_version_is_a_drift_named_after_the_prompt() -> None:
    moved = tuple(
        ServedPrompt(prompt.name, 4, prompt.source) if prompt.name == "chat-context" else prompt for prompt in SERVED
    )

    assert settings_drift(assistant(), assistant(prompts=moved), ()) == ["prompt chat-context"]


def test_a_prompt_the_run_fixed_itself_does_not_count_as_a_drift() -> None:
    moved = tuple(
        ServedPrompt(prompt.name, 4, prompt.source) if prompt.name == "chat-context" else prompt for prompt in SERVED
    )

    assert settings_drift(assistant(), assistant(prompts=moved), ["chat-context"]) == []


def test_a_prompt_that_appears_or_disappears_is_a_drift() -> None:
    fewer = SERVED[:3]

    assert settings_drift(assistant(), assistant(prompts=fewer), ()) == ["prompt chat-empty-context"]
