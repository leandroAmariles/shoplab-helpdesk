"""Artifact serialization, explainability extraction and JSON/Markdown reporting (LAB-118)."""

import json
import platform
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import sklearn
from sklearn.calibration import CalibratedClassifierCV
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline

from intent.constants import CANONICAL_LABELS
from intent.data import compute_file_sha256
from intent.errors import ClassifierConfigurationError


def save_pipeline_artifact(pipeline: Pipeline, artifact_path: Path) -> str:
    """Serialize a fitted scikit-learn Pipeline atomically with joblib and return its SHA-256."""
    if not isinstance(pipeline, Pipeline):
        raise ClassifierConfigurationError("El artefacto a guardar debe ser un sklearn.pipeline.Pipeline.")
    classes = tuple(str(c) for c in getattr(pipeline, "classes_", ()))
    if set(classes) != set(CANONICAL_LABELS):
        raise ClassifierConfigurationError(
            f"El pipeline entrenado no contiene las 5 etiquetas canónicas: {classes}."
        )

    artifact_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = artifact_path.with_suffix(".tmp.joblib")
    joblib.dump(pipeline, tmp_path, compress=3)
    tmp_path.replace(artifact_path)
    return compute_file_sha256(artifact_path)


def load_pipeline_artifact(artifact_path: Path) -> Pipeline:
    """Load and validate a serialized scikit-learn Pipeline artifact."""
    if not artifact_path.exists():
        raise ClassifierConfigurationError(
            f"No se encontró el artefacto del modelo en {artifact_path}. Ejecuta 'train.py' primero."
        )
    loaded = joblib.load(artifact_path)
    if not isinstance(loaded, Pipeline) or not hasattr(loaded, "predict_proba"):
        raise ClassifierConfigurationError(
            f"El archivo {artifact_path} no contiene un Pipeline de scikit-learn con predict_proba."
        )
    classes = tuple(str(c) for c in getattr(loaded, "classes_", ()))
    if set(classes) != set(CANONICAL_LABELS):
        raise ClassifierConfigurationError(
            f"El artefacto cargado tiene clases incompatibles: {classes}."
        )
    return loaded


def extract_top_features_by_class(
    pipeline: Pipeline,
    *,
    top_k: int = 15,
) -> dict[str, list[dict[str, float | str]]]:
    """Extract top positive lexical features per class from a fitted linear TF-IDF pipeline."""
    if "features" not in pipeline.named_steps or "clf" not in pipeline.named_steps:
        return {}

    features_step = pipeline.named_steps["features"]
    if not hasattr(features_step, "get_feature_names_out"):
        return {}

    feature_names = np.asarray(features_step.get_feature_names_out(), dtype=object)
    clf_step = pipeline.named_steps["clf"]

    coef_matrix: np.ndarray | None = None
    classes: list[str] = []

    if isinstance(clf_step, LogisticRegression) and hasattr(clf_step, "coef_"):
        coef_matrix = np.asarray(clf_step.coef_, dtype=np.float64)
        classes = [str(c) for c in clf_step.classes_]
    elif isinstance(clf_step, CalibratedClassifierCV) and hasattr(clf_step, "calibrated_classifiers_"):
        classes = [str(c) for c in clf_step.classes_]
        fold_coefs = [
            np.asarray(cal.estimator.coef_, dtype=np.float64)
            for cal in clf_step.calibrated_classifiers_
            if hasattr(cal, "estimator") and hasattr(cal.estimator, "coef_")
        ]
        if fold_coefs:
            coef_matrix = np.mean(np.stack(fold_coefs, axis=0), axis=0)

    if coef_matrix is None or coef_matrix.shape[1] != len(feature_names):
        return {}

    class_to_row = {cls_name: idx for idx, cls_name in enumerate(classes)}
    result: dict[str, list[dict[str, float | str]]] = {}
    for label in CANONICAL_LABELS:
        if label not in class_to_row:
            continue
        row = coef_matrix[class_to_row[label]]
        top_indices = np.argsort(row)[::-1][:top_k]
        result[label] = [
            {"feature": str(feature_names[i]), "weight": round(float(row[i]), 6)}
            for i in top_indices
        ]
    return result


def current_environment_versions() -> dict[str, str]:
    """Return versions of Python and core scientific dependencies."""
    return {
        "python": platform.python_version(),
        "scikit_learn": sklearn.__version__,
        "numpy": np.__version__,
        "joblib": joblib.__version__,
    }


def write_json_atomic(payload: dict[str, Any], path: Path) -> None:
    """Write a JSON document atomically with UTF-8 encoding and stable formatting."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(".tmp.json")
    tmp_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp_path.replace(path)


def write_train_metadata(metadata: dict[str, Any], path: Path) -> None:
    """Write train_metadata.json atomically."""
    write_json_atomic(metadata, path)


def write_eval_report(report: dict[str, Any], path: Path) -> None:
    """Write eval_report.json atomically."""
    write_json_atomic(report, path)


def render_train_markdown(metadata: dict[str, Any]) -> str:
    """Render a human-readable Markdown summary of the training and variant comparison."""
    train_n = metadata["split_counts"]["train"]
    val_n = metadata["split_counts"]["val"]
    header_cols = (
        "| Variante | CV F1 macro (train) | Val F1 macro | Val Accuracy "
        "| Val Brier | Val Log-Loss | Val ECE | Latencia CPU (ms/msg) |"
    )
    lines = [
        "# Comparativa de variantes — Clasificador de intención (LAB-118)",
        "",
        f"- **Semilla**: `{metadata['seed']}`",
        f"- **Dataset SHA-256**: `{metadata['dataset_sha256'][:16]}…`",
        f"- **Particiones usadas**: `train={train_n}`, `val={val_n}` (`test` no leído)",
        f"- **Variante seleccionada**: **`{metadata['selected_variant']}`**",
        f"- **Motivo**: {metadata['selection_reason']}",
        "",
        header_cols,
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for v in metadata["variants"]:
        lines.append(
            f"| `{v['variant']}` | {v['cv_f1_macro_mean']:.4f} ± {v['cv_f1_macro_std']:.4f} | "
            f"**{v['val_f1_macro']:.4f}** | {v['val_accuracy']:.4f} | {v['val_brier_score']:.4f} | "
            f"{v['val_log_loss']:.4f} | {v['val_ece']:.4f} | {v['val_latency_ms_per_msg']:.4f} |"
        )
    return "\n".join(lines)


def render_eval_markdown(report: dict[str, Any]) -> str:
    """Render a human-readable Markdown summary of the final evaluation on the test split."""
    status = "CUMPLE" if report["passed_target"] else "NO CUMPLE"
    cal = report["calibration"]
    es_info = report["by_language"]["es"]
    pt_info = report["by_language"]["pt"]
    non_golden = report["non_golden_subset"]
    lines = [
        "# Evaluación en partición test — Clasificador de intención (LAB-118)",
        "",
        f"- **Ejemplos en `test`**: `{report['test_count']}`",
        (
            f"- **F1 macro en `test`**: **`{report['test_f1_macro']:.4f}`** "
            f"(objetivo ≥ `{report['target_f1_macro']:.2f}` → **{status}**)"
        ),
        f"- **Exactitud en `test`**: `{report['test_accuracy']:.4f}`",
        (
            f"- **Baseline clase mayoritaria (`{report['baseline_majority_label']}`)**: "
            f"F1 macro = `{report['baseline_f1_macro']:.4f}`, "
            f"exactitud = `{report['baseline_accuracy']:.4f}`"
        ),
        (
            f"- **Calibración**: Brier = `{cal['brier_score']:.4f}`, "
            f"Log-Loss = `{cal['log_loss']:.4f}`, ECE = `{cal['ece']:.4f}`"
        ),
        (
            f"- **Desglose por idioma**: `es` F1 macro = `{es_info['f1_macro']:.4f}` "
            f"(n={es_info['count']}), `pt` F1 macro = `{pt_info['f1_macro']:.4f}` "
            f"(n={pt_info['count']})"
        ),
        (
            "- **Subconjunto sin golden (`golden_id == null`)**: "
            f"F1 macro = `{non_golden['f1_macro']:.4f}` (n={non_golden['count']})"
        ),
        (
            f"- **Latencia en CPU**: `{report['latency_ms_total']:.2f} ms` total "
            f"(`{report['latency_ms_per_example']:.4f} ms/mensaje`)"
        ),
        "",
        "## Métricas por clase",
        "",
        "| Clase | Precisión | Recall | F1 | Soporte |",
        "| --- | --- | --- | --- | --- |",
    ]
    for label, metrics in report["per_class"].items():
        lines.append(
            f"| `{label}` | {metrics['precision']:.4f} | {metrics['recall']:.4f} | "
            f"**{metrics['f1']:.4f}** | {metrics['support']} |"
        )

    labels = report["confusion_matrix"]["labels"]
    matrix = report["confusion_matrix"]["matrix"]
    lines.extend(
        [
            "",
            "## Matriz de confusión (filas = real, columnas = predicha)",
            "",
            "| Real \\ Pred | " + " | ".join(f"`{lbl}`" for lbl in labels) + " |",
            "| --- | " + " | ".join("---" for _ in labels) + " |",
        ]
    )
    for row_label, row_vals in zip(labels, matrix, strict=True):
        lines.append(f"| **`{row_label}`** | " + " | ".join(str(v) for v in row_vals) + " |")

    lines.extend(
        [
            "",
            "## Simulación de umbral de confianza (para LAB-121)",
            "",
            "| Umbral | Retenidos | Fallback | Cobertura | Exactitud retenidos | F1 macro retenidos |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
    )
    for row in report["confidence_thresholds"]:
        acc_str = f"{row['accuracy_retained']:.4f}" if row["accuracy_retained"] is not None else "—"
        f1_str = f"{row['f1_macro_retained']:.4f}" if row["f1_macro_retained"] is not None else "—"
        lines.append(
            f"| `{row['threshold']:.2f}` | {row['retained_count']} | {row['fallback_count']} | "
            f"{row['coverage']:.4f} | {acc_str} | {f1_str} |"
        )

    return "\n".join(lines)
