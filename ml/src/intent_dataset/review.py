"""The human review step: candidates go out as a CSV, a person decides each row, and only what was accepted comes back.

Nothing here decides a label. ``build_examples`` only checks that a person did, and refuses to build anything if any
accepted row is incomplete (it lists all of them), so a half-reviewed file cannot reach the dataset.
"""

import csv
from collections import Counter
from dataclasses import dataclass, fields
from pathlib import Path

from intent_dataset.constants import (
    ACCEPT,
    CANDIDATE_COLUMNS,
    GOLDEN,
    LABELS,
    LANGUAGES,
    OLIST_REVIEW,
    PARAPHRASE,
    PII_CHECKED_YES,
    REJECT,
    SOURCES,
    DatasetRules,
)
from intent_dataset.dataset import Example, next_id
from intent_dataset.errors import ReviewError
from intent_dataset.masking import mask_text
from intent_dataset.text import normalize_text

_UNTRIMMED = ("text", "text_edit")


@dataclass(frozen=True)
class Candidate:
    """A row of the review CSV. All fields are text; empty means «not set» (``decision`` empty means pending)."""

    candidate_id: str
    source: str
    group_id: str
    parent_id: str
    golden_id: str
    language: str
    text: str
    proposed_label: str
    hint: str
    decision: str
    label: str
    text_edit: str
    pii_checked: str
    note: str


@dataclass(frozen=True)
class BuildSummary:
    accepted: int
    rejected: int
    pending: int
    duplicated: int
    by_source: dict[str, int]
    by_label: dict[str, int]


@dataclass(frozen=True)
class BuildResult:
    examples: list[Example]
    summary: BuildSummary


def write_candidates_csv(candidates: list[Candidate], path: Path, *, force: bool = False) -> None:
    """Writes the review CSV. An existing file may hold someone's decisions, so it is only replaced with ``force``."""
    if path.exists() and not force:
        raise ReviewError(
            f"{path.name} ya existe y puede contener decisiones de revisión: no se sobrescribe. "
            "Usa --force si de verdad quieres reemplazarlo."
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=CANDIDATE_COLUMNS)
        writer.writeheader()
        for candidate in candidates:
            writer.writerow({name: getattr(candidate, name) for name in CANDIDATE_COLUMNS})


def read_review_csv(path: Path) -> list[Candidate]:
    """Reads a review CSV (UTF-8, with or without the BOM Excel adds)."""
    if not path.is_file():
        raise ReviewError(f"No existe {path}: genera los candidatos con el comando candidates o paraphrase.")
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        missing = [name for name in CANDIDATE_COLUMNS if name not in (reader.fieldnames or [])]
        if missing:
            raise ReviewError(f"{path.name}: faltan las columnas {', '.join(missing)}.")
        rows = []
        for raw in reader:
            values = {
                name: (raw.get(name) or "") if name in _UNTRIMMED else (raw.get(name) or "").strip()
                for name in (field.name for field in fields(Candidate))
            }
            rows.append(Candidate(**values))
    return rows


def _problems(row: Candidate, parent: Example | None, text: str, rules: DatasetRules) -> list[str]:
    problems = []
    if row.label not in LABELS:
        problems.append("falta la etiqueta" if not row.label else f"etiqueta inválida «{row.label}»")
    if row.source not in SOURCES:
        problems.append(f"origen inválido «{row.source}»")
    if row.language not in LANGUAGES:
        problems.append(f"idioma inválido «{row.language}»")
    if row.source == OLIST_REVIEW and row.pii_checked != PII_CHECKED_YES:
        problems.append("un comentario de Olist necesita pii_checked = si (revisión manual de nombres)")
    if not rules.min_text_chars <= len(text) <= rules.max_text_chars:
        problems.append(f"longitud del texto fuera de {rules.min_text_chars}–{rules.max_text_chars} caracteres")
    if row.source == PARAPHRASE:
        if parent is None:
            problems.append(f"el padre {row.parent_id or '(vacío)'} no existe en el dataset")
        elif row.label in LABELS and row.label != parent.label:
            problems.append(f"la etiqueta debe coincidir con la de su padre {parent.id} ({parent.label})")
    elif row.parent_id:
        problems.append("solo una paráfrasis tiene parent_id")
    return problems


def _to_example(row: Candidate, parent: Example | None, example_id: str, text: str) -> Example:
    if parent is not None:
        group_id, golden_id, parent_id = parent.group_id, parent.golden_id, parent.id
    else:
        group_id, parent_id = row.group_id, None
        golden_id = (row.golden_id or row.group_id) if row.source == GOLDEN else (row.golden_id or None)
    return Example(
        id=example_id,
        text=text,
        label=row.label,
        language=row.language,
        source=row.source,
        group_id=group_id,
        parent_id=parent_id,
        golden_id=golden_id,
        split=None,
        reviewed=True,
    )


def build_examples(rows: list[Candidate], existing: list[Example], rules: DatasetRules) -> BuildResult:
    """New examples from the accepted rows. All-or-nothing: any problem in any accepted row raises ``ReviewError``."""
    by_id = {example.id: example for example in existing}
    seen = {normalize_text(example.text) for example in existing}
    created: list[Example] = []
    problems: list[str] = []
    rejected = pending = duplicated = 0
    for row in rows:
        if row.decision == REJECT:
            rejected += 1
            continue
        if row.decision != ACCEPT:
            if row.decision:
                problems.append(f"fila {row.candidate_id}: decisión inválida «{row.decision}» (accept, reject o vacío)")
            else:
                pending += 1
            continue
        text = mask_text((row.text_edit or row.text).strip())
        parent = by_id.get(row.parent_id) if row.source == PARAPHRASE else None
        row_problems = _problems(row, parent, text, rules)
        if row_problems:
            problems.extend(f"fila {row.candidate_id}: {problem}" for problem in row_problems)
            continue
        key = normalize_text(text)
        if key in seen:
            duplicated += 1
            continue
        seen.add(key)
        example = _to_example(row, parent, next_id([*existing, *created]), text)
        created.append(example)
    if problems:
        listing = "\n  ".join(problems)
        raise ReviewError(f"La revisión tiene filas incompletas o inválidas; no se ha construido nada:\n  {listing}")
    summary = BuildSummary(
        accepted=len(created),
        rejected=rejected,
        pending=pending,
        duplicated=duplicated,
        by_source=dict(Counter(example.source for example in created)),
        by_label=dict(Counter(example.label for example in created)),
    )
    return BuildResult(created, summary)
