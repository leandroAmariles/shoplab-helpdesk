"""A measurement that cannot give a reliable result stops with the cause; a bad answer is only a low score (US4).

A broken environment (assistant down, judge down, index incomplete, invalid dataset) would give zeros that look like a
terrible assistant. So every one of these ends with an error that names the cause, a non-zero code, and **no** CSV: not
even the files of an earlier run are touched (specs/LAB-29, FR-003, FR-010, FR-011, FR-020, FR-021).
"""

from collections.abc import Sequence
from pathlib import Path

import pytest

from fake_backend import ChatReply, FakeBackend, chat_turn, indexed_documents, source
from helpers import TOY_DOCS, make_case, valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.constants import JUDGE_API_KEY_ENV, QUALITY_DETAIL_FILE, QUALITY_SUMMARY_FILE
from shoplab_eval.errors import MeasurementError
from shoplab_eval.golden import parse_golden

KEY = "sk-clave-de-prueba"
PREVIOUS = b"resultado de una ejecucion anterior\n"


class CountingJudge:
    """A judge with fixed values that counts its calls and can be told to fail."""

    def __init__(self) -> None:
        self.calls: list[str] = []
        self.value = 0.8
        self.error: Exception | None = None

    async def _answer(self, name: str, value: float | None) -> float | None:
        self.calls.append(name)
        if self.error is not None and name == getattr(self.error, "metric", name):
            raise self.error
        return value

    async def abstains(self, question: str, answer: str) -> bool:
        self.calls.append("abstains")
        return False

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return await self._answer("faithfulness", self.value)

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return await self._answer("answer_relevancy", self.value)

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return await self._answer("context_precision", self.value)


class StubGateway:
    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        pass

    def check_ready(self, model: str) -> None:
        return None

    def underlying_model(self, model: str) -> str | None:
        return "ollama_chat/qwen2.5:7b"


@pytest.fixture
def judge(monkeypatch: pytest.MonkeyPatch) -> CountingJudge:
    double = CountingJudge()
    monkeypatch.setattr(cli, "build_judge", lambda settings, api_key: double)
    monkeypatch.setattr(cli, "GatewayClient", StubGateway)
    monkeypatch.setenv(JUDGE_API_KEY_ENV, KEY)
    return double


@pytest.fixture
def served(backend: FakeBackend) -> FakeBackend:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    backend.documents_reply = (200, indexed_documents(*TOY_DOCS))
    return backend


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


@pytest.fixture
def out(tmp_path: Path) -> Path:
    """The folder of an earlier run: its files must survive any failure."""
    folder = tmp_path / "out"
    folder.mkdir()
    for name in (QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE):
        (folder / name).write_bytes(PREVIOUS)
    return folder


def quality(backend_url: str, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return cli.main(
        ["quality", "--base-url", backend_url, "--golden", str(golden), "--kb", str(kb), "--out", str(out),
         "--no-langfuse", *extra]
    )  # fmt: skip


def assert_nothing_was_written(out: Path) -> None:
    for name in (QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE):
        assert (out / name).read_bytes() == PREVIOUS, f"{name} se tocó"


# --- the assistant --------------------------------------------------------------------------------------------


def test_a_backend_that_is_down_stops_the_run_with_its_address_and_the_judge_is_never_called(
    dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    server = FakeBackend().start()
    url = server.url
    server.stop()

    code = quality(url, dataset, out, "--turn-timeout", "1")

    assert code == 1
    assert url in capsys.readouterr().err
    assert judge.calls == []
    assert_nothing_was_written(out)


@pytest.mark.parametrize(
    "reply",
    [
        ChatReply(events=[("token", {"content": "A medias"}), ("error", {"code": "SLHD_CHAT_00003", "message": "x"})]),
        ChatReply(status=503, body={"code": "SLHD_CHAT_00002", "message": "model unavailable"}),
        ChatReply(events=[("token", {"content": "Cortado sin done"})]),
    ],
    ids=["error-event", "http-503", "cut-stream"],
)
def test_an_assistant_that_fails_in_a_case_names_the_case_and_it_is_not_a_low_quality_answer(
    served: FakeBackend,
    dataset: tuple[Path, Path],
    out: Path,
    judge: CountingJudge,
    capsys: pytest.CaptureFixture[str],
    reply: ChatReply,
) -> None:
    second = parse_golden(dataset[0]).cases[1]
    ok = served.chat_handler
    served.chat_handler = lambda message, headers: reply if message == second.question else ok(message, headers)

    code = quality(served.url, dataset, out)

    assert code == 1
    assert f"caso {second.id}" in capsys.readouterr().err
    assert judge.calls == []
    assert_nothing_was_written(out)


# --- the judge ------------------------------------------------------------------------------------------------


def test_a_failing_judge_names_the_case_and_the_metric_and_is_never_counted_as_a_zero(
    served: FakeBackend, dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    judge.error = MeasurementError("El juez falló calculando answer_relevancy: ValueError: salida ilegible")
    judge.error.metric = "answer_relevancy"  # type: ignore[attr-defined]

    code = quality(served.url, dataset, out)

    message = capsys.readouterr().err
    assert code == 1
    assert "caso G" in message and "answer_relevancy" in message
    assert_nothing_was_written(out)


def test_an_input_for_the_judge_over_the_limit_is_an_error_that_names_the_case_not_a_zero(
    served: FakeBackend, dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    code = quality(served.url, dataset, out, "--max-input-chars", "40")

    message = capsys.readouterr().err
    assert code == 1
    assert "--max-input-chars" in message and "caso G" in message
    assert judge.calls == []
    assert_nothing_was_written(out)


def test_a_score_out_of_range_from_the_judge_is_an_error_not_a_clipped_value(
    served: FakeBackend, dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    judge.value = 1.7

    code = quality(served.url, dataset, out)

    assert code == 1
    assert "fuera de 0 a 1" in capsys.readouterr().err
    assert_nothing_was_written(out)


def test_a_low_score_is_a_result_and_the_command_succeeds(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, judge: CountingJudge
) -> None:
    judge.value = 0.0

    code = quality(served.url, dataset, tmp_path / "fresh")

    assert code == 0
    assert (tmp_path / "fresh" / QUALITY_SUMMARY_FILE).is_file()


# --- the environment is checked before anything expensive -----------------------------------------------------


def test_an_incomplete_index_stops_the_run_before_the_first_question_and_names_the_documents(
    served: FakeBackend, dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    served.documents_reply = (200, indexed_documents("payments-01-a", "privacy-01-a"))

    code = quality(served.url, dataset, out)

    message = capsys.readouterr().err
    assert code == 1
    assert "índice" in message
    assert "returns-01-a" in message and "shipping-01-a" in message
    assert served.chat_requests == []
    assert_nothing_was_written(out)


def test_a_document_that_is_not_indexed_yet_is_reported_with_its_state(
    served: FakeBackend, dataset: tuple[Path, Path], out: Path, judge: CountingJudge, capsys: pytest.CaptureFixture[str]
) -> None:
    ids = list(TOY_DOCS)
    served.documents_reply = (200, indexed_documents(*ids[:-1], status="PENDING") + indexed_documents(ids[-1]))

    code = quality(served.url, dataset, out)

    assert code == 1
    assert "PENDING" in capsys.readouterr().err
    assert served.chat_requests == []


def test_an_invalid_dataset_reproduces_its_failures_and_nothing_is_measured(
    served: FakeBackend,
    tmp_path: Path,
    toy_kb: Path,
    out: Path,
    judge: CountingJudge,
    capsys: pytest.CaptureFixture[str],
) -> None:
    broken = valid_default_set()
    broken[0] = make_case(id="G001", question="corta")  # a question outside the 10 to 300 characters of LAB-27
    golden = write_golden(tmp_path / "broken.jsonl", broken)

    code = quality(served.url, (golden, toy_kb), out)

    assert code == 1
    assert "G-QUESTION-LENGTH" in capsys.readouterr().err
    assert served.chat_requests == []
    assert_nothing_was_written(out)


def test_an_unreadable_golden_file_is_an_error_not_a_crash(
    served: FakeBackend,
    tmp_path: Path,
    toy_kb: Path,
    out: Path,
    judge: CountingJudge,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = quality(served.url, (tmp_path / "no-existe.jsonl", toy_kb), out)

    assert code == 1
    assert "no-existe.jsonl" in capsys.readouterr().err
    assert_nothing_was_written(out)
