"""The ``quality`` command end to end: the real chat client against a local server that plays the backend.

Only the judge is a double (it needs a model). The assistant is a ``ThreadingHTTPServer`` that sends real server-sent
events, so the command exercises the same code a real run does (specs/LAB-29, FR-022).
"""

import re
from collections.abc import Sequence
from pathlib import Path

import pytest

from fake_backend import FakeBackend, chat_turn, indexed_documents, source
from helpers import TOY_DOCS, valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.__main__ import main
from shoplab_eval.constants import JUDGE_API_KEY_ENV, QUALITY_DETAIL_FILE, QUALITY_SUMMARY_FILE
from shoplab_eval.golden import parse_golden

KEY = "sk-clave-de-prueba"


class FakeJudge:
    """The judge without a model: fixed values, and it never abstains."""

    async def abstains(self, question: str, answer: str) -> bool:
        return False

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return 0.9

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return 0.6

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return 1.0


class StubGateway:
    """The gateway without HTTP: this module tests the command, ``test_quality_config`` tests the gateway check."""

    def __init__(self, base_url: str, api_key: str, timeout: float = 10.0) -> None:
        pass

    def check_ready(self, model: str) -> None:
        return None

    def underlying_model(self, model: str) -> str | None:
        return "ollama_chat/qwen2.5:7b"


@pytest.fixture(autouse=True)
def judge_double(monkeypatch: pytest.MonkeyPatch) -> list[tuple[object, str]]:
    """Every command of this module gets the double and a key; ``built`` records how the judge was asked for."""
    built: list[tuple[object, str]] = []

    def build(settings: object, api_key: str) -> FakeJudge:
        built.append((settings, api_key))
        return FakeJudge()

    monkeypatch.setattr(cli, "build_judge", build)
    monkeypatch.setattr(cli, "GatewayClient", StubGateway)
    monkeypatch.setenv(JUDGE_API_KEY_ENV, KEY)
    return built


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


@pytest.fixture
def served(backend: FakeBackend) -> FakeBackend:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Puedes pagar en hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    backend.documents_reply = (200, indexed_documents(*TOY_DOCS))  # the whole corpus is indexed
    return backend


def quality(backend: FakeBackend, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return main(
        ["quality", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb), "--out", str(out),
         "--no-langfuse", *extra]
    )  # fmt: skip


def knowledge_cases(golden: Path):
    return [case for case in parse_golden(golden).cases if not case.is_conversation]


# --- the happy path -------------------------------------------------------------------------------------------


def test_the_command_succeeds_writes_the_two_csv_files_and_nothing_else(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    out = tmp_path / "out"

    code = quality(served, dataset, out)

    assert code == 0
    assert sorted(path.name for path in out.iterdir()) == sorted([QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE])
    detail = (out / QUALITY_DETAIL_FILE).read_text(encoding="utf-8").splitlines()
    assert len(detail) == 1 + len(knowledge_cases(dataset[0]))


def test_the_summary_goes_to_the_standard_output_and_the_progress_to_the_error_output(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    quality(served, dataset, tmp_path / "out")

    captured = capsys.readouterr()
    assert "Calidad de respuesta" in captured.out
    assert "all" in captured.out and "payments" in captured.out
    assert "excluidos" in captured.out
    assert str(tmp_path / "out" / QUALITY_SUMMARY_FILE) in captured.out
    assert re.search(r"\[1/\d+\] turno del asistente G001", captured.err)
    assert re.search(r"\[\d+/\d+\] juez ", captured.err)


def test_the_assistant_is_asked_once_per_knowledge_case_and_never_for_a_conversation_case(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    quality(served, dataset, tmp_path / "out")

    asked = [str(request["body"]["message"]) for request in served.chat_requests]
    assert asked == [case.question for case in knowledge_cases(dataset[0])]
    assert all("conversationId" not in request["body"] for request in served.chat_requests)


def test_the_run_id_names_the_requests_and_the_summary(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    out = tmp_path / "out"

    quality(served, dataset, out, "--run-id", "demo-1")

    assert {request["headers"]["x-request-id"] for request in served.chat_requests} >= {"eval-demo-1-G001"}
    assert all(str(r["headers"]["x-request-id"]).startswith("eval-demo-1-") for r in served.chat_requests)
    assert ",demo-1," in (out / QUALITY_SUMMARY_FILE).read_text(encoding="utf-8")


def test_without_a_run_id_the_run_is_named_by_the_utc_moment_it_starts(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    quality(served, dataset, tmp_path / "out")

    request_id = str(served.chat_requests[0]["headers"]["x-request-id"])
    assert re.fullmatch(r"eval-\d{8}T\d{6}Z-G001", request_id)


def test_the_assistant_model_is_recorded_when_it_is_given_and_unknown_otherwise(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("SHOPLAB_EVAL_ASSISTANT_MODEL", raising=False)

    quality(served, dataset, tmp_path / "unknown")
    quality(served, dataset, tmp_path / "given", "--assistant-model", "llama3.2:3b")

    assert ",unknown," in (tmp_path / "unknown" / QUALITY_SUMMARY_FILE).read_text(encoding="utf-8")
    assert ",llama3.2:3b," in (tmp_path / "given" / QUALITY_SUMMARY_FILE).read_text(encoding="utf-8")


def test_the_judge_is_built_with_the_key_of_the_environment_and_the_concurrency_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, judge_double: list[tuple[object, str]]
) -> None:
    quality(served, dataset, tmp_path / "out", "--concurrency", "3")

    (settings, api_key), = judge_double
    assert api_key == KEY
    assert settings.concurrency == 3


# --- the command is wired like the others ---------------------------------------------------------------------


def test_the_subcommand_is_listed_in_the_help(capsys: pytest.CaptureFixture[str]) -> None:
    code = main([])

    assert code == 2
    assert "quality" in capsys.readouterr().out


def test_the_other_commands_keep_working(dataset: tuple[Path, Path], capsys: pytest.CaptureFixture[str]) -> None:
    golden, kb = dataset

    assert main(["validate", "--golden", str(golden), "--kb", str(kb)]) == 0
    assert "Casos:" in capsys.readouterr().out


def test_without_the_key_of_the_judge_the_command_says_which_variable_to_define_and_writes_nothing(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.delenv(JUDGE_API_KEY_ENV)

    code = quality(served, dataset, tmp_path / "out")

    assert code == 2
    assert JUDGE_API_KEY_ENV in capsys.readouterr().err
    assert not (tmp_path / "out").exists()
    assert served.chat_requests == []


# --- the Host header of the ingress of DEV --------------------------------------------------------------------


def test_the_host_header_reaches_every_request_to_the_backend(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    code = quality(served, dataset, tmp_path / "out", "--host-header", "shoplab.localtest.me")

    assert code == 0
    chat_hosts = {request["headers"]["host"] for request in served.chat_requests}
    catalog_and_search_hosts = {headers["host"] for headers in served.get_headers}
    assert chat_hosts == catalog_and_search_hosts == {"shoplab.localtest.me"}


def test_the_host_header_can_come_from_the_environment_and_a_flag_wins_over_it(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("SHOPLAB_EVAL_HOST_HEADER", "del-entorno.localtest.me")

    quality(served, dataset, tmp_path / "env")
    assert {r["headers"]["host"] for r in served.chat_requests} == {"del-entorno.localtest.me"}

    served.chat_requests.clear()
    quality(served, dataset, tmp_path / "flag", "--host-header", "del-flag.localtest.me")
    assert {r["headers"]["host"] for r in served.chat_requests} == {"del-flag.localtest.me"}


@pytest.mark.parametrize("bad", ["", "dos palabras", "salto\nde linea", "http://shoplab.localtest.me"])
def test_a_host_header_that_is_not_a_host_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str], bad: str
) -> None:
    code = quality(served, dataset, tmp_path / "out", "--host-header", bad)

    assert code == 2
    assert "--host-header" in capsys.readouterr().err
    assert served.chat_requests == [] and served.get_headers == []
