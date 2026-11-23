"""Command-line evaluation script for testing serialized intent classifier on the test split (LAB-118)."""

import argparse
import sys
import time
from collections import Counter
from collections.abc import Sequence
from pathlib import Path

from intent.artifacts import (
    load_pipeline_artifact,
    render_eval_markdown,
    write_eval_report,
)
from intent.constants import (
    DEFAULT_ARTIFACT_PATH,
    DEFAULT_DATASET_PATH,
    DEFAULT_REPORT_OUT_PATH,
    MIN_TEST_F1_MACRO,
    SPLIT_TEST,
    SPLIT_TRAIN,
)
from intent.data import compute_file_sha256, load_intents_jsonl, validate_dataset_integrity
from intent.errors import ClassifierConfigurationError, DataIntegrityError
from intent.metrics import evaluate_predictions


def build_evaluate_arg_parser() -> argparse.ArgumentParser:
    """Build the command-line argument parser for intent.evaluate."""
    parser = argparse.ArgumentParser(
        prog="python -m intent.evaluate",
        description="Evalúa el clasificador de intención serializado sobre la partición test (LAB-118).",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=DEFAULT_DATASET_PATH,
        help="Ruta al archivo JSONL del dataset (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--artifact",
        type=Path,
        default=DEFAULT_ARTIFACT_PATH,
        help="Ruta al artefacto serializado .joblib (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--report-out",
        type=Path,
        default=DEFAULT_REPORT_OUT_PATH,
        help="Ruta de salida para el informe JSON de evaluación (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--min-f1-macro",
        type=float,
        default=MIN_TEST_F1_MACRO,
        help="Umbral mínimo aceptable de F1 macro en test (por defecto: %(default)s).",
    )
    return parser


def run_evaluation(
    *,
    dataset_path: Path,
    artifact_path: Path,
    report_out_path: Path,
    min_f1_macro: float = MIN_TEST_F1_MACRO,
) -> dict:
    """Load test examples and artifact, compute full metrics, write report, and return report dict."""
    if not dataset_path.exists():
        raise FileNotFoundError(f"No se encontró el dataset en {dataset_path}.")
    if not artifact_path.exists():
        raise FileNotFoundError(f"No se encontró el artefacto en {artifact_path}. Ejecuta train primero.")

    examples = load_intents_jsonl(dataset_path)
    validate_dataset_integrity(examples)

    test_examples = [ex for ex in examples if ex.split == SPLIT_TEST]
    if not test_examples:
        raise DataIntegrityError("El dataset no contiene ejemplos con split == 'test'.")

    # Majority label determined from train partition
    train_labels = [ex.label for ex in examples if ex.split == SPLIT_TRAIN]
    majority_label = Counter(train_labels).most_common(1)[0][0] if train_labels else "pedido"

    pipeline = load_pipeline_artifact(artifact_path)
    texts = [ex.text for ex in test_examples]

    t0 = time.perf_counter()
    y_proba = pipeline.predict_proba(texts)
    y_pred = [str(x) for x in pipeline.predict(texts)]
    latency_ms = (time.perf_counter() - t0) * 1000.0

    report = evaluate_predictions(
        test_examples,
        y_pred=y_pred,
        y_proba=y_proba,
        class_labels=list(pipeline.classes_),
        majority_label=majority_label,
        target_f1_macro=min_f1_macro,
        artifact_path=str(artifact_path).replace("\\", "/"),
        artifact_sha256=compute_file_sha256(artifact_path),
        dataset_sha256=compute_file_sha256(dataset_path),
        latency_ms_total=latency_ms,
    )

    write_eval_report(report, report_out_path)
    return report


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point for intent.evaluate."""
    parser = build_evaluate_arg_parser()
    try:
        args = parser.parse_args(argv)
        report = run_evaluation(
            dataset_path=args.dataset,
            artifact_path=args.artifact,
            report_out_path=args.report_out,
            min_f1_macro=args.min_f1_macro,
        )
        print(render_eval_markdown(report))
        return 0 if report["passed_target"] else 1
    except (FileNotFoundError, ClassifierConfigurationError, ValueError) as exc:
        print(f"Error de configuración o ejecución en evaluate: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"Error inesperado en evaluate: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
