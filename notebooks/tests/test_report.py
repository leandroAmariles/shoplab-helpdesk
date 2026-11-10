import numpy as np
import pytest

from chunking_lab.report import cut_example, plot_distance_curve, plot_size_histograms, size_summary
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
