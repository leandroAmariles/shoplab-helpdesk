"""The ``retrieval`` command end to end: the real client against a local server that plays the backend."""

from pathlib import Path

import pytest

from fake_backend import FakeBackend, indexed_documents, ok_search
from helpers import valid_default_set, write_golden
from shoplab_eval.__main__ import main
from shoplab_eval.golden import parse_golden


@pytest.fixture
def dataset(tmp_path: Path, toy_kb: Path) -> tuple[Path, Path]:
    return write_golden(tmp_path / "golden.jsonl", valid_default_set()), toy_kb


def _serve_the_expected_documents(backend: FakeBackend, golden: Path, miss: tuple[str, ...] = ()) -> None:
    """The backend answers every measurable question with its expected documents first, except the ``miss`` ones."""
    cases = parse_golden(golden).cases
    rankings: dict[str, list[str]] = {}
    for case in cases:
        if not case.expected_documents:
            continue
        if case.id in miss:
            rankings[case.question] = ["shipping-01-a.md"]
        else:
            rankings[case.question] = [f"{doc}.md" for doc in case.expected_documents]
    backend.search_handler = ok_search(rankings)
    every_expected = sorted({doc for case in cases for doc in case.expected_documents})
    backend.documents_reply = (200, indexed_documents(*every_expected))


def test_the_command_prints_the_summary_by_scope_and_succeeds(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden)

    code = main(["retrieval", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb),
                 "--out", str(tmp_path / "out")])

    printed = capsys.readouterr().out
    assert code == 0
    for scope in ("all", "payments", "privacy", "returns", "shipping", "warranty"):
        assert scope in printed
    assert "off_topic" not in printed
    assert "bge-m3" in printed
    assert "68 casos medidos" in printed
    assert "12 excluidos" in printed


def test_the_command_searches_each_measurable_question_once_with_the_default_depth(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden)

    main(["retrieval", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb),
          "--out", str(tmp_path / "out")])

    assert len(backend.search_requests) == 68
    assert {request["limit"] for request in backend.search_requests} == {"50"}


def test_a_miss_lowers_the_numbers_but_the_command_still_succeeds(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden, miss=("G001", "G002"))

    code = main(["retrieval", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb),
                 "--k", "1", "--out", str(tmp_path / "out")])

    assert code == 0
    assert "0.9706" in capsys.readouterr().out  # 66 of 68 cases hit


def test_the_command_writes_the_summary_and_the_detail_and_prints_their_paths(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden)
    out = tmp_path / "out"

    code = main(["retrieval", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb), "--out", str(out)])

    assert code == 0
    summary = (out / "retrieval-summary.csv").read_text(encoding="utf-8").splitlines()
    detail = (out / "retrieval-detail.csv").read_text(encoding="utf-8").splitlines()
    assert summary[1].startswith("all,68,12,bge-m3,50,")
    assert len(summary) == 1 + 1 + 5
    assert len(detail) == 1 + 68
    printed = capsys.readouterr().out
    assert str(out / "retrieval-summary.csv") in printed
    assert str(out / "retrieval-detail.csv") in printed


def test_two_runs_with_nothing_changed_write_identical_files(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden, miss=("G003",))
    base = ["retrieval", "--base-url", backend.url, "--golden", str(golden), "--kb", str(kb)]

    main([*base, "--out", str(tmp_path / "first")])
    main([*base, "--out", str(tmp_path / "second")])

    for name in ("retrieval-summary.csv", "retrieval-detail.csv"):
        assert (tmp_path / "first" / name).read_bytes() == (tmp_path / "second" / name).read_bytes()


# --- the measurement is not valid: errors, exit codes and no CSV (US3) ---

CSV_NAMES = ("retrieval-summary.csv", "retrieval-detail.csv")


def _run(backend_url: str, golden: Path, kb: Path, out: Path, *extra: str) -> int:
    return main(["retrieval", "--base-url", backend_url, "--golden", str(golden), "--kb", str(kb),
                 "--out", str(out), *extra])


def _no_csv(out: Path) -> bool:
    return not any((out / name).exists() for name in CSV_NAMES)


def test_an_invalid_dataset_fails_with_its_codes_and_writes_nothing(
    backend: FakeBackend, tmp_path: Path, toy_kb: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    cases = valid_default_set()
    cases[1]["question"] = cases[0]["question"]
    golden = write_golden(tmp_path / "golden.jsonl", cases)
    out = tmp_path / "out"

    code = _run(backend.url, golden, toy_kb, out)

    assert code == 1
    assert "G-DUPLICATE" in capsys.readouterr().out
    assert _no_csv(out)
    assert backend.search_requests == []


def test_an_unreachable_backend_fails_with_the_url_and_writes_nothing(
    dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    out = tmp_path / "out"
    url = "http://127.0.0.1:1"

    code = _run(url, golden, kb, out, "--timeout", "0.3")

    assert code == 1
    assert url in capsys.readouterr().out
    assert _no_csv(out)


def test_an_incomplete_index_fails_listing_the_documents_before_any_search(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden)
    backend.documents_reply = (200, indexed_documents("payments-01-a", "payments-02-b")
                               + indexed_documents("returns-01-a", status="PENDING"))
    out = tmp_path / "out"

    code = _run(backend.url, golden, kb, out)

    printed = capsys.readouterr().out
    assert code == 1
    assert "privacy-01-a" in printed
    assert "returns-01-a" in printed
    assert "PENDING" in printed
    assert backend.search_requests == []
    assert _no_csv(out)


def test_a_failure_in_the_middle_writes_nothing_and_leaves_an_earlier_result_alone(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset
    _serve_the_expected_documents(backend, golden)
    healthy = backend.search_handler
    calls = {"count": 0}

    def fails_on_the_fortieth(query: str, limit: int | None) -> tuple[int, object]:
        calls["count"] += 1
        if calls["count"] == 40:
            return 503, {"code": "SLHD_RETR_00006", "message": "modelo caído"}
        return healthy(query, limit)

    backend.search_handler = fails_on_the_fortieth
    out = tmp_path / "out"
    out.mkdir()
    previous = out / "retrieval-summary.csv"
    previous.write_text("resultado anterior\n", encoding="utf-8")

    code = _run(backend.url, golden, kb, out)

    assert code == 1
    assert calls["count"] == 40
    assert "503" in capsys.readouterr().out
    assert previous.read_text(encoding="utf-8") == "resultado anterior\n"
    assert not (out / "retrieval-detail.csv").exists()


@pytest.mark.parametrize(
    "extra",
    [["--k", "0"], ["--k", "-1"], ["--k", "3,x"], ["--k", "3,3"], ["--k", ""], ["--k", "5", "--depth", "3"],
     ["--depth", "0"], ["--depth", "51"]],
)
def test_an_invalid_k_or_depth_is_a_usage_error_before_any_request(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str],
    extra: list[str]
) -> None:
    golden, kb = dataset
    out = tmp_path / "out"

    code = _run(backend.url, golden, kb, out, *extra)

    assert code == 2
    assert capsys.readouterr().err.strip() != ""
    assert backend.search_requests == []
    assert backend.document_requests == 0
    assert _no_csv(out)


def test_the_usage_error_gives_the_rejected_value_and_the_accepted_range(
    backend: FakeBackend, dataset: tuple[Path, Path], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    golden, kb = dataset

    _run(backend.url, golden, kb, tmp_path / "out", "--k", "0")
    k_message = capsys.readouterr().err
    _run(backend.url, golden, kb, tmp_path / "out", "--depth", "51")
    depth_message = capsys.readouterr().err

    assert "0" in k_message
    assert "enteros positivos" in k_message
    assert "51" in depth_message
    assert "1" in depth_message
    assert "50" in depth_message
