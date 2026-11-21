"""Agreement between two annotators on a frozen sample: Cohen's kappa, the blind sheet of annotator B and the report.

The first annotator's label (``label_a``) is frozen when the sample is drawn. Annotator B labels from a sheet that has
**no** label column filled in, and the kappa is computed **before** the disagreements are resolved (R7). If B is the
local model, the report says so: that is not an agreement between people.
"""

import csv
import json
import random
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from intent_dataset.constants import ANNOTATOR_KINDS, ANNOTATOR_LOCAL_MODEL, GOLDEN, LABELS, LANGUAGES, DatasetRules
from intent_dataset.dataset import Example
from intent_dataset.errors import InvalidDatasetError, ReviewError
from intent_dataset.paraphrase import LlmClient

SAMPLE_KEYS = ("id", "label_a", "label_b", "final", "reason")
SHEET_COLUMNS = ("id", "text", "label")
_WORDS = re.compile(r"[a-z]+")


@dataclass(frozen=True)
class KappaResult:
    kappa: float | None
    observed: float
    expected: float
    note: str | None = None


def cohen_kappa(first: list[str], second: list[str]) -> KappaResult:
    """κ = (pₒ − pₑ) / (1 − pₑ). When pₑ is 1 (both always say the same single label) κ is not defined."""
    if len(first) != len(second):
        raise ValueError("Las dos listas de etiquetas deben tener la misma longitud.")
    if not first:
        raise ValueError("No se puede calcular el kappa de una lista vacía.")
    total = len(first)
    observed = sum(a == b for a, b in zip(first, second, strict=True)) / total
    counts_first, counts_second = Counter(first), Counter(second)
    expected = sum(counts_first[label] / total * counts_second[label] / total for label in counts_first)
    if expected >= 1.0:
        return KappaResult(None, observed, expected, "no definido: el acuerdo esperado por azar es 1")
    return KappaResult((observed - expected) / (1 - expected), observed, expected)


def select_sample(
    examples: list[Example], rules: DatasetRules, *, seed: int, include: tuple[str, ...] | list[str] = ()
) -> list[Example]:
    """The sample to double-label: the same number per class, both languages, non-golden first, deterministic."""
    per_label, extra = divmod(rules.agreement_sample_size, len(LABELS))
    included = set(include)
    chosen: list[Example] = []
    for position, label in enumerate(LABELS):
        share = per_label + (1 if position < extra else 0)
        pool = sorted((e for e in examples if e.label == label), key=lambda e: e.id)
        if len(pool) < share:
            raise InvalidDatasetError(
                f"La clase {label} tiene {len(pool)} ejemplos y la muestra necesita {share}: no se puede muestrear."
            )
        generator = random.Random(f"{seed}:{label}")
        picked = [e for e in pool if e.id in included][:share]
        for index, language in enumerate(LANGUAGES):
            quota = share // len(LANGUAGES) + (1 if index < share % len(LANGUAGES) else 0)
            need = max(0, quota - sum(1 for e in picked if e.language == language))
            in_language = [e for e in pool if e.language == language]
            picked += _remaining(in_language, picked, generator)[:need]
        picked += _remaining(pool, picked, generator)[: share - len(picked)]
        chosen += picked
    return sorted(chosen, key=lambda e: e.id)


def _remaining(candidates: list[Example], taken: list[Example], generator: random.Random) -> list[Example]:
    """The candidates not taken yet in a seeded order, non-golden ones first (the golden stays the RAG yardstick)."""
    ids = {example.id for example in taken}
    others = [example for example in candidates if example.id not in ids]
    plain = [example for example in others if example.source != GOLDEN]
    golden = [example for example in others if example.source == GOLDEN]
    generator.shuffle(plain)
    generator.shuffle(golden)
    return plain + golden


def write_sample(path: Path, chosen: list[Example], *, force: bool = False) -> None:
    """Freezes the sample with ``label_a``. Replacing it would invalidate an agreement already measured."""
    if path.exists() and not force:
        raise ReviewError(
            f"{path.name} ya existe: cambiar la muestra invalida el acuerdo ya medido. Usa --force solo si vas a "
            "repetir todo el proceso de acuerdo."
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = [
        json.dumps(
            {"id": e.id, "label_a": e.label, "label_b": None, "final": None, "reason": None}, ensure_ascii=False
        )
        for e in sorted(chosen, key=lambda e: e.id)
    ]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def read_sample(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise ReviewError(f"No existe {path}: congélala con el comando sample.")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_annotator_sheet(path: Path, chosen: list[Example]) -> None:
    """The sheet annotator B fills in: id, text and an empty ``label``. It carries nothing of annotator A."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=SHEET_COLUMNS)
        writer.writeheader()
        for example in sorted(chosen, key=lambda e: e.id):
            writer.writerow({"id": example.id, "text": example.text, "label": ""})


def read_annotator_sheet(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ReviewError(f"No existe {path}: genérala con el comando sample.")
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"id", "label"} <= set(reader.fieldnames or []):
            raise ReviewError(f"{path.name}: faltan las columnas id y label.")
        return {row["id"]: (row.get("label") or "").strip() for row in reader}


def sheet_texts(path: Path) -> dict[str, str]:
    """id → text of the sheet, for ``annotate``."""
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return {row["id"]: row["text"] for row in csv.DictReader(stream)}


def compute_agreement(
    sample: list[dict[str, Any]],
    labels_b: dict[str, str],
    *,
    kind: str,
    model: str | None = None,
    today: str | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Fills ``label_b``, resolves the cases that agree and returns the report; raises listing every bad row of B."""
    if kind not in ANNOTATOR_KINDS:
        raise ReviewError(f"kind debe ser uno de {', '.join(ANNOTATOR_KINDS)}, no «{kind}».")
    problems = []
    for row in sample:
        label = labels_b.get(row["id"])
        if label is None:
            problems.append(f"{row['id']}: falta en la hoja del anotador B")
        elif label not in LABELS:
            problems.append(f"{row['id']}: etiqueta inválida «{label}»")
    if problems:
        listing = "\n  ".join(problems)
        raise ReviewError(f"La hoja del anotador B está incompleta o tiene etiquetas inválidas:\n  {listing}")
    updated = []
    for row in sample:
        label_b = labels_b[row["id"]]
        final = row["label_a"] if row["label_a"] == label_b else row.get("final")
        updated.append({**row, "label_b": label_b, "final": final})
    result = cohen_kappa([r["label_a"] for r in updated], [r["label_b"] for r in updated])
    confusion: dict[str, dict[str, int]] = defaultdict(dict)
    for (label_a, label_b), count in sorted(Counter((r["label_a"], r["label_b"]) for r in updated).items()):
        confusion[label_a][label_b] = count
    report = {
        "n": len(updated),
        "kappa": None if result.kappa is None else round(result.kappa, 4),
        "observed_agreement": round(result.observed, 4),
        "expected_agreement": round(result.expected, 4),
        "disagreements": sum(1 for r in updated if r["label_a"] != r["label_b"]),
        "annotator_b": kind,
        "annotator_b_model": model if kind == ANNOTATOR_LOCAL_MODEL else None,
        "confusion": dict(confusion),
        "computed_on": today or date.today().isoformat(),
        "kappa_note": result.note,
    }
    return updated, report


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_resolved_sample(path: Path, sample: list[dict[str, Any]]) -> None:
    """Rewrites the sample keeping the key order of the contract."""
    rows = [json.dumps({key: row.get(key) for key in SAMPLE_KEYS}, ensure_ascii=False) for row in sample]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def _system_prompt(guide: str) -> str:
    return (
        "Eres un anotador de intenciones de mensajes de clientes de una tienda en línea. "
        f"Etiqueta cada mensaje siguiendo esta guía.\n\n{guide}\n\n"
        f"Responde SOLO con una de estas etiquetas, sin nada más: {', '.join(LABELS)}."
    )


def annotate_sheet(rows: dict[str, str], guide: str, client: LlmClient) -> tuple[dict[str, str], list[str]]:
    """The model as annotator B. It gets the guide and the text only (never ``label_a``); an answer that is not exactly
    one label stays empty and is reported instead of guessed."""
    system = _system_prompt(guide)
    labels: dict[str, str] = {}
    invalid: list[str] = []
    for example_id, text in rows.items():
        answer = client.complete(system, text, temperature=0.0)
        found = {word for word in _WORDS.findall(answer.lower()) if word in LABELS}
        if len(found) == 1:
            labels[example_id] = next(iter(found))
        else:
            labels[example_id] = ""
            invalid.append(example_id)
    return labels, invalid
