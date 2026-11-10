"""Tables, examples and charts shown by the notebook, and the results file."""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg", force=False)
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from chunking_lab.config import ExperimentConfig  # noqa: E402
from chunking_lab.metrics import hit_rate, is_correct, mean_reciprocal_rank  # noqa: E402
from chunking_lab.questions import EXPECTED_TOTAL, Question  # noqa: E402
from chunking_lab.retrieval import Retrieval  # noqa: E402
from chunking_lab.strategies import Chunk, Strategy  # noqa: E402
from chunking_lab.text import normalize  # noqa: E402

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


# --- Results file and the 20-row table ----------------------------------------------------------------------------

FORMAT_VERSION = 1
PARAM_KEYS = ("chunk_size", "chunk_overlap", "top_k", "breakpoint_percentile", "min_chunk_chars")
NOT_FOUND = "no encontrado"


def build_results(config: ExperimentConfig, sizes: dict[Strategy, dict],
                  ranks: dict[Strategy, dict[str, int | None]], questions: list[Question],
                  model_digest: str, corpus_documents: int, generated_at: str) -> dict:
    """The structure of results/chunking-results.json (contracts/results-format.md). Scores are not stored."""
    summary = {}
    for strategy in Strategy:
        values = [ranks[strategy][q.id] for q in questions]
        summary[strategy.value] = {
            **sizes[strategy],
            "size_mean": round(sizes[strategy]["size_mean"], 1),
            "hit_rate": round(hit_rate(values), 4),
            "mrr": round(mean_reciprocal_rank(values), 4),
            "misses": sum(1 for r in values if r is None),
        }
    return {
        "meta": {
            "format_version": FORMAT_VERSION,
            "generated_at": generated_at,
            "embed_model": config.embed_model,
            "embed_model_digest": model_digest,
            "corpus_documents": corpus_documents,
            "params": {key: getattr(config, key) for key in PARAM_KEYS},
        },
        "summary": summary,
        "rows": [
            {"id": q.id, "topic": q.topic, "document": q.document,
             "rank": {s.value: ranks[s][q.id] for s in Strategy}}
            for q in questions
        ],
    }


def write_results(results: dict, path: Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Bytes, not write_text: the file must have LF endings on every operating system.
    path.write_bytes((json.dumps(results, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


CRLF = bytes([13, 10])
LF = bytes([10])


def results_digest(path: Path) -> str:
    """sha256 of the results file with CRLF read as LF, so Git's line-ending conversion cannot change it."""
    return hashlib.sha256(Path(path).read_bytes().replace(CRLF, LF)).hexdigest()


def read_results(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def validate_results(results: dict, expected_rows: int = EXPECTED_TOTAL) -> None:
    """Raises ValueError if the file breaks the coherence rules of the contract."""
    rows = results["rows"]
    top_k = results["meta"]["params"]["top_k"]
    if len(rows) != expected_rows:
        raise ValueError(f"rows debe tener exactamente {expected_rows} filas y tiene {len(rows)}")
    ids = [row["id"] for row in rows]
    if len(set(ids)) != len(ids):
        raise ValueError("rows tiene ids repetidos")
    for strategy in Strategy:
        values = [row["rank"][strategy.value] for row in rows]
        for row, value in zip(rows, values, strict=True):
            if value is not None and not 1 <= value <= top_k:
                raise ValueError(f"rank de {row['id']} en {strategy.value} fuera de [1, {top_k}]: {value}")
        summary = results["summary"][strategy.value]
        misses = sum(1 for v in values if v is None)
        if summary["misses"] != misses:
            raise ValueError(f"misses de {strategy.value} es {summary['misses']} y hay {misses} nulos")
        if summary["hit_rate"] != round(hit_rate(values), 4):
            raise ValueError(f"hit_rate de {strategy.value} no coincide con las filas")
        if summary["mrr"] != round(mean_reciprocal_rank(values), 4):
            raise ValueError(f"mrr de {strategy.value} no coincide con las filas")


def results_table(results: dict) -> pd.DataFrame:
    """One row per question with the rank of each strategy, plus the hit rate and MRR rows."""
    top_k = results["meta"]["params"]["top_k"]
    labels = [STRATEGY_LABELS[s] for s in Strategy]
    rows = []
    for row in results["rows"]:
        rows.append([row["id"], row["topic"], row["document"],
                     *(NOT_FOUND if row["rank"][s.value] is None else str(row["rank"][s.value]) for s in Strategy)])
    summary = results["summary"]
    rows.append([f"hit rate@{top_k}", "", "", *(f"{summary[s.value]['hit_rate'] * 100:.1f}%" for s in Strategy)])
    rows.append(["MRR", "", "", *(f"{summary[s.value]['mrr']:.3f}" for s in Strategy)])
    return pd.DataFrame(rows, columns=["pregunta", "tema", "documento esperado", *labels])


def plot_metrics(results: dict):
    """Hit rate@k and MRR per strategy as grouped bars."""
    strategies = list(Strategy)
    positions = np.arange(len(strategies))
    figure, axis = plt.subplots(figsize=(6, 3))
    axis.bar(positions - 0.2, [results["summary"][s.value]["hit_rate"] for s in strategies], 0.4, label="hit rate@k")
    axis.bar(positions + 0.2, [results["summary"][s.value]["mrr"] for s in strategies], 0.4, label="MRR")
    axis.set_xticks(positions, [STRATEGY_LABELS[s] for s in strategies])
    axis.set_ylim(0, 1.05)
    axis.legend()
    figure.tight_layout()
    return figure


# --- Reading the results: headline, differing cases, retrieved chunks ----------------------------------------------

def _best(values: dict[Strategy, float]) -> tuple[list[Strategy], float, float]:
    """Strategies sharing the highest value, that value, and the gap to the best of the others (0 if none)."""
    top = max(values.values())
    winners = [s for s in Strategy if values[s] == top]
    rest = [v for s, v in values.items() if s not in winners]
    return winners, top, (top - max(rest)) if rest else 0.0


def _names(strategies: list[Strategy]) -> str:
    labels = [STRATEGY_LABELS[s] for s in strategies]
    return labels[0] if len(labels) == 1 else ", ".join(labels[:-1]) + " y " + labels[-1]


def headline(results: dict) -> str:
    """One generated paragraph with the best strategy by MRR and by hit rate, so it never contradicts the table."""
    top_k = results["meta"]["params"]["top_k"]
    summary = results["summary"]
    metrics = (
        ("MRR", "mrr", lambda value: f"{value:.3f}", lambda gap: f"{gap:.3f}"),
        (f"hit rate@{top_k}", "hit_rate", lambda value: f"{value:.1%}", lambda gap: f"{gap * 100:.1f} puntos"),
    )
    parts = []
    for name, key, show, show_gap in metrics:
        winners, top, gap = _best({s: summary[s.value][key] for s in Strategy})
        if len(winners) == len(Strategy):
            parts.append(f"en {name} las tres estrategias empatan ({show(top)})")
        elif len(winners) > 1:
            parts.append(f"en {name} empatan {_names(winners)} ({show(top)}), {show_gap(gap)} por encima de la otra")
        else:
            parts.append(f"en {name} gana {_names(winners)} ({show(top)}), {show_gap(gap)} por encima de la siguiente")
    return "Con las 20 preguntas de prueba: " + "; ".join(parts) + "."


def differing_cases(results: dict) -> list[dict]:
    """Rows in which the strategies do not all get the same rank, widest gap first (not found = top_k + 1)."""
    worst = results["meta"]["params"]["top_k"] + 1

    def spread(row: dict) -> int:
        values = [worst if r is None else r for r in row["rank"].values()]
        return max(values) - min(values)

    cases = [row for row in results["rows"] if spread(row) > 0]
    return sorted(cases, key=lambda row: (-spread(row), row["id"]))


def retrieval_rows(retrieval: Retrieval, question: Question) -> list[dict]:
    """The chunks a strategy retrieved for one question, in order, flagging the correct one."""
    return [{"place": place, "score": hit.score, "document": hit.chunk.document, "position": hit.chunk.position,
             "section": hit.chunk.section, "correct": is_correct(hit.chunk, question), "text": hit.chunk.text}
            for place, hit in enumerate(retrieval.hits, start=1)]
