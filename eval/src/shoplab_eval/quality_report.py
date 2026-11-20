"""What an answer quality measurement shows and writes: the table for the screen and the two CSV files (research R8).

The files follow the conventions of the retrieval measurement of LAB-28 (contracts/quality-csv.md of the spec):
UTF-8 without BOM, a line feed as line end, four decimals with a point and ``|`` between document ids.

Two differences with LAB-28. An empty cell means «does not apply» or «cannot be computed by definition» and is never
written as ``0`` or ``NaN``. And the files do not carry the text of the answers or of the fragments (FR-019), only the
ids of the documents they come from.

Two runs are not byte-identical (the assistant and the judge are models): what makes them comparable is in the summary.
"""

import csv
import io
from pathlib import Path

from shoplab_eval.constants import (
    DECIMALS,
    LIST_SEPARATOR,
    QUALITY_DETAIL_FILE,
    QUALITY_METRICS,
    QUALITY_SUMMARY_FILE,
)
from shoplab_eval.quality_run import QualityCaseResult, QualityReport
from shoplab_eval.quality_scores import MetricStat


def format_quality_report(report: QualityReport) -> str:
    """A readable table with a row per scope and the parameters that make the run comparable."""
    info = report.info
    headers = ["ámbito", "casos", "fidelidad", "relevancia", "precisión del contexto", "abstenciones"]
    rows = [
        [
            summary.scope,
            str(summary.cases),
            _stat_text(summary.faithfulness),
            _stat_text(summary.answer_relevancy),
            _stat_text(summary.context_precision),
            str(summary.abstained),
        ]
        for summary in report.summaries
    ]
    widths = [max(len(row[column]) for row in [headers, *rows]) for column in range(len(headers))]
    lines = [
        f"Calidad de respuesta · ejecución {info.run_id}",
        f"Juez: {info.judge.model} ({info.judge_underlying_model}) · temperatura {_number(info.judge.temperature)} · "
        f"RAGAS {info.ragas_version}",
        f"Asistente: {info.assistant_model} · embeddings: {info.embedding_model} · Langfuse: {info.langfuse}",
        f"Prompts: {info.prompt_versions}",
        "",
    ]
    lines += ["  ".join(cell.ljust(width) for cell, width in zip(row, widths, strict=True)) for row in [headers, *rows]]
    measured = len(report.results)
    lines += [
        "",
        f"{measured} casos medidos, {report.excluded} excluidos (conversación: sin contexto que juzgar)",
        "«vacías» son casos donde la métrica aplica pero no se pudo calcular por definición (p. ej. una abstención "
        "no tiene afirmaciones que comprobar); no entran en la media.",
    ]
    return "\n".join(lines)


def summary_csv(report: QualityReport) -> str:
    """One row per scope: ``all`` first, then each category with measured cases, with the parameters of the run."""
    info = report.info
    header = ["scope", "cases", "excluded_cases"]
    for metric in QUALITY_METRICS:
        header += [f"{metric}_mean", f"{metric}_n", f"{metric}_empty"]
    header += [
        "abstained",
        "run_id",
        "judge_model",
        "judge_underlying_model",
        "judge_temperature",
        "ragas_version",
        "abstention_prompt_version",
        "assistant_model",
        "embedding_model",
        "langfuse",
        "prompt_versions",
    ]
    rows = []
    for summary in report.summaries:
        row: list[object] = [summary.scope, summary.cases, summary.excluded]
        for metric in QUALITY_METRICS:
            stat = summary.stat(metric)
            row += ["" if stat.mean is None else _number(stat.mean), stat.entered, stat.empty]
        row += [
            summary.abstained,
            info.run_id,
            info.judge.model,
            info.judge_underlying_model,
            _number(info.judge.temperature),
            info.ragas_version,
            info.abstention_prompt_version,
            info.assistant_model,
            info.embedding_model,
            info.langfuse,
            info.prompt_versions,
        ]
        rows.append(row)
    return _csv_text(header, rows)


def detail_csv(report: QualityReport) -> str:
    """One row per measured case, in the order of the dataset: its scores, the context delivered and its trace."""
    header = [
        "id",
        "category",
        "question",
        "kind",
        *QUALITY_METRICS,
        "abstained",
        "context_documents",
        "context_fragments",
        "trace_id",
        "langfuse",
    ]
    return _csv_text(header, [_detail_row(result) for result in report.results])


def write_quality_report(report: QualityReport, out_dir: Path) -> tuple[Path, Path]:
    """Writes the summary and the detail, creating the folder. Both texts are built before anything is written."""
    summary_text = summary_csv(report)
    detail_text = detail_csv(report)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / QUALITY_SUMMARY_FILE
    detail_path = out_dir / QUALITY_DETAIL_FILE
    # newline="" keeps the line ends of the csv module as they are: UTF-8 without BOM, same bytes on every platform.
    summary_path.write_text(summary_text, encoding="utf-8", newline="")
    detail_path.write_text(detail_text, encoding="utf-8", newline="")
    return summary_path, detail_path


def _detail_row(result: QualityCaseResult) -> list[object]:
    scores = result.scores.by_metric()
    # One id per delivered fragment, in order; a document that is not in the corpus shows its title after a «?».
    documents = LIST_SEPARATOR.join(item.document_id or f"?{item.title}" for item in result.turn.context)
    return [
        result.case.id,
        result.case.category,
        result.case.question,
        result.kind.value,
        *("" if scores[metric] is None else _number(scores[metric]) for metric in QUALITY_METRICS),
        "true" if result.scores.abstained else "false",
        documents,
        len(result.turn.context),
        result.trace_id,
        result.langfuse.value,
    ]


def _stat_text(stat: MetricStat) -> str:
    if stat.entered == 0 and stat.empty == 0:
        return "—"
    if stat.entered == 0:
        return f"sin valores (vacías={stat.empty})"
    empty = f", vacías={stat.empty}" if stat.empty else ""
    return f"{_number(stat.mean)} (n={stat.entered}{empty})"


def _number(value: float | None) -> str:
    return f"{value:.{DECIMALS}f}"


def _csv_text(header: list[str], rows: list[list[object]]) -> str:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return buffer.getvalue()
