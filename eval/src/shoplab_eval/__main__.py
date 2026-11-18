"""``python -m shoplab_eval``: ``validate`` checks the golden dataset and ``retrieval`` measures the search with it."""

import argparse
import os
import sys
from pathlib import Path

from shoplab_eval.constants import (
    BASE_URL_ENV,
    DEFAULT_BASE_URL,
    DEFAULT_DEPTH,
    DEFAULT_GOLDEN_PATH,
    DEFAULT_KB_DIR,
    DEFAULT_KS,
    DEFAULT_RESULTS_DIR,
    DEFAULT_TIMEOUT_SECONDS,
    MAX_DEPTH,
)
from shoplab_eval.errors import InvalidGoldenError, MeasurementError
from shoplab_eval.golden import load_golden, validate_golden
from shoplab_eval.retrieval_report import format_report, write_report
from shoplab_eval.retrieval_run import measure_retrieval
from shoplab_eval.search_client import KnowledgeSearchClient
from shoplab_eval.summary import format_summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="shoplab_eval", description="Dataset golden de ShopLab Helpdesk")
    commands = parser.add_subparsers(dest="command")
    validate = commands.add_parser("validate", help="valida eval/golden.jsonl contra el corpus y muestra el resumen")
    validate.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    validate.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    retrieval = commands.add_parser(
        "retrieval", help="mide hit rate@k, recall@k y MRR de la búsqueda real contra el dataset golden"
    )
    retrieval.add_argument("--k", default=",".join(str(k) for k in DEFAULT_KS), help="valores de k separados por comas")
    retrieval.add_argument("--depth", type=int, default=DEFAULT_DEPTH, help="fragmentos que se piden por pregunta")
    retrieval.add_argument(
        "--base-url",
        default=os.environ.get(BASE_URL_ENV, DEFAULT_BASE_URL),
        help=f"backend que sirve la búsqueda (variable {BASE_URL_ENV})",
    )
    retrieval.add_argument("--out", type=Path, default=DEFAULT_RESULTS_DIR, help="carpeta de salida de los CSV")
    retrieval.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN_PATH, help="ruta del archivo JSONL")
    retrieval.add_argument("--kb", type=Path, default=DEFAULT_KB_DIR, help="directorio del corpus (data/kb)")
    retrieval.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS, help="segundos por petición")
    args = parser.parse_args(argv)
    _use_utf8_output()
    if args.command == "retrieval":
        return _run_retrieval(args)
    if args.command != "validate":
        parser.print_help()
        return 2
    try:
        summary = validate_golden(args.golden, args.kb)
    except InvalidGoldenError as error:
        print(error)
        return 1
    except (FileNotFoundError, ValueError) as error:
        print(error)
        return 1
    print(format_summary(summary))
    return 0


def _run_retrieval(args: argparse.Namespace) -> int:
    """Exit codes: 0 measured and written, 1 the measurement is not valid (nothing written), 2 wrong usage.

    The CSV files are written only after the whole measurement succeeded, so a failure never leaves a result that
    looks valid (and never touches the files of an earlier run).
    """
    try:
        ks = _parse_ks(args.k, args.depth)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    try:
        cases = load_golden(args.golden, args.kb)
        client = KnowledgeSearchClient(args.base_url, args.timeout)
        report = measure_retrieval(cases, client, ks, args.depth)
    except (InvalidGoldenError, MeasurementError, FileNotFoundError) as error:
        print(error)
        return 1
    print(format_report(report))
    summary_path, detail_path = write_report(report, args.out)
    print(f"\nResumen: {summary_path}\nDetalle: {detail_path}")
    return 0


def _parse_ks(raw: str, depth: int) -> tuple[int, ...]:
    """The k to report: positive integers, no repeats, none above the depth. Raises ``ValueError`` with the fix."""
    if not 1 <= depth <= MAX_DEPTH:
        raise ValueError(f"--depth {depth} no es válido: debe ser un entero entre 1 y {MAX_DEPTH}")
    problem = (
        f"--k {raw!r} no es válido: usa enteros positivos separados por comas, sin repetir y no mayores que "
        f"--depth ({depth}); por ejemplo 1,3,5"
    )
    try:
        ks = tuple(int(value) for value in raw.split(","))
    except ValueError:
        raise ValueError(problem) from None
    if any(k < 1 or k > depth for k in ks) or len(set(ks)) != len(ks):
        raise ValueError(problem)
    return ks


def _use_utf8_output() -> None:
    """The report is in Spanish; the default console encoding of Windows would garble the accents."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
