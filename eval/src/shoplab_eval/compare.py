"""Compares two quality runs side by side (specs/LAB-85-versioned-prompts, FR-021 and FR-022).

It reads the ``all`` row of two ``quality-summary.csv`` files and shows, for each metric, the mean and the number of
cases of each run and their difference, which prompts tell one run from the other and, if the two runs were not
measured under the same conditions, why: a difference between runs that used another judge, another assistant model or
another set of cases may not be the prompt's, and it is never shown without saying so. Nothing here asks a model,
Langfuse or the backend: it only reads two files.
"""

import csv
import io
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from shoplab_eval.constants import ALL_SCOPE, DECIMALS, PRODUCTION_LABEL, QUALITY_METRICS
from shoplab_eval.prompt_selection import parse_prompt_versions

# The parameters that make two results comparable (FR-022); ``cases`` is read from the ``all`` row too.
COMPARABILITY_PARAMETERS = (
    "judge_model",
    "judge_underlying_model",
    "judge_temperature",
    "assistant_model",
    "embedding_model",
    "cases",
)
NOT_COMPARABLE = "No comparables: la diferencia puede no deberse al prompt"
NO_DIFFERENCE = "ninguno"


class SummaryError(ValueError):
    """A summary cannot be read or has no ``all`` row: a usage error of the command (exit code 2)."""


@dataclass(frozen=True)
class MetricComparison:
    """One metric of both runs; a mean is ``None`` when the run has no value for it, and then there is no delta."""

    metric: str
    a_mean: float | None
    a_n: int
    b_mean: float | None
    b_n: int

    @property
    def delta(self) -> float | None:
        if self.a_mean is None or self.b_mean is None:
            return None
        return self.b_mean - self.a_mean


@dataclass(frozen=True)
class Comparison:
    run_a: str
    run_b: str
    rows: tuple[MetricComparison, ...]
    differing_prompts: tuple[str, ...]
    warnings: tuple[str, ...]

    @property
    def comparable(self) -> bool:
        return not self.warnings


def read_summary(path: Path) -> dict[str, str]:
    """The ``all`` row of a quality summary. Raises ``SummaryError`` when the file is missing, unreadable or has none."""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise SummaryError(f"No se pudo leer el resumen {path}: {error.__class__.__name__}") from None
    try:
        rows = list(csv.DictReader(io.StringIO(text, newline="")))
    except csv.Error:
        raise SummaryError(f"El resumen {path} no es un CSV legible") from None
    for row in rows:
        if row.get("scope") == ALL_SCOPE:
            missing = [f"{metric}_mean" for metric in QUALITY_METRICS if f"{metric}_mean" not in row]
            if missing:
                raise SummaryError(f"El resumen {path} no tiene las columnas {', '.join(missing)}: no es de calidad")
            return {key: value or "" for key, value in row.items() if key is not None}
    raise SummaryError(f"El resumen {path} no tiene la fila del ámbito «{ALL_SCOPE}»")


def compare_summaries(a: Mapping[str, str], b: Mapping[str, str]) -> Comparison:
    """The comparison of two ``all`` rows, with the prompts that differ and the warnings of comparability."""
    rows = tuple(
        MetricComparison(
            metric=metric,
            a_mean=_number(a.get(f"{metric}_mean", "")),
            a_n=_count(a.get(f"{metric}_n", "")),
            b_mean=_number(b.get(f"{metric}_mean", "")),
            b_n=_count(b.get(f"{metric}_n", "")),
        )
        for metric in QUALITY_METRICS
    )
    warnings = tuple(
        f"{parameter}: A={a.get(parameter, '') or '—'} · B={b.get(parameter, '') or '—'}"
        for parameter in COMPARABILITY_PARAMETERS
        if a.get(parameter, "") != b.get(parameter, "")
    )
    return Comparison(
        run_a=a.get("run_id", "") or "A",
        run_b=b.get("run_id", "") or "B",
        rows=rows,
        differing_prompts=_differing_prompts(a.get("prompt_versions", ""), b.get("prompt_versions", "")),
        warnings=warnings,
    )


def format_comparison(comparison: Comparison) -> str:
    """The table for the screen, with the prompts that differ and the warnings under it."""
    headers = ["métrica", "A (media)", "casos A", "B (media)", "casos B", "diferencia"]
    table = [
        [
            row.metric,
            _show(row.a_mean),
            str(row.a_n),
            _show(row.b_mean),
            str(row.b_n),
            _show(row.delta, signed=True),
        ]
        for row in comparison.rows
    ]
    widths = [max(len(line[column]) for line in [headers, *table]) for column in range(len(headers))]
    lines = [f"Comparación de calidad · A: {comparison.run_a} · B: {comparison.run_b}"]
    if comparison.warnings:
        lines += ["", NOT_COMPARABLE]
    lines.append("")
    for line in [headers, *table]:
        lines.append("  ".join(cell.ljust(width) for cell, width in zip(line, widths, strict=True)))
    differing = ", ".join(comparison.differing_prompts) or NO_DIFFERENCE
    lines += ["", f"Prompts que difieren: {differing}"]
    if comparison.warnings:
        lines += ["Advertencias:", *[f"  - {warning}" for warning in comparison.warnings]]
    return "\n".join(lines)


def comparison_csv(comparison: Comparison) -> str:
    """The table as CSV: UTF-8, comma, four decimals and an empty cell where there is nothing to show."""
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["metric", "a_mean", "a_n", "b_mean", "b_n", "delta"])
    for row in comparison.rows:
        writer.writerow([row.metric, _cell(row.a_mean), row.a_n, _cell(row.b_mean), row.b_n, _cell(row.delta)])
    return buffer.getvalue()


def _differing_prompts(a: str, b: str) -> tuple[str, ...]:
    """The prompts whose value is not the same in both runs; one that a run does not fix is on ``production``."""
    versions_a, versions_b = parse_prompt_versions(a), parse_prompt_versions(b)
    names = sorted(set(versions_a) | set(versions_b))
    return tuple(
        name for name in names if versions_a.get(name, PRODUCTION_LABEL) != versions_b.get(name, PRODUCTION_LABEL)
    )


def _number(cell: str) -> float | None:
    try:
        return float(cell) if cell.strip() else None
    except ValueError:
        return None


def _count(cell: str) -> int:
    try:
        return int(cell)
    except ValueError:
        return 0


def _cell(value: float | None) -> str:
    return "" if value is None else f"{_rounded(value):.{DECIMALS}f}"


def _show(value: float | None, signed: bool = False) -> str:
    if value is None:
        return "—"
    return f"{_rounded(value):+.{DECIMALS}f}" if signed else f"{_rounded(value):.{DECIMALS}f}"


def _rounded(value: float) -> float:
    """Rounded to the decimals of the report; ``+ 0.0`` turns a negative zero into zero (it never prints -0.0000)."""
    return round(value, DECIMALS) + 0.0
