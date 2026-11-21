"""The figures of the data sheet, generated from the dataset so they cannot drift (research R8).

``DATASHEET.md`` is written by hand except for the block between ``<!-- stats:begin -->`` and ``<!-- stats:end -->``:
``stats --write`` regenerates it and ``validate`` fails if it differs from what the dataset gives.
"""

import re
from collections import Counter
from dataclasses import dataclass
from typing import Any

from intent_dataset.constants import ANNOTATOR_LOCAL_MODEL, LABELS, LANGUAGES, SOURCES, SPLITS, STATS_BEGIN, STATS_END
from intent_dataset.dataset import Example
from intent_dataset.errors import InvalidDatasetError
from intent_dataset.text import normalize_text

REQUIRED_SECTIONS: tuple[str, ...] = (
    "Origen",
    "Composición",
    "Licencias",
    "Generación y revisión de paráfrasis",
    "Acuerdo entre anotadores",
    "Enmascarado",
    "Particiones",
    "Limitaciones",
    "Uso previsto y no previsto",
)
REQUIRED_GUIDE_SECTIONS: tuple[str, ...] = (*LABELS, "Casos límite")
_HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class Stats:
    total: int
    by_label_language: Counter
    by_split_label: Counter
    by_source: Counter
    golden_cases_by_split: dict[str, int]
    report: dict[str, Any] | None


def compute_stats(examples: list[Example], report: dict[str, Any] | None) -> Stats:
    golden_ids: dict[str, set[str]] = {split: set() for split in SPLITS}
    for example in examples:
        if example.golden_id and example.split in golden_ids:
            golden_ids[example.split].add(example.golden_id)
    return Stats(
        total=len(examples),
        by_label_language=Counter((example.label, example.language) for example in examples),
        by_split_label=Counter((example.split, example.label) for example in examples),
        by_source=Counter(example.source for example in examples),
        golden_cases_by_split={split: len(ids) for split, ids in golden_ids.items()},
        report=report,
    )


def _agreement_line(report: dict[str, Any] | None) -> str:
    if report is None:
        return "Acuerdo entre anotadores: pendiente (aún no hay `agreement.json`)."
    kappa = report.get("kappa")
    shown = "no definido" if kappa is None else f"{kappa}"
    line = (
        f"Acuerdo entre anotadores: κ de Cohen = {shown} sobre {report.get('n')} casos "
        f"({report.get('disagreements')} desacuerdos antes de resolver)."
    )
    if report.get("annotator_b") == ANNOTATOR_LOCAL_MODEL:
        line += (
            f" Anotador B: **modelo local** ({report.get('annotator_b_model')}); "
            "**no es un acuerdo entre personas**."
        )
    else:
        line += " Anotador B: persona."
    return line


def render_block(stats: Stats) -> str:
    """The Markdown between the markers, markers included."""
    lines = [STATS_BEGIN, f"**Total: {stats.total} ejemplos**", ""]
    lines += ["| Clase | " + " | ".join(LANGUAGES) + " | total |", "|---|" + "---|" * (len(LANGUAGES) + 1)]
    for label in LABELS:
        counts = [stats.by_label_language[(label, language)] for language in LANGUAGES]
        lines.append(f"| {label} | " + " | ".join(str(count) for count in counts) + f" | {sum(counts)} |")
    lines += ["", "| Partición | " + " | ".join(LABELS) + " | total |", "|---|" + "---|" * (len(LABELS) + 1)]
    for split in SPLITS:
        counts = [stats.by_split_label[(split, label)] for label in LABELS]
        lines.append(f"| {split} | " + " | ".join(str(count) for count in counts) + f" | {sum(counts)} |")
    lines += ["", "| Origen | ejemplos |", "|---|---|"]
    lines += [f"| {source} | {stats.by_source[source]} |" for source in SOURCES]
    golden = ", ".join(f"{split} {stats.golden_cases_by_split[split]}" for split in SPLITS)
    lines += ["", f"Casos del golden por partición: {golden}.", "", _agreement_line(stats.report), STATS_END]
    return "\n".join(lines)


def _span(text: str) -> tuple[int, int] | None:
    start, end = text.find(STATS_BEGIN), text.find(STATS_END)
    if start < 0 or end < start:
        return None
    return start, end + len(STATS_END)


def check_block(text: str, expected: str) -> list[str]:
    """Problems of the generated block in the sheet: absent markers, or figures that differ from the dataset."""
    span = _span(text)
    if span is None:
        return [f"faltan las marcas {STATS_BEGIN} y {STATS_END} en la ficha"]
    if text[span[0] : span[1]].strip() != expected.strip():
        return ["las cifras de la ficha no coinciden con el dataset: ejecuta `stats --write`"]
    return []


def write_block(text: str, block: str) -> str:
    """The sheet with only the generated block replaced; everything else is left untouched."""
    span = _span(text)
    if span is None:
        raise InvalidDatasetError(f"La ficha no tiene las marcas {STATS_BEGIN} y {STATS_END}: añádelas donde va.")
    return text[: span[0]] + block.strip() + text[span[1] :]


def missing_sections(text: str, required: tuple[str, ...]) -> list[str]:
    """The required sections that have no heading (any level, case and accents ignored, a prefix is enough)."""
    headings = [normalize_text(match) for match in _HEADING.findall(text)]
    return [name for name in required if not any(heading.startswith(normalize_text(name)) for heading in headings)]
