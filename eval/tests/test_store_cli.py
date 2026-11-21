"""``quality`` and ``retrieval`` with the evaluation history, end to end (specs/LAB-31, FR-010 to FR-014).

The real clients talk to a local server that plays the backend, so the order of the requests is what the commands
really do. Only the judge and the gateway are doubles. What these tests protect: nothing is measured that could not be
saved, nothing is saved that was not measured whole, the CSV files are never the price of a failure to save, and whoever
does not save sees exactly what they saw before this story.
"""

from collections.abc import Sequence
from pathlib import Path

import pytest

from fake_backend import ChatReply, FakeBackend, chat_turn, eval_settings, source
from helpers import valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.__main__ import main
from shoplab_eval.constants import (
    ASSISTANT_MODEL_ENV,
    EVAL_ORIGIN_ENV,
    EVAL_TOKEN_ENV,
    JUDGE_API_KEY_ENV,
    QUALITY_DETAIL_FILE,
    QUALITY_SUMMARY_FILE,
)
from test_retrieval_cli import _serve_the_expected_documents

TOKEN = "token-de-prueba-del-cli"


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
    for name in (EVAL_TOKEN_ENV, EVAL_ORIGIN_ENV, ASSISTANT_MODEL_ENV):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def with_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(EVAL_TOKEN_ENV, TOKEN)


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


@pytest.fixture
def served(backend: FakeBackend, dataset: tuple[Path, Path]) -> FakeBackend:
    """A backend that answers the chat, the search and the catalog of the whole corpus."""
    backend.chat_handler = lambda message, headers: chat_turn(
        "Puedes pagar en hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    _serve_the_expected_documents(backend, dataset[0])
    return backend


def run_quality(base_url: str, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return main(["quality", "--base-url", base_url, "--golden", str(golden), "--kb", str(kb), "--out", str(out),
                 "--no-langfuse", *extra])  # fmt: skip


def run_retrieval(base_url: str, dataset: tuple[Path, Path], out: Path, *extra: str) -> int:
    golden, kb = dataset
    return main(["retrieval", "--base-url", base_url, "--golden", str(golden), "--kb", str(kb), "--out", str(out),
                 *extra])  # fmt: skip


def posted(backend: FakeBackend) -> list[dict[str, object]]:
    return [request for request in backend.eval_requests if request["method"] == "POST"]


def csv_files(out: Path) -> list[str]:
    return sorted(path.name for path in out.glob("*.csv")) if out.exists() else []


# --- saving the two kinds of run ---------------------------------------------------------------------------------


def test_a_quality_run_is_saved_after_the_files_are_written_with_the_token_and_the_parameters(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, capsys: pytest.CaptureFixture[str]
) -> None:
    out = tmp_path / "out"

    code = run_quality(served.url, dataset, out, "--run-id", "t031-q", "--save", "--assistant-model", "llama3.2:3b")

    captured = capsys.readouterr()
    body = posted(served)[0]["body"]
    assert code == 0
    assert csv_files(out) == [QUALITY_DETAIL_FILE, QUALITY_SUMMARY_FILE]
    assert "Guardada en el historial: ejecución t031-q" in captured.out
    assert (body["run_id"], body["kind"], body["origin"], body["assistant_model"]) == (  # type: ignore[index]
        "t031-q", "QUALITY", "local", "llama3.2:3b")
    assert posted(served)[0]["headers"]["X-Eval-Token"] == TOKEN  # type: ignore[index]
    assert len(body["results"]) == len((out / QUALITY_DETAIL_FILE).read_text("utf-8").splitlines()) - 1  # type: ignore[index]
    assert TOKEN not in captured.out + captured.err


def test_a_retrieval_run_is_saved_with_its_own_id_and_the_depth_and_the_ks(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, capsys: pytest.CaptureFixture[str]
) -> None:
    code = run_retrieval(served.url, dataset, tmp_path / "out", "--run-id", "t031-r", "--k", "1,3")

    body = posted(served)[0]["body"]
    assert code == 0
    assert "Guardada en el historial: ejecución t031-r" in capsys.readouterr().out
    assert (body["run_id"], body["kind"], body["prompts"]) == ("t031-r", "RETRIEVAL", [])  # type: ignore[index]
    assert body["retrieval_config"]["depth"] == 50 and body["retrieval_config"]["ks"] == [1, 3]  # type: ignore[index]
    assert body["cases_measured"] == 68  # type: ignore[index]


def test_a_retrieval_run_without_an_id_is_named_by_the_utc_moment_it_starts(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None
) -> None:
    run_retrieval(served.url, dataset, tmp_path / "out")

    assert posted(served)[0]["body"]["run_id"].endswith("Z")  # type: ignore[index]


@pytest.mark.parametrize("run_id", ["con espacio", "a" * 33, "ñandú"])
def test_a_run_id_that_does_not_fit_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, run_id: str, capsys: pytest.CaptureFixture[str]
) -> None:
    code = run_retrieval(served.url, dataset, tmp_path / "out", "--run-id", run_id)

    assert code == 2
    assert "debe cumplir" in capsys.readouterr().err
    assert served.order == []


def test_the_origin_and_the_assistant_model_come_from_the_environment_in_a_retrieval_run(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(EVAL_ORIGIN_ENV, "dev")
    monkeypatch.setenv(ASSISTANT_MODEL_ENV, "llama3.2:3b")

    run_retrieval(served.url, dataset, tmp_path / "out")

    body = posted(served)[0]["body"]
    assert (body["origin"], body["assistant_model"]) == ("dev", "llama3.2:3b")  # type: ignore[index]


def test_an_origin_that_is_not_a_short_label_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv(EVAL_ORIGIN_ENV, "el portátil de María")

    code = run_quality(served.url, dataset, tmp_path / "out")

    assert code == 2
    assert EVAL_ORIGIN_ENV in capsys.readouterr().err
    assert served.order == []


# --- the parameters of the assistant ------------------------------------------------------------------------------


def test_the_run_carries_the_prompt_versions_and_the_retrieval_the_backend_reported(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None
) -> None:
    served.settings_replies = [(200, eval_settings(top_k=6, threshold=0.5, versions={
        "chat-system": 7, "chat-small-talk": 1, "chat-context": 2, "chat-empty-context": None}))]  # fmt: skip

    run_quality(served.url, dataset, tmp_path / "out")

    body = posted(served)[0]["body"]
    assert body["retrieval_config"] == {"top_k": 6, "similarity_threshold": 0.5, "max_context_characters": 8000}  # type: ignore[index]
    assert body["prompts"][0] == {"name": "chat-system", "version": 7, "source": "LANGFUSE", "pinned": False}  # type: ignore[index]
    assert body["prompts"][3] == {"name": "chat-empty-context", "version": None, "source": "DEFAULT", "pinned": False}  # type: ignore[index]


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_a_change_of_the_assistant_during_the_measurement_makes_the_run_invalid_and_leaves_nothing(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None,
    command: object, capsys: pytest.CaptureFixture[str]
) -> None:
    served.settings_replies = [(200, eval_settings(top_k=4)), (200, eval_settings(top_k=6))]
    out = tmp_path / "out"

    code = command(served.url, dataset, out)  # type: ignore[operator]

    captured = capsys.readouterr()
    assert code == 1
    # ``retrieval`` has always printed the errors of its measurement to the standard output, ``quality`` to the error one
    assert "La configuración del asistente cambió" in captured.out + captured.err
    assert csv_files(out) == []
    assert posted(served) == []


# --- an order that spends nothing for nothing ---------------------------------------------------------------------


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_everything_that_can_stop_the_saving_is_asked_before_the_assistant_the_search_or_the_catalog(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, command: object
) -> None:
    command(served.url, dataset, tmp_path / "out")  # type: ignore[operator]

    order = served.order
    assert order[:2] == ["settings", "run-exists"]
    assert order[-2:] == ["settings", "save"]  # the settings are read again at the end, the run is sent last
    assert order.index("save") == len(order) - 1
    first_work = min(order.index(kind) for kind in ("chat", "search", "documents") if kind in order)
    assert first_work > order.index("run-exists")


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_a_run_id_that_is_already_saved_stops_the_run_before_measuring_and_writes_nothing(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, command: object,
    capsys: pytest.CaptureFixture[str]
) -> None:
    served.run_reply = (200, {"run_id": "t031-1", "kind": "QUALITY"})
    out = tmp_path / "out"

    code = command(served.url, dataset, out, "--run-id", "t031-1")  # type: ignore[operator]

    assert code == 1
    assert "Ya hay una ejecución guardada con el identificador 't031-1'" in capsys.readouterr().err
    assert served.order == ["settings", "run-exists"]
    assert csv_files(out) == []


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_a_backend_that_is_down_is_named_before_anything_is_asked_and_nothing_is_written(
    dataset: tuple[Path, Path], tmp_path: Path, with_token: None, command: object, capsys: pytest.CaptureFixture[str]
) -> None:
    out = tmp_path / "out"

    code = command("http://127.0.0.1:1", dataset, out, "--save")  # type: ignore[operator]

    assert code == 1
    assert "backend en http://127.0.0.1:1" in capsys.readouterr().err
    assert csv_files(out) == []


def test_a_wrong_token_says_what_to_check_and_never_prints_the_token(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, capsys: pytest.CaptureFixture[str]
) -> None:
    served.settings_replies = [(403, {"code": "SLHD_EVAL_00001"})]

    code = run_quality(served.url, dataset, tmp_path / "out")

    err = capsys.readouterr().err
    assert code == 1
    assert "rechazó el token" in err and "shoplab.eval.persistence.token" in err
    assert TOKEN not in err
    assert served.order == ["settings"]


def test_a_measurement_that_fails_halfway_saves_nothing_and_writes_nothing(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, capsys: pytest.CaptureFixture[str]
) -> None:
    served.chat_handler = lambda message, headers: ChatReply(status=500, body={"code": "x"})
    out = tmp_path / "out"

    code = run_quality(served.url, dataset, out)

    assert code == 1
    assert posted(served) == []
    assert csv_files(out) == []


def test_a_run_that_cannot_be_saved_at_the_end_keeps_the_files_and_says_nothing_of_it_was_stored(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, capsys: pytest.CaptureFixture[str]
) -> None:
    served.save_handler = lambda payload: (503, {"code": "SLHD_EVAL_00005"})
    out = tmp_path / "out"

    code = run_quality(served.url, dataset, out)

    err = capsys.readouterr().err
    assert code == 1
    assert csv_files(out) == [QUALITY_DETAIL_FILE, QUALITY_SUMMARY_FILE]
    assert "NO se guardó" in err and "no quedó nada de ella en la base" in err and "Los CSV sí están escritos" in err


# --- whoever does not save sees what they saw before ---------------------------------------------------------------


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_without_a_token_and_without_the_option_nothing_is_asked_of_the_history_and_the_files_are_the_same(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, command: object, capsys: pytest.CaptureFixture[str]
) -> None:
    out = tmp_path / "out"

    code = command(served.url, dataset, out)  # type: ignore[operator]

    assert code == 0
    assert served.eval_requests == []
    assert "No se guardó nada en el historial" in capsys.readouterr().err
    assert len(csv_files(out)) == 2


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_no_save_never_saves_even_with_a_token(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, with_token: None, command: object
) -> None:
    code = command(served.url, dataset, tmp_path / "out", "--no-save")  # type: ignore[operator]

    assert code == 0
    assert served.eval_requests == []


@pytest.mark.parametrize("command", [run_quality, run_retrieval])
def test_save_without_a_token_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, command: object, capsys: pytest.CaptureFixture[str]
) -> None:
    code = command(served.url, dataset, tmp_path / "out", "--save")  # type: ignore[operator]

    assert code == 2
    assert EVAL_TOKEN_ENV in capsys.readouterr().err
    assert served.order == []
    assert csv_files(tmp_path / "out") == []


def test_the_options_are_in_the_help_of_both_commands(capsys: pytest.CaptureFixture[str]) -> None:
    for command in ("quality", "retrieval"):
        with pytest.raises(SystemExit):
            main([command, "--help"])
        help_text = capsys.readouterr().out
        assert "--save" in help_text and "--no-save" in help_text and EVAL_TOKEN_ENV in help_text
