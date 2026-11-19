"""How the judge is configured, and what the command checks and records about it (specs/LAB-29, FR-007, FR-008, FR-018).

The first half is pure (flags, environment and defaults); the second half runs the command against a local backend and
a local gateway. The judge itself is a double: what is under test is the configuration, not a model.
"""

from collections.abc import Iterator, Sequence
from pathlib import Path
from types import SimpleNamespace

import pytest

from fake_backend import FakeBackend, chat_turn, indexed_documents, source
from fake_gateway import GATEWAY_KEY, FakeGateway
from helpers import TOY_DOCS, valid_default_set, write_golden
from shoplab_eval import __main__ as cli
from shoplab_eval.__main__ import main
from shoplab_eval.constants import (
    DEFAULT_JUDGE_BASE_URL,
    DEFAULT_JUDGE_MODEL,
    JUDGE_API_KEY_ENV,
    JUDGE_BASE_URL_ENV,
    JUDGE_MODEL_ENV,
    QUALITY_DETAIL_FILE,
    QUALITY_SUMMARY_FILE,
)
from shoplab_eval.quality_judge import JudgeSettings, read_judge_api_key, resolve_judge_settings


def flags(**overrides: object) -> SimpleNamespace:
    """The options of the command as ``argparse`` leaves them: ``None`` means «not given»."""
    values = {
        "judge_model": None,
        "judge_base_url": None,
        "judge_temperature": None,
        "judge_timeout": None,
        "max_input_chars": None,
        "concurrency": None,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


# --- flag, then environment, then default ---------------------------------------------------------------------


def test_with_nothing_given_the_judge_uses_the_defaults() -> None:
    expected = JudgeSettings(model=DEFAULT_JUDGE_MODEL, base_url=DEFAULT_JUDGE_BASE_URL)

    assert resolve_judge_settings(flags(), {}) == expected


def test_the_environment_gives_the_model_and_the_gateway_when_no_flag_does() -> None:
    env = {JUDGE_MODEL_ENV: "judge-grande", JUDGE_BASE_URL_ENV: "http://gateway:4000/v1"}

    settings = resolve_judge_settings(flags(), env)

    assert (settings.model, settings.base_url) == ("judge-grande", "http://gateway:4000/v1")


def test_a_flag_wins_over_the_environment() -> None:
    env = {JUDGE_MODEL_ENV: "del-entorno", JUDGE_BASE_URL_ENV: "http://entorno:4000/v1"}

    settings = resolve_judge_settings(flags(judge_model="del-flag", judge_base_url="http://flag:4000/v1"), env)

    assert (settings.model, settings.base_url) == ("del-flag", "http://flag:4000/v1")


def test_the_numeric_options_come_from_their_flags() -> None:
    settings = resolve_judge_settings(
        flags(judge_temperature=0.3, judge_timeout=45, max_input_chars=9000, concurrency=4), {}
    )

    assert (settings.temperature, settings.timeout, settings.max_input_chars, settings.concurrency) == (0.3, 45, 9000, 4)


# --- a value that cannot work is rejected with the value that does --------------------------------------------


@pytest.mark.parametrize(
    ("option", "value", "expected"),
    [
        ("judge_model", "   ", "--judge-model"),
        ("judge_base_url", "localhost:4000/v1", "--judge-base-url.*http"),
        ("judge_temperature", -0.1, "--judge-temperature.*0 y 2"),
        ("judge_temperature", 2.5, "--judge-temperature.*0 y 2"),
        ("judge_timeout", 0, "--judge-timeout.*mayor que 0"),
        ("judge_timeout", -3, "--judge-timeout.*mayor que 0"),
        ("max_input_chars", 0, "--max-input-chars.*mayor que 0"),
        ("concurrency", 0, "--concurrency.*1 y 16"),
        ("concurrency", 17, "--concurrency.*1 y 16"),
    ],
)
def test_an_invalid_option_says_which_one_and_what_is_accepted(option: str, value: object, expected: str) -> None:
    with pytest.raises(ValueError, match=expected):
        resolve_judge_settings(flags(**{option: value}), {})


def test_an_empty_model_in_the_environment_is_rejected_too() -> None:
    with pytest.raises(ValueError, match="--judge-model"):
        resolve_judge_settings(flags(), {JUDGE_MODEL_ENV: "  "})


# --- the key comes only from the environment ------------------------------------------------------------------


def test_the_key_is_read_from_the_environment_and_trimmed() -> None:
    assert read_judge_api_key({JUDGE_API_KEY_ENV: "  sk-eval  "}) == "sk-eval"


@pytest.mark.parametrize("env", [{}, {JUDGE_API_KEY_ENV: ""}, {JUDGE_API_KEY_ENV: "   "}])
def test_a_missing_key_names_the_variable_to_define(env: dict[str, str]) -> None:
    with pytest.raises(ValueError, match=JUDGE_API_KEY_ENV):
        read_judge_api_key(env)


def test_there_is_no_option_to_pass_the_key_on_the_command_line(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit):
        main(["quality", "--judge-api-key", "sk-x"])

    assert "unrecognized arguments" in capsys.readouterr().err


# --- the command: preflight, record and never print the key ---------------------------------------------------


@pytest.fixture
def gateway() -> Iterator[FakeGateway]:
    server = FakeGateway().start()
    try:
        yield server
    finally:
        server.stop()


class QuietJudge:
    async def abstains(self, question: str, answer: str) -> bool:
        return False

    async def faithfulness(self, question: str, answer: str, contexts: Sequence[str]) -> float | None:
        return 0.9

    async def answer_relevancy(self, question: str, answer: str) -> float | None:
        return 0.6

    async def context_precision(self, question: str, reference: str, contexts: Sequence[str]) -> float | None:
        return 1.0


@pytest.fixture(autouse=True)
def environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli, "build_judge", lambda settings, api_key: QuietJudge())
    monkeypatch.setenv(JUDGE_API_KEY_ENV, GATEWAY_KEY)
    monkeypatch.delenv(JUDGE_MODEL_ENV, raising=False)
    monkeypatch.delenv(JUDGE_BASE_URL_ENV, raising=False)


@pytest.fixture
def served(backend: FakeBackend) -> FakeBackend:
    backend.chat_handler = lambda message, headers: chat_turn(
        "Hasta 12 cuotas.", sources=[source(1, "payments-01-a", "Hasta 12 cuotas sin interés.")]
    )
    backend.documents_reply = (200, indexed_documents(*TOY_DOCS))  # the whole corpus is indexed
    return backend


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


def quality(
    backend: FakeBackend, gateway: FakeGateway, dataset: tuple[Path, Path], out: Path, *extra: str
) -> int:
    golden, kb = dataset
    return main(
        ["quality", "--base-url", backend.url, "--judge-base-url", gateway.url, "--golden", str(golden),
         "--kb", str(kb), "--out", str(out), "--no-langfuse", *extra]
    )  # fmt: skip


def summary_row(out: Path) -> dict[str, str]:
    import csv

    with (out / QUALITY_SUMMARY_FILE).open(encoding="utf-8", newline="") as handle:
        return next(csv.DictReader(handle))


def test_the_summary_records_the_logical_name_and_the_real_model_behind_it(
    served: FakeBackend, gateway: FakeGateway, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    code = quality(served, gateway, dataset, tmp_path / "out")

    assert code == 0
    row = summary_row(tmp_path / "out")
    assert row["judge_model"] == "judge"
    assert row["judge_underlying_model"] == "ollama_chat/qwen2.5:7b"


def test_changing_the_model_behind_the_name_is_visible_in_the_summary_with_no_change_of_code(
    served: FakeBackend, gateway: FakeGateway, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    quality(served, gateway, dataset, tmp_path / "antes")
    gateway.models["judge"] = "ollama_chat/llama3.1:8b"
    quality(served, gateway, dataset, tmp_path / "despues")

    assert summary_row(tmp_path / "antes")["judge_model"] == summary_row(tmp_path / "despues")["judge_model"]
    assert summary_row(tmp_path / "antes")["judge_underlying_model"] != summary_row(tmp_path / "despues")[
        "judge_underlying_model"
    ]


def test_a_model_behind_the_name_that_cannot_be_read_is_recorded_as_unknown_with_a_warning(
    served: FakeBackend,
    gateway: FakeGateway,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    gateway.info_status = 403

    code = quality(served, gateway, dataset, tmp_path / "out")

    assert code == 0
    assert summary_row(tmp_path / "out")["judge_underlying_model"] == "unknown"
    warnings = capsys.readouterr().err
    assert "modelo real" in warnings
    assert "judge_underlying_model" in warnings


def test_the_embedding_model_is_recorded_from_a_single_search(
    served: FakeBackend, gateway: FakeGateway, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    quality(served, gateway, dataset, tmp_path / "out")

    assert summary_row(tmp_path / "out")["embedding_model"] == "bge-m3"
    assert len(served.search_requests) == 1


def test_the_gateway_and_the_model_are_checked_before_the_assistant_is_asked(
    served: FakeBackend, gateway: FakeGateway, dataset: tuple[Path, Path], tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    code = quality(served, gateway, dataset, tmp_path / "out", "--judge-model", "chat")

    assert code == 1
    assert "chat" in capsys.readouterr().err
    assert served.chat_requests == []
    assert not (tmp_path / "out").exists()


def test_a_wrong_key_fails_before_the_assistant_is_asked_and_the_key_is_not_shown(
    served: FakeBackend,
    gateway: FakeGateway,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setenv(JUDGE_API_KEY_ENV, "sk-clave-equivocada")

    code = quality(served, gateway, dataset, tmp_path / "out")

    captured = capsys.readouterr()
    assert code == 1
    assert "401" in captured.err
    assert "sk-clave-equivocada" not in captured.out + captured.err
    assert served.chat_requests == []


@pytest.mark.parametrize(
    "option",
    [["--concurrency", "0"], ["--judge-temperature", "-1"], ["--judge-timeout", "0"], ["--max-input-chars", "0"]],
)
def test_an_invalid_option_is_a_usage_error_before_anything_is_asked(
    served: FakeBackend,
    gateway: FakeGateway,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    option: list[str],
) -> None:
    code = quality(served, gateway, dataset, tmp_path / "out", *option)

    assert code == 2
    assert option[0] in capsys.readouterr().err
    assert served.chat_requests == [] and gateway.requests == []
    assert not (tmp_path / "out").exists()


def test_the_key_never_appears_in_the_output_or_in_the_files(
    served: FakeBackend,
    gateway: FakeGateway,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    out = tmp_path / "out"

    quality(served, gateway, dataset, out)

    captured = capsys.readouterr()
    assert GATEWAY_KEY not in captured.out + captured.err
    for name in (QUALITY_SUMMARY_FILE, QUALITY_DETAIL_FILE):
        assert GATEWAY_KEY not in (out / name).read_text(encoding="utf-8")


def test_the_options_of_the_judge_reach_the_judge_that_is_built(
    served: FakeBackend,
    gateway: FakeGateway,
    dataset: tuple[Path, Path],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    built: list[tuple[JudgeSettings, str]] = []
    monkeypatch.setattr(cli, "build_judge", lambda settings, key: built.append((settings, key)) or QuietJudge())

    quality(
        served, gateway, dataset, tmp_path / "out",
        "--judge-temperature", "0.2", "--judge-timeout", "30", "--max-input-chars", "5000", "--concurrency", "3",
    )  # fmt: skip

    (settings, key), = built
    assert (settings.model, settings.base_url) == ("judge", gateway.url)
    assert (settings.temperature, settings.timeout, settings.max_input_chars, settings.concurrency) == (0.2, 30, 5000, 3)
    assert key == GATEWAY_KEY
