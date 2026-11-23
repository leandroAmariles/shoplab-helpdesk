"""Datos de prueba deterministas para ver la pantalla /admin-eval con historial (specs/LAB-32, quickstart.md).

Lee eval/golden.jsonl y escribe en seed/out/ cinco cargas con el formato de
specs/LAB-31-eval-run-persistence/contracts/eval-runs-api.md:

- seed-q1, seed-q2 y seed-q3: tres ejecuciones de calidad.
  q2 cambia la versión de chat-system (3 -> 4), baja la fidelidad y reescribe el caso G001 (no comparable con q1).
  q3 tiene context_precision vacío por definición en todos los casos (un hueco, no un cero).
- seed-r1 y seed-r2: dos ejecuciones de recuperación con k distintos ([1, 3, 5] y [1, 3, 5, 10]).

Solo usa la biblioteca estándar. Las puntuaciones salen de una función determinista del identificador de la ejecución,
el del caso y el de la métrica: ejecutarlo dos veces da exactamente los mismos archivos. Ningún dato es real.

Uso:  python generate_seed.py            (escribe en ./out)
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
GOLDEN = ROOT / "eval" / "golden.jsonl"
OUT = HERE / "out"

CONVERSATION_CATEGORIES = {"small_talk", "injection"}
PROMPTS = ("chat-system", "chat-small-talk", "chat-context", "chat-empty-context")
RETRIEVAL_CONFIG = {"top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000}
START = datetime(2026, 11, 10, 14, 0, tzinfo=timezone.utc)


def digest(*parts: object) -> bytes:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).digest()


def unit(*parts: object) -> float:
    """A number in [0, 1) that depends only on its arguments."""
    return int.from_bytes(digest(*parts)[:8], "big") / 2**64


def clamp(value: float) -> float:
    return round(min(1.0, max(0.0, value)), 4)


def load_cases() -> list[dict]:
    with GOLDEN.open(encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle if line.strip()]
    return [row for row in rows if row["category"] not in CONVERSATION_CATEGORIES]


def iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def case_payload(case: dict, rewrite: bool) -> dict:
    question = case["question"]
    if rewrite:
        question = f"{question} (versión reescrita)"
    return {
        "id": case["id"],
        "category": case["category"],
        "question": question,
        "reference_answer": case["reference_answer"],
        "expected_documents": case["expected_documents"],
    }


def run_header(run_id: str, kind: str, day: int, cases_measured: int, cases_excluded: int, dataset_hash: str) -> dict:
    started = START + timedelta(days=day)
    return {
        "run_id": run_id,
        "kind": kind,
        "started_at": iso(started),
        "finished_at": iso(started + timedelta(minutes=9)),
        "origin": "dev",
        "commit_sha": digest("commit", run_id).hex()[:40],
        "dataset_hash": dataset_hash,
        "cases_measured": cases_measured,
        "cases_excluded": cases_excluded,
        "assistant_model": "llama3.2:3b",
        "embedding_model": "bge-m3",
    }


def quality_run(run_id: str, day: int, cases: list[dict], dataset_hash: str, *, system_version: int,
                faithfulness_shift: float, empty_precision: bool, rewrite: set[str]) -> dict:
    results = []
    for case in cases:
        answered = bool(case["expected_documents"])
        base = 0.35 + 0.65 * unit(run_id, case["id"], "faithfulness")
        scores: dict[str, float | None] = {"faithfulness": clamp(base + faithfulness_shift)}
        if answered:
            scores["answer_relevancy"] = clamp(0.4 + 0.6 * unit(run_id, case["id"], "answer_relevancy"))
            scores["context_precision"] = (
                None if empty_precision else clamp(0.3 + 0.7 * unit(run_id, case["id"], "context_precision"))
            )
        results.append({
            "case": case_payload(case, case["id"] in rewrite),
            "case_kind": "answered" if answered else "unanswered",
            "scores": scores,
            "retrieved_documents": list(case["expected_documents"]),
            "abstained": not answered,
            "trace_id": digest("trace", run_id, case["id"]).hex()[:32] if answered else None,
        })
    payload = run_header(run_id, "QUALITY", day, len(cases), 120 - len(cases), dataset_hash)
    payload.update({
        "prompts": [
            {"name": name, "version": system_version if name == "chat-system" else 2, "source": "LANGFUSE",
             "pinned": False}
            for name in PROMPTS
        ],
        "judge": {"model": "judge", "underlying_model": "ollama_chat/qwen2.5:7b", "temperature": 0.0, "seed": 7,
                  "max_input_chars": 24000},
        "evaluator": {"library": "ragas", "library_version": "0.4.4", "judgment_instructions_version": "1"},
        "retrieval_config": dict(RETRIEVAL_CONFIG),
        "results": results,
    })
    return payload


def retrieval_run(run_id: str, day: int, cases: list[dict], dataset_hash: str, ks: list[int],
                  pool: list[str]) -> dict:
    answered = [case for case in cases if case["expected_documents"]]
    results = []
    for case in answered:
        expected = case["expected_documents"]
        # Where each expected document would appear in the ranking: sooner for most cases, never for some.
        ranks = [1 + int(unit(run_id, case["id"], doc, "rank") ** 2 * 12) for doc in expected]
        scores: dict[str, float | None] = {"reciprocal_rank": clamp(1 / min(ranks))}
        for k in ks:
            found = sum(1 for rank in ranks if rank <= k)
            scores[f"hit_at_{k}"] = 1.0 if found else 0.0
            scores[f"recall_at_{k}"] = clamp(found / len(expected))
        retrieved = [pool[int(unit(run_id, case["id"], position) * len(pool))] for position in range(5)]
        for doc, rank in zip(expected, ranks):
            if rank <= 5:
                retrieved[rank - 1] = doc
        results.append({
            "case": case_payload(case, False),
            "case_kind": "answered",
            "scores": scores,
            "retrieved_documents": list(dict.fromkeys(retrieved)),
            "abstained": None,
            "trace_id": None,
        })
    payload = run_header(run_id, "RETRIEVAL", day, len(answered), 120 - len(answered), dataset_hash)
    payload.update({
        "prompts": [],
        "retrieval_config": {**RETRIEVAL_CONFIG, "depth": 50, "ks": ks},
        "results": results,
    })
    return payload


def main() -> None:
    cases = load_cases()
    dataset_hash = hashlib.sha256(GOLDEN.read_bytes()).hexdigest()
    pool = sorted({doc for case in cases for doc in case["expected_documents"]})
    runs = [
        quality_run("seed-q1", 0, cases, dataset_hash, system_version=3, faithfulness_shift=0.0,
                    empty_precision=False, rewrite=set()),
        quality_run("seed-q2", 1, cases, dataset_hash, system_version=4, faithfulness_shift=-0.12,
                    empty_precision=False, rewrite={"G001"}),
        quality_run("seed-q3", 2, cases, dataset_hash, system_version=4, faithfulness_shift=-0.04,
                    empty_precision=True, rewrite=set()),
        retrieval_run("seed-r1", 3, cases, dataset_hash, [1, 3, 5], pool),
        retrieval_run("seed-r2", 4, cases, dataset_hash, [1, 3, 5, 10], pool),
    ]
    OUT.mkdir(exist_ok=True)
    for run in runs:
        target = OUT / f"{run['run_id']}.json"
        target.write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{target.relative_to(HERE)}: {run['kind']}, {run['cases_measured']} casos")


if __name__ == "__main__":
    main()
