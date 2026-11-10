"""Tables, examples and charts shown by the notebook, and the results file."""

from dataclasses import dataclass

import matplotlib

matplotlib.use("Agg", force=False)
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from chunking_lab.strategies import Chunk, Strategy
from chunking_lab.text import normalize

PROBE_LENGTH = 20


def size_summary(chunks_by_strategy: dict[Strategy, list[Chunk]]) -> dict[Strategy, dict]:
    """Chunk count and min / mean / max size in characters, per strategy and always in the canonical order."""
    summary: dict[Strategy, dict] = {}
    for strategy in Strategy:
        chunks = chunks_by_strategy.get(strategy)
        if chunks is None:
            continue
        sizes = [len(c.text) for c in chunks]
        summary[strategy] = {
            "chunk_count": len(sizes),
            "size_min": min(sizes),
            "size_mean": round(sum(sizes) / len(sizes), 1),
            "size_max": max(sizes),
        }
    return summary


@dataclass(frozen=True)
class CutExample:
    """The chunks of one document that contain the start of a passage; `cut` is True when none holds it whole."""

    chunks: list[Chunk]
    cut: bool


def cut_example(chunks_by_strategy: dict[Strategy, list[Chunk]], document_id: str,
                passage: str) -> dict[Strategy, CutExample]:
    wanted = normalize(passage)
    probe = wanted[:PROBE_LENGTH]
    example: dict[Strategy, CutExample] = {}
    for strategy in Strategy:
        chunks = chunks_by_strategy.get(strategy)
        if chunks is None:
            continue
        own = [c for c in chunks if c.document == document_id]
        starting = [c for c in own if probe in normalize(c.text)]
        example[strategy] = CutExample(chunks=starting, cut=not any(wanted in normalize(c.text) for c in own))
    return example


STRATEGY_LABELS = {Strategy.FIXED: "Tamaño fijo", Strategy.HEADINGS: "Por encabezados", Strategy.SEMANTIC: "Semántica"}


def plot_size_histograms(chunks_by_strategy: dict[Strategy, list[Chunk]], chunk_size: int):
    """One histogram of chunk sizes per strategy, on a shared axis, with the size cap marked."""
    strategies = [s for s in Strategy if s in chunks_by_strategy]
    figure, axes = plt.subplots(1, len(strategies), figsize=(4 * len(strategies), 3), sharex=True, sharey=True)
    axes = np.atleast_1d(axes)
    for axis, strategy in zip(axes, strategies, strict=True):
        axis.hist([len(c.text) for c in chunks_by_strategy[strategy]], bins=range(0, chunk_size + 100, 100))
        axis.axvline(chunk_size, color="tab:red", linestyle="--", linewidth=1)
        axis.set_title(STRATEGY_LABELS[strategy])
        axis.set_xlabel("caracteres por chunk")
    axes[0].set_ylabel("chunks")
    figure.tight_layout()
    return figure


def plot_distance_curve(distances: np.ndarray, threshold: float, title: str):
    """Distance between consecutive units of one document; the cuts are the points above the threshold."""
    figure, axis = plt.subplots(figsize=(8, 2.8))
    positions = np.arange(len(distances))
    axis.plot(positions, distances, marker="o", linewidth=1)
    axis.axhline(threshold, color="tab:red", linestyle="--", linewidth=1, label="umbral (percentil)")
    above = distances > threshold
    axis.scatter(positions[above], distances[above], color="tab:red", zorder=3, label="corte")
    axis.set_xlabel("unidad i (distancia con la i + 1)")
    axis.set_ylabel("distancia coseno")
    axis.set_title(title)
    axis.legend()
    figure.tight_layout()
    return figure
