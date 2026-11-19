"""Attaching the scores to the Langfuse traces of the turns (specs/LAB-29, user story 3, FR-012 to FR-014, research R4).

The first half tests the orchestration with a double of Langfuse; the second half runs the command against a local
server that answers like Langfuse 4.x (``events_only`` mode) and a local backend. The judge is always a double.
"""

import csv
from collections.abc import Iterator, Sequence
from pathlib import Path

import pytest

from fake_backend import FakeBackend, chat_turn, indexed_documents, source
from fake_gateway import GATEWAY_KEY
from fake_langfuse import PUBLIC_KEY, SECRET_KEY, FakeLangfuse
from helpers import TOY_DOCS, valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.chat_client import ChatTurn, ContextItem
from shoplab_eval.constants import (
    JUDGE_API_KEY_ENV,
    LANGFUSE_HOST_ENV,
    LANGFUSE_PUBLIC_KEY_ENV,
    LANGFUSE_SECRET_KEY_ENV,
    QUALITY_DETAIL_FILE,
    QUALITY_SUMMARY_FILE,
)
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import GoldenCase, parse_golden
from shoplab_eval.langfuse_client import LangfuseClient, LangfuseStatus, ScoreToPublish, score_id_for, trace_id_for
from shoplab_eval.quality_judge import JudgeSettings
from shoplab_eval.quality_run import RunInfo, measure_quality, request_id_for

RUN_ID = "20261118T153000Z"


def case(case_id: str, category: str = "payments", documents: tuple[str, ...] = ("payments-01-a",)) -> GoldenCase:
    return GoldenCase(case_id, f"Pregunta {case_id}", f"Referencia {case_id}", documents, category)


class FakeChat:
    def __init__(self, log: list[str]) -> None:
        self.log = log

    def ask(self, question: str, request_id: str) -> ChatTurn:
        self.log.append(f"ask:{request_id.rsplit('-', 1)[-1]}")
        context = (ContextItem(1, "Título", "", "Texto", "doc-1"),)
        return ChatTurn(request_id, "Respuesta del asistente.", context)


class FakeJudge:
    def __init__(self, log: list[str]) -> None:
        self.log = log
        self.abstain_questions: set[str] = set()

    async def abstains(self, question: str, answer: str) -> bool:
        self.log.append("judge")
        return question in self.abstain_questions

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return 0.8

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return 0.6

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return 1.0


class FakeTraces:
    """Langfuse without HTTP: which traces exist, which publications fail, and everything that was published."""

    def __init__(self, log: list[str]) -> None:
        self.log = log
        self.missing: set[str] = set()
        self.failing: set[str] = set()
        self.published: list[ScoreToPublish] = []
        self.access_checked = 0

    def check_access(self) -> None:
        self.access_checked += 1

    def wait_for_trace(self, trace_id: str) -> None:
        self.log.append("trace")
        if trace_id in self.missing:
            raise MeasurementError(f"La traza {trace_id} no apareció en Langfuse")

    def publish_score(self, score: ScoreToPublish) -> None:
        self.log.append("publish")
        if score.trace_id in self.failing:
            raise MeasurementError(f"Langfuse respondió 500 al publicar {score.name}")
        self.published.append(score)


@pytest.fixture
def log() -> list[str]:
    return []


@pytest.fixture
def traces(log: list[str]) -> FakeTraces:
    return FakeTraces(log)


def info() -> RunInfo:
    return RunInfo(
        run_id=RUN_ID,
        judge=JudgeSettings(model="judge", concurrency=2),
        judge_underlying_model="ollama_chat/qwen2.5:7b",
        ragas_version="0.4.4",
        abstention_prompt_version="1",
        assistant_model="llama3.2:3b",
        embedding_model="bge-m3",
        langfuse="skipped",
    )


def run(cases, log, traces, judge=None):
    return measure_quality(cases, FakeChat(log), judge or FakeJudge(log), info(), langfuse=traces)


def trace_of(case_id: str) -> str:
    return trace_id_for(request_id_for(RUN_ID, case_id))


# --- the first turn is the probe ------------------------------------------------------------------------------


def test_the_trace_of_the_first_turn_is_awaited_before_the_second_turn_is_asked(
    log: list[str], traces: FakeTraces
) -> None:
    run([case("G001"), case("G002")], log, traces)

    assert log[:3] == ["ask:G001", "trace", "ask:G002"]


def test_when_the_first_trace_never_arrives_nothing_else_is_asked_or_judged(log: list[str], traces: FakeTraces) -> None:
    traces.missing = {trace_of("G001")}

    with pytest.raises(MeasurementError, match=r"G001.*no apareció"):
        run([case("G001"), case("G002"), case("G003")], log, traces)

    assert log == ["ask:G001", "trace"]  # one question to the assistant, nothing to the judge, nothing published


# --- what is published ----------------------------------------------------------------------------------------


def test_one_score_per_metric_with_a_value_is_published_with_its_deterministic_id_and_its_trace(
    log: list[str], traces: FakeTraces
) -> None:
    run([case("G001")], log, traces)

    by_name = {score.name: score for score in traces.published}
    assert sorted(by_name) == ["answer_relevancy", "context_precision", "faithfulness"]
    for name, score in by_name.items():
        assert score.score_id == score_id_for(RUN_ID, "G001", name)
        assert score.trace_id == trace_of("G001")
    assert (by_name["faithfulness"].value, by_name["answer_relevancy"].value) == (0.8, 0.6)


def test_the_score_says_which_run_case_and_judge_it_comes_from(log: list[str], traces: FakeTraces) -> None:
    run([case("G001")], log, traces)

    score = traces.published[0]
    assert dict(score.metadata) == {
        "run_id": RUN_ID,
        "case_id": "G001",
        "judge_model": "judge",
        "judge_underlying_model": "ollama_chat/qwen2.5:7b",
    }
    assert "judge" in score.comment and "0.4.4" in score.comment


def test_an_unanswered_case_publishes_only_faithfulness(log: list[str], traces: FakeTraces) -> None:
    run([case("G050", documents=())], log, traces)

    assert [score.name for score in traces.published] == ["faithfulness"]


def test_an_empty_score_is_never_published(log: list[str], traces: FakeTraces) -> None:
    judge = FakeJudge(log)
    judge.abstain_questions = {"Pregunta G001"}  # it abstained: faithfulness is empty

    run([case("G001")], log, traces, judge)

    assert sorted(score.name for score in traces.published) == ["answer_relevancy", "context_precision"]


def test_a_case_with_no_value_at_all_has_nothing_to_attach_and_nothing_is_missing(
    log: list[str], traces: FakeTraces
) -> None:
    judge = FakeJudge(log)
    judge.abstain_questions = {"Pregunta G050"}

    report = run([case("G050", documents=())], log, traces, judge)

    assert traces.published == []
    assert report.results[0].langfuse is LangfuseStatus.ATTACHED


def test_the_scores_are_attached_after_the_judge_has_scored_every_case(log: list[str], traces: FakeTraces) -> None:
    run([case("G001"), case("G002")], log, traces)

    assert max(index for index, line in enumerate(log) if line == "judge") < min(
        index for index, line in enumerate(log) if line == "publish"
    )


# --- the result says what reached Langfuse --------------------------------------------------------------------


def test_when_everything_is_attached_every_case_and_the_run_say_so(log: list[str], traces: FakeTraces) -> None:
    report = run([case("G001"), case("G002")], log, traces)

    assert [result.langfuse for result in report.results] == [LangfuseStatus.ATTACHED] * 2
    assert report.info.langfuse == "attached"
    assert report.attach_failures == ()


def test_a_failed_publication_marks_only_that_case_and_the_run_is_partial_but_not_an_error(
    log: list[str], traces: FakeTraces
) -> None:
    traces.failing = {trace_of("G002")}

    report = run([case("G001"), case("G002"), case("G003")], log, traces)

    assert [result.langfuse.value for result in report.results] == ["yes", "no", "yes"]
    assert report.info.langfuse == "partial"
    assert len(report.attach_failures) == 1 and "G002" in report.attach_failures[0]
    assert {score.trace_id for score in traces.published} == {trace_of("G001"), trace_of("G003")}


def test_a_trace_that_is_not_there_when_attaching_is_reported_and_nothing_is_published_for_it(
    log: list[str], traces: FakeTraces
) -> None:
    class VanishingTraces(FakeTraces):
        def wait_for_trace(self, trace_id: str) -> None:
            if trace_id == trace_of("G002") and self.log.count("trace") >= 2:  # the probe (G001) is the first call
                raise MeasurementError(f"La traza {trace_id} no apareció en Langfuse")
            self.log.append("trace")

    vanishing = VanishingTraces(log)

    report = run([case("G001"), case("G002")], log, vanishing)

    assert [result.langfuse.value for result in report.results] == ["yes", "no"]
    assert all(score.trace_id != trace_of("G002") for score in vanishing.published)
    assert "G002" in report.attach_failures[0]


def test_without_langfuse_nothing_is_attached_and_the_run_says_skipped(log: list[str]) -> None:
    report = measure_quality([case("G001")], FakeChat(log), FakeJudge(log), info())

    assert report.results[0].langfuse is LangfuseStatus.SKIPPED
    assert report.info.langfuse == "skipped"
    assert report.attach_failures == ()


def test_two_runs_have_other_traces_and_other_score_ids(log: list[str], traces: FakeTraces) -> None:
    other = FakeTraces(log)
    run([case("G001")], log, traces)
    measure_quality([case("G001")], FakeChat(log), FakeJudge(log), _other_run(), langfuse=other)

    assert {s.score_id for s in traces.published}.isdisjoint({s.score_id for s in other.published})
    assert {s.trace_id for s in traces.published}.isdisjoint({s.trace_id for s in other.published})


def _other_run() -> RunInfo:
    from dataclasses import replace

    return replace(info(), run_id="20261118T153100Z")


# --- the command ----------------------------------------------------------------------------------------------


@pytest.fixture
def langfuse_server() -> Iterator[FakeLangfuse]:
    server = FakeLangfuse().start()
    try:
        yield server
    finally:
        server.stop()


class StubGateway:
    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        pass

    def check_ready(self, model: str) -> None:
        return None

    def underlying_model(self, model: str) -> str | None:
        return "ollama_chat/qwen2.5:7b"


@pytest.fixture(autouse=True)
def command_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "build_judge", lambda settings, key: FakeJudge([]))
    monkeypatch.setattr(cli, "GatewayClient", StubGateway)
    monkeypatch.setenv(JUDGE_API_KEY_ENV, GATEWAY_KEY)
    for name in (LANGFUSE_HOST_ENV, LANGFUSE_PUBLIC_KEY_ENV, LANGFUSE_SECRET_KEY_ENV):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


@pytest.fixture
def served(backend: FakeBackend) -> FakeBackend:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    backend.documents_reply = (200, indexed_documents(*TOY_DOCS))  # the whole corpus is indexed
    return backend


def knowledge_cases(golden: Path) -> list[GoldenCase]:
    return [c for c in parse_golden(golden).cases if not c.is_conversation]


def with_langfuse(monkeypatch: pytest.MonkeyPatch, server: FakeLangfuse, secret: str = SECRET_KEY) -> None:
    monkeypatch.setenv(LANGFUSE_HOST_ENV, server.url)
    monkeypatch.setenv(LANGFUSE_PUBLIC_KEY_ENV, PUBLIC_KEY)
    monkeypatch.setenv(LANGFUSE_SECRET_KEY_ENV, secret)
    monkeypatch.setattr(
        cli,
        "build_langfuse",
        lambda host, public, private: LangfuseClient(host, public, private, 2.0, wait_seconds=0.2, poll_seconds=0.01),
    )


def expose_every_trace(server: FakeLangfuse, golden: Path, run_id: str) -> None:
    for item in knowledge_cases(golden):
        server.add_trace(trace_id_for(request_id_for(run_id, item.id)))


def quality(backend: FakeBackend, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return cli.main(
        ["quality", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb), "--out", str(out), *extra]
    )


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def test_without_keys_and_without_the_flag_nothing_is_attached_and_it_is_not_an_error(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    assert quality(served, dataset, tmp_path / "out") == 0

    assert read_csv(tmp_path / "out" / QUALITY_SUMMARY_FILE)[0]["langfuse"] == "skipped"
    assert {row["langfuse"] for row in read_csv(tmp_path / "out" / QUALITY_DETAIL_FILE)} == {"skipped"}


def test_asking_for_langfuse_without_its_keys_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert quality(served, dataset, tmp_path / "out", "--langfuse") == 2

    assert LANGFUSE_PUBLIC_KEY_ENV in capsys.readouterr().err
    assert served.chat_requests == []
    assert not (tmp_path / "out").exists()


def test_no_langfuse_wins_over_the_keys_of_the_environment(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with_langfuse(monkeypatch, langfuse_server)

    assert quality(served, dataset, tmp_path / "out", "--no-langfuse") == 0

    assert langfuse_server.requests == []
    assert read_csv(tmp_path / "out" / QUALITY_SUMMARY_FILE)[0]["langfuse"] == "skipped"


def test_with_the_keys_the_scores_reach_langfuse_and_the_files_say_so(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with_langfuse(monkeypatch, langfuse_server)
    expose_every_trace(langfuse_server, dataset[0], "demo-1")

    code = quality(served, dataset, tmp_path / "out", "--run-id", "demo-1")

    assert code == 0
    assert read_csv(tmp_path / "out" / QUALITY_SUMMARY_FILE)[0]["langfuse"] == "attached"
    detail = read_csv(tmp_path / "out" / QUALITY_DETAIL_FILE)
    assert {row["langfuse"] for row in detail} == {"yes"}
    answered = [c for c in knowledge_cases(dataset[0]) if c.answerable]
    unanswered = [c for c in knowledge_cases(dataset[0]) if not c.answerable]
    assert len(langfuse_server.scores) == 3 * len(answered) + len(unanswered)


def test_running_the_same_run_twice_does_not_duplicate_the_scores(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with_langfuse(monkeypatch, langfuse_server)
    expose_every_trace(langfuse_server, dataset[0], "demo-1")

    quality(served, dataset, tmp_path / "a", "--run-id", "demo-1")
    first = dict(langfuse_server.scores)
    quality(served, dataset, tmp_path / "b", "--run-id", "demo-1")

    assert set(langfuse_server.scores) == set(first)
    assert len(langfuse_server.score_requests) == 2 * len(first)


def test_another_run_leaves_the_scores_of_the_first_one_untouched(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with_langfuse(monkeypatch, langfuse_server)
    expose_every_trace(langfuse_server, dataset[0], "demo-1")
    expose_every_trace(langfuse_server, dataset[0], "demo-2")

    quality(served, dataset, tmp_path / "a", "--run-id", "demo-1")
    first = dict(langfuse_server.scores)
    quality(served, dataset, tmp_path / "b", "--run-id", "demo-2")

    assert all(langfuse_server.scores[score_id] == payload for score_id, payload in first.items())
    assert len(langfuse_server.scores) == 2 * len(first)
    assert {payload["metadata"]["run_id"] for payload in langfuse_server.scores.values()} == {"demo-1", "demo-2"}


def test_wrong_langfuse_keys_fail_before_the_assistant_is_asked_and_the_secret_is_not_shown(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with_langfuse(monkeypatch, langfuse_server, secret="sk-lf-equivocada")

    code = quality(served, dataset, tmp_path / "out")

    captured = capsys.readouterr()
    assert code == 1
    assert "401" in captured.err
    assert "sk-lf-equivocada" not in captured.out + captured.err
    assert served.chat_requests == []
    assert not (tmp_path / "out").exists()


def test_traces_that_never_arrive_stop_the_run_after_one_question_and_write_nothing(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with_langfuse(monkeypatch, langfuse_server)  # no trace is ever exposed: the traces of the chat are off

    code = quality(served, dataset, tmp_path / "out", "--run-id", "demo-1")

    assert code == 1
    assert "no apareció" in capsys.readouterr().err
    assert len(served.chat_requests) == 1
    assert not (tmp_path / "out").exists()


def test_when_some_scores_cannot_be_attached_the_files_are_written_complete_and_the_exit_code_is_one(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with_langfuse(monkeypatch, langfuse_server)
    expose_every_trace(langfuse_server, dataset[0], "demo-1")
    failing = knowledge_cases(dataset[0])[1]
    langfuse_server.fail_scores_for = {trace_id_for(request_id_for("demo-1", failing.id))}

    code = quality(served, dataset, tmp_path / "out", "--run-id", "demo-1")

    assert code == 1
    assert failing.id in capsys.readouterr().err
    summary = read_csv(tmp_path / "out" / QUALITY_SUMMARY_FILE)[0]
    detail = {row["id"]: row for row in read_csv(tmp_path / "out" / QUALITY_DETAIL_FILE)}
    assert summary["langfuse"] == "partial"
    assert detail[failing.id]["langfuse"] == "no"
    assert len(detail) == len(knowledge_cases(dataset[0]))
    assert sum(row["langfuse"] == "yes" for row in detail.values()) == len(detail) - 1


def test_the_secret_key_of_langfuse_never_appears_in_the_output_or_in_the_files(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    langfuse_server: FakeLangfuse,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    with_langfuse(monkeypatch, langfuse_server)
    expose_every_trace(langfuse_server, dataset[0], "demo-1")

    quality(served, dataset, tmp_path / "out", "--run-id", "demo-1")

    captured = capsys.readouterr()
    assert SECRET_KEY not in captured.out + captured.err
    for name in (QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE):
        assert SECRET_KEY not in (tmp_path / "out" / name).read_text(encoding="utf-8")
