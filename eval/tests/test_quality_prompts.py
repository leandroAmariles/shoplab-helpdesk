"""``quality --prompt``: the prompts a run fixes, end to end (specs/LAB-85-versioned-prompts, FR-017 to FR-024).

The real chat client and the real prompt client talk to local servers that play the backend and Langfuse. Only the
judge and the gateway are doubles (they need a model), like in ``test_quality_cli``.
"""

import csv
from collections.abc import Iterator, Sequence
from pathlib import Path

import pytest

from fake_backend import ChatReply, FakeBackend, chat_turn, indexed_documents, source
from fake_langfuse import PUBLIC_KEY, SECRET_KEY, FakeLangfuse
from helpers import TOY_DOCS, valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.__main__ import main
from shoplab_eval.constants import (
    JUDGE_API_KEY_ENV,
    LANGFUSE_HOST_ENV,
    LANGFUSE_PUBLIC_KEY_ENV,
    LANGFUSE_SECRET_KEY_ENV,
    PROMPT_TOKEN_ENV,
    QUALITY_SUMMARY_FILE,
)

TOKEN = "token-de-evaluacion"


class FakeJudge:
    async def abstains(self, question: str, answer: str) -> bool:
        return False

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return 0.9

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return 0.6

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return 1.0


class StubGateway:
    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        pass

    def check_ready(self, model: str) -> None:
        return None

    def underlying_model(self, model: str) -> str | None:
        return "ollama_chat/qwen2.5:7b"


@pytest.fixture(autouse=True)
def doubles(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "build_judge", lambda settings, api_key: FakeJudge())
    monkeypatch.setattr(cli, "GatewayClient", StubGateway)
    monkeypatch.setenv(JUDGE_API_KEY_ENV, "sk-clave-de-prueba")
    monkeypatch.setenv(PROMPT_TOKEN_ENV, TOKEN)


@pytest.fixture
def langfuse(monkeypatch: pytest.MonkeyPatch) -> Iterator[FakeLangfuse]:
    server = FakeLangfuse().start()
    monkeypatch.setenv(LANGFUSE_HOST_ENV, server.url)
    monkeypatch.setenv(LANGFUSE_PUBLIC_KEY_ENV, PUBLIC_KEY)
    monkeypatch.setenv(LANGFUSE_SECRET_KEY_ENV, SECRET_KEY)
    server.add_prompt("chat-system", 2)
    server.add_prompt("chat-system", 3, label="staging")
    server.add_prompt("chat-context", 4)
    try:
        yield server
    finally:
        server.stop()


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


@pytest.fixture
def served(backend: FakeBackend) -> FakeBackend:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Puedes pagar en hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    backend.documents_reply = (200, indexed_documents(*TOY_DOCS))
    return backend


def quality(backend: FakeBackend, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return main(
        ["quality", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb), "--out", str(out),
         "--no-langfuse", *extra]
    )  # fmt: skip


def summary_row(out: Path) -> dict[str, str]:
    with (out / QUALITY_SUMMARY_FILE).open(encoding="utf-8", newline="") as handle:
        return next(row for row in csv.DictReader(handle) if row["scope"] == "all")


# --- the versions reach the chat and the result ---------------------------------------------------------------


def test_the_resolved_versions_travel_in_every_request_with_the_token_and_the_result_records_them(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=staging", "--prompt", "chat-context=4")

    assert code == 0
    assert served.chat_requests
    for request in served.chat_requests:
        assert request["headers"]["x-prompt-versions"] == "chat-system=3,chat-context=4"
        assert request["headers"]["x-prompt-token"] == TOKEN
    assert summary_row(tmp_path / "out")["prompt_versions"] == "chat-context=4|chat-system=3"


def test_a_label_is_recorded_as_the_version_it_pointed_to_and_not_as_the_label(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=staging")

    assert "staging" not in summary_row(tmp_path / "out")["prompt_versions"]
    assert summary_row(tmp_path / "out")["prompt_versions"] == "chat-system=3"


def test_without_prompt_the_requests_carry_no_version_and_the_result_says_production(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    code = quality(served, dataset, tmp_path / "out")

    assert code == 0
    assert all("x-prompt-versions" not in request["headers"] for request in served.chat_requests)
    assert all("x-prompt-token" not in request["headers"] for request in served.chat_requests)
    assert summary_row(tmp_path / "out")["prompt_versions"] == "production"


def test_the_screen_report_says_which_prompts_were_measured(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=2")

    assert "Prompts: chat-system=2" in capsys.readouterr().out


# --- what is checked before the assistant is asked anything ---------------------------------------------------


@pytest.mark.parametrize(
    "arguments",
    [["--prompt", "chat-system"], ["--prompt", "nada=1"], ["--prompt", "chat-system=0"],
     ["--prompt", "chat-system=1", "--prompt", "chat-system=2"]],
)  # fmt: skip
def test_a_wrong_argument_is_a_usage_error_with_code_two_and_nothing_is_asked_or_written(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path, arguments: list[str]
) -> None:
    code = quality(served, dataset, tmp_path / "out", *arguments)

    assert code == 2
    assert served.chat_requests == []
    assert langfuse.prompt_requests == []
    assert not (tmp_path / "out").exists()


def test_without_the_token_it_is_a_usage_error_that_names_the_variable(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.delenv(PROMPT_TOKEN_ENV)

    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=2")

    assert code == 2
    assert PROMPT_TOKEN_ENV in capsys.readouterr().err
    assert served.chat_requests == []


def test_a_version_that_does_not_exist_stops_the_run_before_the_assistant_and_names_it(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=99")

    assert code == 1
    assert "chat-system" in capsys.readouterr().err
    assert served.chat_requests == []
    assert not (tmp_path / "out").exists()


def test_a_label_that_does_not_exist_stops_the_run_too(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-context=staging")

    assert code == 1
    assert "staging" in capsys.readouterr().err
    assert served.chat_requests == []


def test_with_langfuse_down_the_run_fails_and_never_measures_with_the_default_text(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setenv(LANGFUSE_HOST_ENV, "http://127.0.0.1:1")
    monkeypatch.setenv(LANGFUSE_PUBLIC_KEY_ENV, PUBLIC_KEY)
    monkeypatch.setenv(LANGFUSE_SECRET_KEY_ENV, SECRET_KEY)

    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=2")

    assert code == 1
    assert "no se mide con el texto por defecto" in capsys.readouterr().err
    assert served.chat_requests == []
    assert not (tmp_path / "out").exists()


def test_without_the_keys_of_langfuse_the_run_fails_naming_the_variables(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.delenv(LANGFUSE_PUBLIC_KEY_ENV, raising=False)
    monkeypatch.delenv(LANGFUSE_SECRET_KEY_ENV, raising=False)

    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=2")

    assert code == 1
    assert LANGFUSE_PUBLIC_KEY_ENV in capsys.readouterr().err
    assert served.chat_requests == []


# --- what the backend says ------------------------------------------------------------------------------------


def test_a_403_of_the_backend_stops_the_measurement_and_explains_the_token(
    served: FakeBackend, langfuse: FakeLangfuse, dataset: tuple[Path, Path], tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    served.chat_handler = lambda message, headers: ChatReply(
        status=403, body={"code": "SLHD_CHAT_00008", "message": "Fixing prompt versions is not allowed"}
    )

    code = quality(served, dataset, tmp_path / "out", "--prompt", "chat-system=2")

    assert code == 1
    error = capsys.readouterr().err
    assert "403" in error and "override-token" in error and PROMPT_TOKEN_ENV in error
    assert not (tmp_path / "out").exists()
