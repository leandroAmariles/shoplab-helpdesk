"""Evaluation metrics, confusion matrix, calibration (Brier, log-loss, ECE) and threshold analysis (LAB-118)."""

from collections.abc import Sequence
from typing import Any

import numpy as np
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)

from intent.constants import (
    CALIBRATION_BINS,
    CANONICAL_LABELS,
    CONFIDENCE_THRESHOLDS,
    LANGUAGES,
    MIN_TEST_F1_MACRO,
    SCHEMA_VERSION,
)
from intent_dataset.dataset import Example


def compute_per_class_metrics(
    y_true: Sequence[str],
    y_pred: Sequence[str],
    labels: Sequence[str] = CANONICAL_LABELS,
) -> dict[str, dict[str, float | int]]:
    """Compute precision, recall, F1 and support for each canonical label."""
    precisions, recalls, f1s, supports = precision_recall_fscore_support(
        y_true,
        y_pred,
        labels=list(labels),
        zero_division=0,
    )
    result: dict[str, dict[str, float | int]] = {}
    for idx, label in enumerate(labels):
        result[label] = {
            "precision": round(float(precisions[idx]), 6),
            "recall": round(float(recalls[idx]), 6),
            "f1": round(float(f1s[idx]), 6),
            "support": int(supports[idx]),
        }
    return result


def compute_confusion_matrix(
    y_true: Sequence[str],
    y_pred: Sequence[str],
    labels: Sequence[str] = CANONICAL_LABELS,
) -> dict[str, Any]:
    """Return a 5x5 confusion matrix in canonical label order (rows=true, cols=predicted)."""
    matrix = confusion_matrix(y_true, y_pred, labels=list(labels))
    return {
        "labels": list(labels),
        "matrix": [[int(val) for val in row] for row in matrix.tolist()],
    }


def align_probabilities(
    y_proba: np.ndarray,
    model_classes: Sequence[str],
    canonical_labels: Sequence[str] = CANONICAL_LABELS,
) -> np.ndarray:
    """Reorder columns of a probability matrix to match canonical_labels."""
    probs = np.asarray(y_proba, dtype=np.float64)
    class_to_col = {str(label): idx for idx, label in enumerate(model_classes)}
    aligned = np.zeros((probs.shape[0], len(canonical_labels)), dtype=np.float64)
    for target_col, label in enumerate(canonical_labels):
        if label in class_to_col:
            aligned[:, target_col] = probs[:, class_to_col[label]]
    row_sums = aligned.sum(axis=1, keepdims=True)
    row_sums = np.where(row_sums > 1e-12, row_sums, 1.0)
    return aligned / row_sums


def multiclass_brier_score(
    y_true: Sequence[str],
    aligned_proba: np.ndarray,
    canonical_labels: Sequence[str] = CANONICAL_LABELS,
) -> float:
    """Compute the mean multiclass Brier score sum_k (p_ik - y_ik)^2 over all examples."""
    label_to_idx = {label: idx for idx, label in enumerate(canonical_labels)}
    n = len(y_true)
    one_hot = np.zeros((n, len(canonical_labels)), dtype=np.float64)
    for row_idx, label in enumerate(y_true):
        one_hot[row_idx, label_to_idx[label]] = 1.0
    squared_error = np.sum((aligned_proba - one_hot) ** 2, axis=1)
    return float(np.mean(squared_error))


def compute_calibration_metrics(
    y_true: Sequence[str],
    y_pred: Sequence[str],
    aligned_proba: np.ndarray,
    canonical_labels: Sequence[str] = CANONICAL_LABELS,
    *,
    n_bins: int = CALIBRATION_BINS,
) -> dict[str, Any]:
    """Compute multiclass Brier score, log-loss, Expected Calibration Error (ECE) and reliability bins."""
    brier = multiclass_brier_score(y_true, aligned_proba, canonical_labels)
    clipped = np.clip(aligned_proba, 1e-15, 1.0 - 1e-15)
    clipped = clipped / clipped.sum(axis=1, keepdims=True)
    label_to_idx = {label: idx for idx, label in enumerate(canonical_labels)}
    true_probs = np.asarray([clipped[row_idx, label_to_idx[lbl]] for row_idx, lbl in enumerate(y_true)])
    ll = float(-np.mean(np.log(true_probs)))

    confidences = np.max(aligned_proba, axis=1)
    correct = np.asarray([1.0 if yt == yp else 0.0 for yt, yp in zip(y_true, y_pred, strict=True)], dtype=np.float64)

    edges = np.linspace(0.0, 1.0, n_bins + 1)
    bins_report: list[dict[str, float | int | None]] = []
    ece = 0.0
    n_total = len(y_true)

    for b_idx in range(n_bins):
        low = float(edges[b_idx])
        high = float(edges[b_idx + 1])
        if b_idx == 0:
            mask = (confidences >= low) & (confidences <= high)
        else:
            mask = (confidences > low) & (confidences <= high)

        count = int(np.sum(mask))
        if count > 0:
            mean_conf = float(np.mean(confidences[mask]))
            emp_acc = float(np.mean(correct[mask]))
            gap = abs(emp_acc - mean_conf)
            ece += (count / n_total) * gap
            bins_report.append(
                {
                    "bin_lower": round(low, 2),
                    "bin_upper": round(high, 2),
                    "count": count,
                    "mean_confidence": round(mean_conf, 6),
                    "accuracy": round(emp_acc, 6),
                    "gap": round(gap, 6),
                }
            )
        else:
            bins_report.append(
                {
                    "bin_lower": round(low, 2),
                    "bin_upper": round(high, 2),
                    "count": 0,
                    "mean_confidence": None,
                    "accuracy": None,
                    "gap": None,
                }
            )

    return {
        "brier_score": round(brier, 6),
        "log_loss": round(ll, 6),
        "ece": round(float(ece), 6),
        "bins": bins_report,
    }


def simulate_confidence_thresholds(
    y_true: Sequence[str],
    y_pred: Sequence[str],
    aligned_proba: np.ndarray,
    *,
    thresholds: Sequence[float] = CONFIDENCE_THRESHOLDS,
    labels: Sequence[str] = CANONICAL_LABELS,
) -> list[dict[str, float | int | None]]:
    """Simulate coverage vs accuracy and macro-F1 when filtering predictions by max confidence >= threshold."""
    confidences = np.max(aligned_proba, axis=1)
    n_total = len(y_true)
    y_true_arr = np.asarray(y_true)
    y_pred_arr = np.asarray(y_pred)

    rows: list[dict[str, float | int | None]] = []
    for threshold in thresholds:
        mask = confidences >= threshold
        retained = int(np.sum(mask))
        fallback = n_total - retained
        coverage = retained / n_total if n_total > 0 else 0.0
        if retained > 0:
            yt_sub = y_true_arr[mask].tolist()
            yp_sub = y_pred_arr[mask].tolist()
            present_labels = [lbl for lbl in labels if lbl in yt_sub or lbl in yp_sub]
            acc_ret = round(float(accuracy_score(yt_sub, yp_sub)), 6)
            f1_ret = round(
                float(f1_score(yt_sub, yp_sub, labels=present_labels, average="macro", zero_division=0)),
                6,
            )
        else:
            acc_ret = None
            f1_ret = None

        rows.append(
            {
                "threshold": round(float(threshold), 2),
                "retained_count": retained,
                "fallback_count": fallback,
                "coverage": round(float(coverage), 6),
                "accuracy_retained": acc_ret,
                "f1_macro_retained": f1_ret,
            }
        )
    return rows


def _subgroup_metrics(
    y_true: Sequence[str],
    y_pred: Sequence[str],
    labels: Sequence[str] = CANONICAL_LABELS,
) -> dict[str, float | int]:
    if not y_true:
        return {"count": 0, "f1_macro": 0.0, "accuracy": 0.0}
    present = [lbl for lbl in labels if lbl in y_true or lbl in y_pred]
    return {
        "count": len(y_true),
        "f1_macro": round(float(f1_score(y_true, y_pred, labels=present, average="macro", zero_division=0)), 6),
        "accuracy": round(float(accuracy_score(y_true, y_pred)), 6),
    }


def evaluate_predictions(
    examples: Sequence[Example],
    *,
    y_pred: Sequence[str],
    y_proba: np.ndarray,
    class_labels: Sequence[str],
    majority_label: str = "pedido",
    target_f1_macro: float = MIN_TEST_F1_MACRO,
    artifact_path: str = "",
    artifact_sha256: str = "",
    dataset_sha256: str = "",
    latency_ms_total: float = 0.0,
) -> dict[str, Any]:
    """Build the complete EvaluationReport dictionary over a test split."""
    y_true = [ex.label for ex in examples]
    aligned_proba = align_probabilities(y_proba, class_labels, CANONICAL_LABELS)

    test_f1_macro = round(
        float(f1_score(y_true, y_pred, labels=list(CANONICAL_LABELS), average="macro", zero_division=0)),
        6,
    )
    test_accuracy = round(float(accuracy_score(y_true, y_pred)), 6)

    y_dummy = [majority_label] * len(y_true)
    baseline_f1_macro = round(
        float(f1_score(y_true, y_dummy, labels=list(CANONICAL_LABELS), average="macro", zero_division=0)),
        6,
    )
    baseline_accuracy = round(float(accuracy_score(y_true, y_dummy)), 6)

    by_language: dict[str, dict[str, float | int]] = {}
    for lang in LANGUAGES:
        yt_lang = [ex.label for ex in examples if ex.language == lang]
        yp_lang = [pred for ex, pred in zip(examples, y_pred, strict=True) if ex.language == lang]
        by_language[lang] = _subgroup_metrics(yt_lang, yp_lang)

    yt_non_golden = [ex.label for ex in examples if ex.golden_id is None]
    yp_non_golden = [pred for ex, pred in zip(examples, y_pred, strict=True) if ex.golden_id is None]
    non_golden_subset = _subgroup_metrics(yt_non_golden, yp_non_golden)

    confidences = np.max(aligned_proba, axis=1)
    misclassified: list[dict[str, Any]] = []
    for idx, (ex, pred) in enumerate(zip(examples, y_pred, strict=True)):
        if ex.label != pred:
            misclassified.append(
                {
                    "id": ex.id,
                    "language": ex.language,
                    "source": ex.source,
                    "text": ex.text,
                    "true_label": ex.label,
                    "predicted_label": str(pred),
                    "confidence": round(float(confidences[idx]), 6),
                }
            )

    n_examples = len(examples)
    return {
        "schema_version": SCHEMA_VERSION,
        "artifact_path": artifact_path,
        "artifact_sha256": artifact_sha256,
        "dataset_sha256": dataset_sha256,
        "test_count": n_examples,
        "target_f1_macro": round(float(target_f1_macro), 4),
        "passed_target": bool(test_f1_macro >= target_f1_macro),
        "test_f1_macro": test_f1_macro,
        "test_accuracy": test_accuracy,
        "baseline_majority_label": majority_label,
        "baseline_f1_macro": baseline_f1_macro,
        "baseline_accuracy": baseline_accuracy,
        "per_class": compute_per_class_metrics(y_true, y_pred, CANONICAL_LABELS),
        "confusion_matrix": compute_confusion_matrix(y_true, y_pred, CANONICAL_LABELS),
        "calibration": compute_calibration_metrics(y_true, y_pred, aligned_proba, CANONICAL_LABELS),
        "confidence_thresholds": simulate_confidence_thresholds(y_true, y_pred, aligned_proba),
        "by_language": by_language,
        "non_golden_subset": non_golden_subset,
        "misclassified_examples": misclassified,
        "latency_ms_total": round(float(latency_ms_total), 4),
        "latency_ms_per_example": round(float(latency_ms_total / max(1, n_examples)), 4),
    }
