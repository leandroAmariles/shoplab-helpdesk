"""Unit tests for evaluation metrics, confusion matrix, calibration and confidence thresholds (LAB-118)."""

import numpy as np
import pytest

from intent.constants import CANONICAL_LABELS, CONFIDENCE_THRESHOLDS
from intent.metrics import (
    compute_calibration_metrics,
    compute_confusion_matrix,
    compute_per_class_metrics,
    evaluate_predictions,
    multiclass_brier_score,
    simulate_confidence_thresholds,
)
from intent_dataset.dataset import Example


def test_compute_per_class_and_confusion_matrix_follow_canonical_label_order() -> None:
    y_true = list(CANONICAL_LABELS) * 2
    y_pred = list(CANONICAL_LABELS) * 2
    per_class = compute_per_class_metrics(y_true, y_pred)
    cm = compute_confusion_matrix(y_true, y_pred)

    assert tuple(per_class.keys()) == CANONICAL_LABELS
    for label in CANONICAL_LABELS:
        assert per_class[label]["precision"] == pytest.approx(1.0)
        assert per_class[label]["recall"] == pytest.approx(1.0)
        assert per_class[label]["f1"] == pytest.approx(1.0)
        assert per_class[label]["support"] == 2

    assert cm["labels"] == list(CANONICAL_LABELS)
    assert cm["matrix"] == [
        [2, 0, 0, 0, 0],
        [0, 2, 0, 0, 0],
        [0, 0, 2, 0, 0],
        [0, 0, 0, 2, 0],
        [0, 0, 0, 0, 2],
    ]


def test_calibration_metrics_on_perfect_and_imperfect_probabilities() -> None:
    y_true = list(CANONICAL_LABELS)
    probs_perfect = np.eye(len(CANONICAL_LABELS), dtype=np.float64)

    brier = multiclass_brier_score(y_true, probs_perfect, CANONICAL_LABELS)
    assert brier == pytest.approx(0.0, abs=1e-12)

    cal = compute_calibration_metrics(y_true, y_true, probs_perfect, CANONICAL_LABELS, n_bins=5)
    assert cal["brier_score"] == pytest.approx(0.0, abs=1e-12)
    assert cal["ece"] == pytest.approx(0.0, abs=1e-12)
    assert cal["log_loss"] < 1e-6
    assert len(cal["bins"]) == 5


def test_simulate_confidence_thresholds_Produces_monotonic_coverage() -> None:
    y_true = ["politica", "producto", "pedido", "escalado", "charla"]
    y_pred = ["politica", "producto", "pedido", "escalado", "politica"]
    # Max confidences: 0.95, 0.88, 0.76, 0.65, 0.45 (the last one is wrong and low confidence)
    probs = np.array(
        [
            [0.95, 0.02, 0.01, 0.01, 0.01],
            [0.03, 0.88, 0.03, 0.03, 0.03],
            [0.06, 0.06, 0.76, 0.06, 0.06],
            [0.08, 0.09, 0.09, 0.65, 0.09],
            [0.45, 0.15, 0.15, 0.15, 0.10],
        ],
        dtype=np.float64,
    )

    sim = simulate_confidence_thresholds(y_true, y_pred, probs, thresholds=CONFIDENCE_THRESHOLDS)
    assert [row["threshold"] for row in sim] == list(CONFIDENCE_THRESHOLDS)
    coverages = [row["coverage"] for row in sim]
    assert coverages == sorted(coverages, reverse=True)
    # At threshold 0.50, the 4 correct predictions (>= 0.65) are retained and the wrong one (0.45) goes to fallback
    row_50 = sim[0]
    assert row_50["retained_count"] == 4
    assert row_50["fallback_count"] == 1
    assert row_50["accuracy_retained"] == pytest.approx(1.0)


def test_evaluate_predictions_includes_language_and_non_golden_breakdowns(
    synthetic_examples: list[Example],
) -> None:
    test_examples = [ex for ex in synthetic_examples if ex.split == "test"]
    n = len(test_examples)
    label_to_idx = {label: idx for idx, label in enumerate(CANONICAL_LABELS)}
    probs = np.full((n, len(CANONICAL_LABELS)), 0.025, dtype=np.float64)
    y_pred: list[str] = []
    for i, ex in enumerate(test_examples):
        probs[i, label_to_idx[ex.label]] = 0.90
        y_pred.append(ex.label)

    report = evaluate_predictions(
        test_examples,
        y_pred=y_pred,
        y_proba=probs,
        class_labels=CANONICAL_LABELS,
        majority_label="pedido",
        target_f1_macro=0.85,
        artifact_path="artifacts/intent/intent_classifier.joblib",
        artifact_sha256="a" * 64,
        dataset_sha256="b" * 64,
        latency_ms_total=2.5,
    )

    assert report["passed_target"] is True
    assert report["test_f1_macro"] == pytest.approx(1.0)
    assert report["baseline_f1_macro"] < 0.30
    assert "es" in report["by_language"]
    assert "pt" in report["by_language"]
    assert report["non_golden_subset"]["count"] > 0
    assert report["misclassified_examples"] == []
