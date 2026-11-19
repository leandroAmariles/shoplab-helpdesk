"""What a retrieval measurement shows and writes: the table for the screen and the two CSV files (research R7).

The files are the contract that LAB-30 and LAB-31 read (``specs/LAB-28-retrieval-metrics/contracts/retrieval-csv.md``).
They have to be identical for identical inputs: no timestamps, no paths, fixed decimals and a fixed order.
"""

import csv
import io
from pathlib import Path

from shoplab_eval.constants import DECIMALS, DETAIL_FILE, LIST_SEPARATOR, SUMMARY_FILE
from shoplab_eval.retrieval_run import RetrievalReport


def format_report(report: RetrievalReport) -> str:
    """A readable table with a row per scope (the whole dataset and each category) and the parameters of the run."""
    headers = ["scope", "casos"]
    headers += [f"hit_rate@{k}" for k in report.ks]
    headers += [f"recall@{k}" for k in report.ks]
    headers.append("mrr")
    rows = []
    for summary in report.summaries:
        cells = [summary.scope, str(summary.cases)]
        cells += [_number(summary.hit_rate[k]) for k in report.ks]
        cells += [_number(summary.recall[k]) for k in report.ks]
        cells.append(_number(summary.mrr))
        rows.append(cells)
    widths = [max(len(row[column]) for row in [headers, *rows]) for column in range(len(headers))]
    lines = ["  ".join(cell.ljust(width) for cell, width in zip(row, widths, strict=True)) for row in [headers, *rows]]
    measured = report.summaries[0].cases if report.summaries else 0
    lines += [
        "",
        f"Modelo de embeddings: {report.model}",
        f"Profundidad de búsqueda: {report.depth} fragmentos por pregunta",
        f"{measured} casos medidos, {report.excluded} excluidos (sin documentos esperados)",
    ]
    return "\n".join(lines)


def _number(value: float) -> str:
    return f"{value:.{DECIMALS}f}"


def summary_csv(report: RetrievalReport) -> str:
    """One row per scope: ``all`` first, then each category with measured cases; the means of the detail."""
    header = ["scope", "cases", "excluded_cases", "embedding_model", "search_depth"]
    header += [f"hit_rate@{k}" for k in report.ks] + [f"recall@{k}" for k in report.ks] + ["mrr"]
    rows = []
    for summary in report.summaries:
        row = [summary.scope, summary.cases, summary.excluded, report.model, report.depth]
        row += [_number(summary.hit_rate[k]) for k in report.ks]
        row += [_number(summary.recall[k]) for k in report.ks]
        row.append(_number(summary.mrr))
        rows.append(row)
    return _csv_text(header, rows)


def detail_csv(report: RetrievalReport) -> str:
    """One row per measured case in the order of the dataset: what was expected, what came back and each metric."""
    header = ["id", "category", "question", "expected_documents", "retrieved_documents", "first_hit_rank"]
    header += [f"hit@{k}" for k in report.ks] + [f"recall@{k}" for k in report.ks] + ["reciprocal_rank"]
    rows = []
    for result in report.results:
        metrics = result.metrics
        row = [
            result.case.id,
            result.case.category,
            result.case.question,
            LIST_SEPARATOR.join(result.case.expected_documents),
            LIST_SEPARATOR.join(result.retrieved),
            "" if metrics.first_hit_rank is None else metrics.first_hit_rank,
        ]
        row += [metrics.hit[k] for k in report.ks]
        row += [_number(metrics.recall[k]) for k in report.ks]
        row.append(_number(metrics.reciprocal_rank))
        rows.append(row)
    return _csv_text(header, rows)


def write_report(report: RetrievalReport, out_dir: Path) -> tuple[Path, Path]:
    """Writes the summary and the detail, creating the folder. Both texts are built before anything is written."""
    summary_text = summary_csv(report)
    detail_text = detail_csv(report)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / SUMMARY_FILE
    detail_path = out_dir / DETAIL_FILE
    # newline="" keeps the line ends of the csv module as they are: UTF-8 without BOM, same bytes on every platform.
    summary_path.write_text(summary_text, encoding="utf-8", newline="")
    detail_path.write_text(detail_text, encoding="utf-8", newline="")
    return summary_path, detail_path


def _csv_text(header: list[str], rows: list[list[object]]) -> str:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(header)
    writer.writerows(rows)
    return buffer.getvalue()
