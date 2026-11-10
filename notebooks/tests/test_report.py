import json

import numpy as np
import pytest

from chunking_lab.config import ExperimentConfig
from chunking_lab.questions import Question
from chunking_lab.report import (
    build_results,
    cut_example,
    plot_distance_curve,
    plot_metrics,
    plot_size_histograms,
    read_results,
    results_digest,
    results_table,
    size_summary,
    validate_results,
    write_results,
)
from chunking_lab.strategies import Chunk, Strategy


def chunk(strategy: Strategy, position: int, text: str, document: str = "returns-01-x") -> Chunk:
    return Chunk(strategy=strategy, document=document, position=position, section="", text=text)


# --- Part 1: sizes and cut examples (US1) ---------------------------------------------------------------------------

def test_size_summary_has_count_min_mean_max_per_strategy_in_fixed_order():
    chunks = {
        Strategy.SEMANTIC: [chunk(Strategy.SEMANTIC, 0, "a" * 10)],
        Strategy.FIXED: [chunk(Strategy.FIXED, 0, "a" * 100), chunk(Strategy.FIXED, 1, "a" * 51)],
        Strategy.HEADINGS: [chunk(Strategy.HEADINGS, i, "a" * (i + 1)) for i in range(3)],
    }
    summary = size_summary(chunks)
    assert list(summary) == [Strategy.FIXED, Strategy.HEADINGS, Strategy.SEMANTIC]
    assert summary[Strategy.FIXED] == {"chunk_count": 2, "size_min": 51, "size_mean": 75.5, "size_max": 100}
    assert summary[Strategy.HEADINGS] == {"chunk_count": 3, "size_min": 1, "size_mean": 2.0, "size_max": 3}
    assert summary[Strategy.SEMANTIC]["size_mean"] == 10.0


def test_size_mean_is_rounded_to_one_decimal():
    chunks = {Strategy.FIXED: [chunk(Strategy.FIXED, i, "a" * n) for i, n in enumerate((1, 1, 2))]}
    assert size_summary(chunks)[Strategy.FIXED]["size_mean"] == pytest.approx(1.3)


PASSAGE = "El plazo es de siete días corridos."


def test_cut_example_finds_the_chunks_with_the_start_of_the_passage_and_flags_cuts():
    chunks = {
        Strategy.FIXED: [chunk(Strategy.FIXED, 0, "intro El plazo es de siete"),
                         chunk(Strategy.FIXED, 1, "días corridos. más texto")],
        Strategy.HEADINGS: [chunk(Strategy.HEADINGS, 0, f"## Plazos\n{PASSAGE} Fin.")],
        Strategy.SEMANTIC: [chunk(Strategy.SEMANTIC, 0, "otro"), chunk(Strategy.SEMANTIC, 1, PASSAGE)],
    }
    example = cut_example(chunks, "returns-01-x", PASSAGE)
    assert example[Strategy.FIXED].cut is True
    assert [c.position for c in example[Strategy.FIXED].chunks] == [0]
    assert example[Strategy.HEADINGS].cut is False
    assert example[Strategy.SEMANTIC].cut is False
    assert [c.position for c in example[Strategy.SEMANTIC].chunks] == [1]


def test_cut_example_normalizes_whitespace_and_ignores_other_documents():
    chunks = {Strategy.FIXED: [chunk(Strategy.FIXED, 0, "El plazo\n  es de siete   días corridos.", "returns-01-x"),
                               chunk(Strategy.FIXED, 0, PASSAGE, "other-doc")]}
    example = cut_example(chunks, "returns-01-x", PASSAGE)
    assert example[Strategy.FIXED].cut is False
    assert len(example[Strategy.FIXED].chunks) == 1


def test_plots_return_figures():
    chunks = {s: [chunk(s, 0, "a" * 120), chunk(s, 1, "b" * 480)] for s in Strategy}
    assert len(plot_size_histograms(chunks, 1000).axes) == 3
    assert len(plot_distance_curve(np.array([0.1, 0.5, 0.2]), 0.3, "doc").axes) == 1


# --- Part 2: results (US2) --------------------------------------------------------------------------------------------

SIZES = {s: {"chunk_count": 10, "size_min": 5, "size_mean": 100.04, "size_max": 900} for s in Strategy}


def questions(n: int = 20) -> list[Question]:
    return [Question(id=f"Q{i:02d}", topic="returns", question="¿?", document="returns-01-x", passage="p" * 40)
            for i in range(1, n + 1)]


def ranks(pattern: dict[Strategy, list]) -> dict[Strategy, dict[str, int | None]]:
    return {s: {f"Q{i:02d}": r for i, r in enumerate(values, start=1)} for s, values in pattern.items()}


def sample(n: int = 20):
    fixed = [1, 2, None, 4] + [1] * (n - 4)
    headings = [1] * n
    semantic = [None] * 2 + [3] * (n - 2)
    return build_results(ExperimentConfig(), SIZES, ranks({Strategy.FIXED: fixed, Strategy.HEADINGS: headings,
                                                           Strategy.SEMANTIC: semantic}),
                         questions(n), "abc123", 24, "2026-11-10T22:15:00Z")


def test_build_results_follows_the_contract_layout_and_order():
    results = sample()
    assert list(results) == ["meta", "summary", "rows"]
    assert results["meta"]["format_version"] == 1
    assert list(results["meta"]["params"]) == [
        "chunk_size", "chunk_overlap", "top_k", "breakpoint_percentile", "min_chunk_chars"]
    assert list(results["summary"]) == ["fixed", "headings", "semantic"]
    assert list(results["summary"]["fixed"]) == [
        "chunk_count", "size_min", "size_mean", "size_max", "hit_rate", "mrr", "misses"]
    assert len(results["rows"]) == 20
    assert results["rows"][2]["rank"] == {"fixed": None, "headings": 1, "semantic": 3}
    assert "score" not in json.dumps(results)


def test_build_results_computes_and_rounds_the_metrics():
    summary = sample()["summary"]
    assert summary["fixed"]["misses"] == 1
    assert summary["fixed"]["hit_rate"] == 0.95
    assert summary["fixed"]["mrr"] == round((1 + 0.5 + 0 + 0.25 + 16) / 20, 4)
    assert summary["fixed"]["size_mean"] == 100.0
    assert summary["headings"]["hit_rate"] == 1.0
    assert summary["semantic"]["misses"] == 2


def test_write_and_read_round_trip(tmp_path):
    results = sample()
    path = tmp_path / "results" / "chunking-results.json"
    write_results(results, path)
    text = path.read_text(encoding="utf-8")
    assert text.startswith('{\n  "meta"')
    assert text.endswith("}\n")
    assert read_results(path) == results


def test_validate_accepts_a_coherent_result():
    validate_results(sample())


def test_validate_rejects_the_wrong_number_of_rows_and_duplicate_ids():
    results = sample()
    results["rows"] = results["rows"][:19]
    with pytest.raises(ValueError, match="20"):
        validate_results(results)
    results = sample()
    results["rows"][1]["id"] = "Q01"
    with pytest.raises(ValueError, match="repetid"):
        validate_results(results)


def test_validate_rejects_ranks_outside_top_k_and_incoherent_metrics():
    results = sample()
    results["rows"][0]["rank"]["fixed"] = 5
    with pytest.raises(ValueError, match="rank"):
        validate_results(results)
    results = sample()
    results["summary"]["fixed"]["misses"] = 0
    with pytest.raises(ValueError, match="misses"):
        validate_results(results)
    results = sample()
    results["summary"]["fixed"]["hit_rate"] = 0.5
    with pytest.raises(ValueError, match="hit_rate"):
        validate_results(results)
    results = sample()
    results["summary"]["fixed"]["mrr"] = 0.99
    with pytest.raises(ValueError, match="mrr"):
        validate_results(results)


def test_results_table_has_one_row_per_question_and_a_summary():
    table = results_table(sample())
    assert len(table) == 20 + 2
    assert list(table.columns)[-3:] == ["Tamaño fijo", "Por encabezados", "Semántica"]
    assert table.iloc[2]["Tamaño fijo"] == "no encontrado"
    assert table.iloc[2]["Por encabezados"] == "1"
    assert table.iloc[-2]["pregunta"] == "hit rate@4"
    assert table.iloc[-1]["pregunta"] == "MRR"
    assert table.iloc[-2]["Por encabezados"] == "100.0%"


def test_plot_metrics_returns_a_figure():
    assert len(plot_metrics(sample()).axes) >= 1


def test_results_file_has_lf_endings_and_a_digest_that_ignores_crlf(tmp_path):
    path = tmp_path / "r.json"
    write_results(sample(), path)
    crlf, lf = bytes([13, 10]), bytes([10])
    assert crlf not in path.read_bytes()
    digest = results_digest(path)
    path.write_bytes(path.read_bytes().replace(lf, crlf))
    assert results_digest(path) == digest
