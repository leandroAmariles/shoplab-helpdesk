"""Model variants, grouped cross-validation search, calibration and variant selection (LAB-118)."""

import time
from collections import Counter
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.base import clone
from sklearn.calibration import CalibratedClassifierCV
from sklearn.dummy import DummyClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import GridSearchCV, StratifiedGroupKFold, StratifiedKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import Normalizer
from sklearn.svm import LinearSVC

from intent.constants import (
    CANONICAL_LABELS,
    CV_FOLDS,
    DEFAULT_SEED,
    SELECTION_TIE_MARGIN,
    VARIANT_AUTO,
    VARIANT_DUMMY,
    VARIANT_EMBEDDING_LOGREG,
    VARIANT_NAMES,
    VARIANT_TFIDF_LINEARSVC,
    VARIANT_TFIDF_LOGREG,
)
from intent.data import assert_no_test_examples
from intent.embeddings import EmbeddingTransformer
from intent.errors import ClassifierConfigurationError
from intent.features import build_tfidf_union
from intent.metrics import align_probabilities, compute_calibration_metrics
from intent.preprocess import TextPreprocessor
from intent_dataset.dataset import Example


@dataclass(frozen=True)
class GridSearchSummary:
    """Result of a grouped grid search on the train partition."""

    best_estimator: Pipeline
    best_params: dict[str, Any]
    cv_f1_macro_mean: float
    cv_f1_macro_std: float


@dataclass(frozen=True)
class VariantComparisonEntry:
    """Comparison row for one candidate variant evaluated on train (CV) and val."""

    variant: str
    best_params: dict[str, Any]
    cv_f1_macro_mean: float
    cv_f1_macro_std: float
    val_f1_macro: float
    val_accuracy: float
    val_brier_score: float
    val_log_loss: float
    val_ece: float
    val_latency_ms_per_msg: float
    requires_external_service: bool

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class VariantSelectionResult:
    """Complete outcome of comparing all variants and fitting the final winning pipeline."""

    selected_variant: str
    selection_reason: str
    selected_params: dict[str, Any]
    majority_label: str
    variants: tuple[VariantComparisonEntry, ...]
    final_pipeline: Pipeline

    @property
    def entries(self) -> dict[str, VariantComparisonEntry]:
        return {v.variant: v for v in self.variants}


def majority_class_label(examples: Sequence[Example]) -> str:
    """Return the most frequent class label in the given examples (tie-broken by canonical order)."""
    counts = Counter(ex.label for ex in examples)
    return max(CANONICAL_LABELS, key=lambda lbl: (counts.get(lbl, 0), -CANONICAL_LABELS.index(lbl)))


def build_dummy_pipeline(*, seed: int = DEFAULT_SEED) -> Pipeline:
    """Build the trivial majority-class baseline pipeline."""
    return Pipeline(
        steps=[
            ("preprocess", TextPreprocessor()),
            ("clf", DummyClassifier(strategy="prior", random_state=seed)),
        ]
    )


def build_tfidf_logreg_pipeline(
    *,
    seed: int = DEFAULT_SEED,
    c: float = 2.0,
    class_weight: str | None = None,
    word_min_df: int = 1,
    char_min_df: int = 2,
) -> Pipeline:
    """Build the lexical TF-IDF (word + char_wb) + LogisticRegression pipeline."""
    return Pipeline(
        steps=[
            ("preprocess", TextPreprocessor()),
            ("features", build_tfidf_union(word_min_df=word_min_df, char_min_df=char_min_df)),
            (
                "clf",
                LogisticRegression(
                    C=c,
                    class_weight=class_weight,
                    solver="lbfgs",
                    max_iter=2000,
                    random_state=seed,
                ),
            ),
        ]
    )


def build_tfidf_linearsvc_pipeline(
    *,
    seed: int = DEFAULT_SEED,
    cv_folds: int = CV_FOLDS,
    c: float = 1.0,
    class_weight: str | None = None,
    word_min_df: int = 1,
    char_min_df: int = 2,
) -> Pipeline:
    """Build the lexical TF-IDF (word + char_wb) + calibrated LinearSVC pipeline."""
    base_svc = LinearSVC(
        C=c,
        class_weight=class_weight,
        dual="auto",
        max_iter=5000,
        random_state=seed,
    )
    inner_cv = StratifiedKFold(n_splits=cv_folds, shuffle=True, random_state=seed)
    calibrated = CalibratedClassifierCV(
        estimator=base_svc,
        method="sigmoid",
        cv=inner_cv,
    )
    return Pipeline(
        steps=[
            ("preprocess", TextPreprocessor()),
            ("features", build_tfidf_union(word_min_df=word_min_df, char_min_df=char_min_df)),
            ("clf", calibrated),
        ]
    )


def build_embedding_logreg_pipeline(
    cache_path: str,
    *,
    seed: int = DEFAULT_SEED,
    c: float = 2.0,
    class_weight: str | None = None,
    offline_only: bool = True,
) -> Pipeline:
    """Build the retrieval embedding (LAB-21) + L2 normalizer + LogisticRegression pipeline."""
    return Pipeline(
        steps=[
            ("preprocess", TextPreprocessor()),
            ("embed", EmbeddingTransformer(cache_path=cache_path, offline_only=offline_only)),
            ("norm", Normalizer(norm="l2")),
            (
                "clf",
                LogisticRegression(
                    C=c,
                    class_weight=class_weight,
                    solver="lbfgs",
                    max_iter=2000,
                    random_state=seed,
                ),
            ),
        ]
    )


def make_grouped_cv_splits(
    train_examples: Sequence[Example],
    *,
    cv_folds: int = CV_FOLDS,
    seed: int = DEFAULT_SEED,
) -> list[tuple[np.ndarray, np.ndarray]]:
    """Create deterministic StratifiedGroupKFold index splits ensuring no test example is present."""
    assert_no_test_examples(train_examples)
    texts = [ex.text for ex in train_examples]
    labels = [ex.label for ex in train_examples]
    groups = [ex.group_id for ex in train_examples]
    splitter = StratifiedGroupKFold(n_splits=cv_folds, shuffle=True, random_state=seed)
    return [
        (np.asarray(tr, dtype=np.int64), np.asarray(va, dtype=np.int64))
        for tr, va in splitter.split(texts, labels, groups)
    ]


def run_grouped_grid_search(
    pipeline: Pipeline,
    param_grid: dict[str, list[Any]],
    train_examples: Sequence[Example],
    *,
    seed: int = DEFAULT_SEED,
    cv_folds: int = CV_FOLDS,
) -> GridSearchSummary:
    """Run deterministic GridSearchCV over train_examples with StratifiedGroupKFold optimizing f1_macro."""
    assert_no_test_examples(train_examples)
    cv_splits = make_grouped_cv_splits(train_examples, cv_folds=cv_folds, seed=seed)
    x_train = [ex.text for ex in train_examples]
    y_train = [ex.label for ex in train_examples]

    search = GridSearchCV(
        estimator=clone(pipeline),
        param_grid=param_grid,
        scoring="f1_macro",
        cv=cv_splits,
        refit=True,
        n_jobs=1,
    )
    search.fit(x_train, y_train)
    best_idx = int(search.best_index_)
    mean_score = float(search.cv_results_["mean_test_score"][best_idx])
    std_score = float(search.cv_results_["std_test_score"][best_idx])

    return GridSearchSummary(
        best_estimator=search.best_estimator_,
        best_params=dict(search.best_params_),
        cv_f1_macro_mean=round(mean_score, 6),
        cv_f1_macro_std=round(std_score, 6),
    )


def _evaluate_variant_on_val(
    variant_name: str,
    estimator: Pipeline,
    best_params: dict[str, Any],
    cv_mean: float,
    cv_std: float,
    val_examples: Sequence[Example],
    *,
    requires_external_service: bool,
) -> VariantComparisonEntry:
    assert_no_test_examples(val_examples)
    x_val = [ex.text for ex in val_examples]
    y_val = [ex.label for ex in val_examples]

    t0 = time.perf_counter()
    y_proba = estimator.predict_proba(x_val)
    y_pred = [str(lbl) for lbl in estimator.predict(x_val)]
    elapsed_ms = (time.perf_counter() - t0) * 1000.0

    aligned_proba = align_probabilities(y_proba, list(estimator.classes_), CANONICAL_LABELS)
    val_f1 = float(f1_score(y_val, y_pred, labels=list(CANONICAL_LABELS), average="macro", zero_division=0))
    val_acc = float(accuracy_score(y_val, y_pred))
    cal = compute_calibration_metrics(y_val, y_pred, aligned_proba, CANONICAL_LABELS)

    return VariantComparisonEntry(
        variant=variant_name,
        best_params=best_params,
        cv_f1_macro_mean=round(cv_mean, 6),
        cv_f1_macro_std=round(cv_std, 6),
        val_f1_macro=round(val_f1, 6),
        val_accuracy=round(val_acc, 6),
        val_brier_score=float(cal["brier_score"]),
        val_log_loss=float(cal["log_loss"]),
        val_ece=float(cal["ece"]),
        val_latency_ms_per_msg=round(elapsed_ms / max(1, len(x_val)), 4),
        requires_external_service=requires_external_service,
    )


def _select_winner(
    entries: dict[str, VariantComparisonEntry],
    forced_variant: str = VARIANT_AUTO,
) -> tuple[str, str]:
    if forced_variant != VARIANT_AUTO:
        if forced_variant not in entries:
            raise ClassifierConfigurationError(
                f"Variante desconocida {forced_variant!r}. Opciones válidas: auto, {', '.join(VARIANT_NAMES)}."
            )
        return forced_variant, f"Variante fijada explícitamente por parámetro (--variant {forced_variant})."

    lexical_candidates = [entries[VARIANT_TFIDF_LOGREG], entries[VARIANT_TFIDF_LINEARSVC]]
    best_lexical = max(
        lexical_candidates,
        key=lambda e: (e.val_f1_macro, e.cv_f1_macro_mean, -e.val_brier_score),
    )
    emb_entry = entries[VARIANT_EMBEDDING_LOGREG]

    if emb_entry.val_f1_macro > best_lexical.val_f1_macro + SELECTION_TIE_MARGIN:
        reason = (
            f"{emb_entry.variant} superó a la mejor variante léxica ({best_lexical.variant}) en F1 macro de "
            f"validación por más de {SELECTION_TIE_MARGIN:.2f} ({emb_entry.val_f1_macro:.4f} vs "
            f"{best_lexical.val_f1_macro:.4f})."
        )
        return emb_entry.variant, reason

    reason = (
        f"Seleccionada {best_lexical.variant} (val_f1_macro={best_lexical.val_f1_macro:.4f}, "
        f"cv_f1_macro={best_lexical.cv_f1_macro_mean:.4f}, brier={best_lexical.val_brier_score:.4f}): "
        "alcanza el mejor rendimiento en validación (o empata dentro del margen de 0.01 con embeddings) "
        "siendo 100 % autocontenida en CPU sin llamadas de red en inferencia."
    )
    return best_lexical.variant, reason


def compare_and_select_variants(
    train_examples: Sequence[Example],
    val_examples: Sequence[Example],
    *,
    embeddings_cache_path: Path,
    seed: int = DEFAULT_SEED,
    cv_folds: int = CV_FOLDS,
    forced_variant: str = VARIANT_AUTO,
    allow_remote_embeddings: bool = False,
) -> VariantSelectionResult:
    """Train, cross-validate on train, compare on val, select winner, and refit on train+val without touching test."""
    assert_no_test_examples(train_examples)
    assert_no_test_examples(val_examples)

    x_train = [ex.text for ex in train_examples]
    y_train = [ex.label for ex in train_examples]

    # 1. Dummy majority baseline
    dummy_pipe = build_dummy_pipeline(seed=seed)
    cv_splits = make_grouped_cv_splits(train_examples, cv_folds=cv_folds, seed=seed)
    dummy_fold_scores: list[float] = []
    for tr_idx, va_idx in cv_splits:
        fold_pipe = clone(dummy_pipe)
        fold_pipe.fit([x_train[i] for i in tr_idx], [y_train[i] for i in tr_idx])
        preds = fold_pipe.predict([x_train[i] for i in va_idx])
        dummy_fold_scores.append(
            float(
                f1_score(
                    [y_train[i] for i in va_idx],
                    preds,
                    labels=list(CANONICAL_LABELS),
                    average="macro",
                    zero_division=0,
                )
            )
        )
    dummy_pipe.fit(x_train, y_train)
    dummy_entry = _evaluate_variant_on_val(
        VARIANT_DUMMY,
        dummy_pipe,
        {},
        float(np.mean(dummy_fold_scores)),
        float(np.std(dummy_fold_scores)),
        val_examples,
        requires_external_service=False,
    )

    # 2. TF-IDF + LogisticRegression
    logreg_grid = {
        "features__word__min_df": [1, 2],
        "features__char__min_df": [2, 3],
        "clf__C": [0.5, 1.0, 2.0, 5.0, 10.0],
        "clf__class_weight": [None, "balanced"],
    }
    logreg_search = run_grouped_grid_search(
        build_tfidf_logreg_pipeline(seed=seed),
        logreg_grid,
        train_examples,
        seed=seed,
        cv_folds=cv_folds,
    )
    logreg_entry = _evaluate_variant_on_val(
        VARIANT_TFIDF_LOGREG,
        logreg_search.best_estimator,
        logreg_search.best_params,
        logreg_search.cv_f1_macro_mean,
        logreg_search.cv_f1_macro_std,
        val_examples,
        requires_external_service=False,
    )

    # 3. TF-IDF + Calibrated LinearSVC
    svc_grid = {
        "features__word__min_df": [1, 2],
        "features__char__min_df": [2, 3],
        "clf__estimator__C": [0.25, 0.5, 1.0, 2.0, 5.0],
        "clf__estimator__class_weight": [None, "balanced"],
    }
    svc_search = run_grouped_grid_search(
        build_tfidf_linearsvc_pipeline(seed=seed, cv_folds=cv_folds),
        svc_grid,
        train_examples,
        seed=seed,
        cv_folds=cv_folds,
    )
    svc_entry = _evaluate_variant_on_val(
        VARIANT_TFIDF_LINEARSVC,
        svc_search.best_estimator,
        svc_search.best_params,
        svc_search.cv_f1_macro_mean,
        svc_search.cv_f1_macro_std,
        val_examples,
        requires_external_service=False,
    )

    # 4. Retrieval Embeddings + LogisticRegression
    emb_grid = {
        "clf__C": [0.25, 0.5, 1.0, 2.0, 5.0, 10.0],
        "clf__class_weight": [None, "balanced"],
    }
    emb_base = build_embedding_logreg_pipeline(
        str(embeddings_cache_path),
        seed=seed,
        offline_only=not allow_remote_embeddings,
    )
    emb_search = run_grouped_grid_search(
        emb_base,
        emb_grid,
        train_examples,
        seed=seed,
        cv_folds=cv_folds,
    )
    emb_entry = _evaluate_variant_on_val(
        VARIANT_EMBEDDING_LOGREG,
        emb_search.best_estimator,
        emb_search.best_params,
        emb_search.cv_f1_macro_mean,
        emb_search.cv_f1_macro_std,
        val_examples,
        requires_external_service=True,
    )

    entries_by_name = {
        VARIANT_DUMMY: dummy_entry,
        VARIANT_TFIDF_LOGREG: logreg_entry,
        VARIANT_TFIDF_LINEARSVC: svc_entry,
        VARIANT_EMBEDDING_LOGREG: emb_entry,
    }
    best_estimators = {
        VARIANT_DUMMY: dummy_pipe,
        VARIANT_TFIDF_LOGREG: logreg_search.best_estimator,
        VARIANT_TFIDF_LINEARSVC: svc_search.best_estimator,
        VARIANT_EMBEDDING_LOGREG: emb_search.best_estimator,
    }

    selected_variant, selection_reason = _select_winner(entries_by_name, forced_variant=forced_variant)
    selected_entry = entries_by_name[selected_variant]

    # Refit the winning estimator configuration on train + val (still zero test examples)
    combined_examples = list(train_examples) + list(val_examples)
    assert_no_test_examples(combined_examples)
    final_pipeline = clone(best_estimators[selected_variant])
    final_pipeline.fit([ex.text for ex in combined_examples], [ex.label for ex in combined_examples])

    return VariantSelectionResult(
        selected_variant=selected_variant,
        selection_reason=selection_reason,
        selected_params=dict(selected_entry.best_params),
        majority_label=majority_class_label(train_examples),
        variants=tuple(entries_by_name[name] for name in VARIANT_NAMES),
        final_pipeline=final_pipeline,
    )
