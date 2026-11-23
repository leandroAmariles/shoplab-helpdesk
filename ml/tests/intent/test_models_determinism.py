"""Unit tests for pipelines, grouped cross-validation, variant selection and determinism (LAB-118)."""

from pathlib import Path

import numpy as np
import pytest

from intent.artifacts import (
    extract_top_features_by_class,
    load_pipeline_artifact,
    save_pipeline_artifact,
)
from intent.constants import (
    CANONICAL_LABELS,
    VARIANT_DUMMY,
    VARIANT_EMBEDDING_LOGREG,
    VARIANT_NAMES,
    VARIANT_TFIDF_LINEARSVC,
    VARIANT_TFIDF_LOGREG,
)
from intent.embeddings import build_deterministic_embeddings_cache
from intent.errors import DataLeakageError
from intent.features import build_tfidf_union
from intent.models import (
    build_dummy_pipeline,
    build_embedding_logreg_pipeline,
    build_tfidf_linearsvc_pipeline,
    build_tfidf_logreg_pipeline,
    compare_and_select_variants,
    make_grouped_cv_splits,
    run_grouped_grid_search,
)
from intent.preprocess import preprocess_text
from intent_dataset.dataset import Example


def test_build_tfidf_union_extracts_word_and_char_ngrams_and_mask_tokens(
    synthetic_examples: list[Example],
) -> None:
    train_texts = [preprocess_text(ex.text) for ex in synthetic_examples if ex.split == "train"]
    union = build_tfidf_union(word_min_df=1, char_min_df=1)
    matrix = union.fit_transform(train_texts)
    feature_names = list(union.get_feature_names_out())

    assert matrix.shape[0] == len(train_texts)
    assert any(name.startswith("word____mask_id__") for name in feature_names)
    assert any(name.startswith("char__") for name in feature_names)


def test_make_grouped_cv_splits_never_splits_same_group_id_and_rejects_test_examples(
    synthetic_examples: list[Example],
) -> None:
    train_examples = [ex for ex in synthetic_examples if ex.split == "train"]
    splits = make_grouped_cv_splits(train_examples, cv_folds=3, seed=118)
    assert len(splits) == 3

    for train_idx, val_idx in splits:
        train_groups = {train_examples[i].group_id for i in train_idx}
        val_groups = {train_examples[i].group_id for i in val_idx}
        assert train_groups.isdisjoint(val_groups)

    with pytest.raises(DataLeakageError, match="test"):
        make_grouped_cv_splits(synthetic_examples, cv_folds=3, seed=118)


def test_run_grouped_grid_search_is_deterministic_and_rejects_test_split(
    synthetic_examples: list[Example],
) -> None:
    train_examples = [ex for ex in synthetic_examples if ex.split == "train"]
    val_texts = [ex.text for ex in synthetic_examples if ex.split == "val"]

    res1 = run_grouped_grid_search(
        build_tfidf_logreg_pipeline(seed=118),
        {"clf__C": [0.5, 2.0]},
        train_examples,
        seed=118,
        cv_folds=3,
    )
    res2 = run_grouped_grid_search(
        build_tfidf_logreg_pipeline(seed=118),
        {"clf__C": [0.5, 2.0]},
        train_examples,
        seed=118,
        cv_folds=3,
    )

    assert res1.best_params == res2.best_params
    assert res1.cv_f1_macro_mean == pytest.approx(res2.cv_f1_macro_mean, abs=1e-12)
    np.testing.assert_array_equal(res1.best_estimator.predict(val_texts), res2.best_estimator.predict(val_texts))
    np.testing.assert_allclose(
        res1.best_estimator.predict_proba(val_texts),
        res2.best_estimator.predict_proba(val_texts),
        atol=1e-12,
    )


def test_all_four_variants_expose_calibrated_probabilities_summing_to_one(
    tmp_path: Path,
    synthetic_examples: list[Example],
) -> None:
    train_examples = [ex for ex in synthetic_examples if ex.split == "train"]
    val_texts = [ex.text for ex in synthetic_examples if ex.split == "val"]
    x_train = [ex.text for ex in train_examples]
    y_train = [ex.label for ex in train_examples]

    cache_path = tmp_path / "embeddings_cache.npz"
    build_deterministic_embeddings_cache(synthetic_examples, cache_path, dim=32, seed=118)

    pipelines = {
        VARIANT_DUMMY: build_dummy_pipeline(seed=118),
        VARIANT_TFIDF_LOGREG: build_tfidf_logreg_pipeline(seed=118),
        VARIANT_TFIDF_LINEARSVC: build_tfidf_linearsvc_pipeline(seed=118, cv_folds=3),
        VARIANT_EMBEDDING_LOGREG: build_embedding_logreg_pipeline(str(cache_path), seed=118),
    }

    for name, pipeline in pipelines.items():
        pipeline.fit(x_train, y_train)
        probs = pipeline.predict_proba(val_texts)
        preds = pipeline.predict(val_texts)
        classes = list(pipeline.classes_)

        assert probs.shape == (len(val_texts), len(CANONICAL_LABELS)), name
        assert np.all((probs >= 0.0) & (probs <= 1.0)), name
        np.testing.assert_allclose(probs.sum(axis=1), np.ones(len(val_texts)), atol=1e-6)
        assert set(classes) == set(CANONICAL_LABELS), name
        expected_preds = [classes[idx] for idx in np.argmax(probs, axis=1)]
        assert list(preds) == expected_preds, name


def test_compare_and_select_variants_and_artifact_roundtrip(
    tmp_path: Path,
    synthetic_examples: list[Example],
) -> None:
    train_examples = [ex for ex in synthetic_examples if ex.split == "train"]
    val_examples = [ex for ex in synthetic_examples if ex.split == "val"]
    test_texts = [ex.text for ex in synthetic_examples if ex.split == "test"]

    cache_path = tmp_path / "embeddings_cache.npz"
    build_deterministic_embeddings_cache(synthetic_examples, cache_path, dim=32, seed=118)

    selection1 = compare_and_select_variants(
        train_examples,
        val_examples,
        embeddings_cache_path=cache_path,
        seed=118,
        cv_folds=3,
    )
    selection2 = compare_and_select_variants(
        train_examples,
        val_examples,
        embeddings_cache_path=cache_path,
        seed=118,
        cv_folds=3,
    )

    assert [v.variant for v in selection1.variants] == list(VARIANT_NAMES)
    assert selection1.selected_variant == selection2.selected_variant
    assert selection1.selected_variant != VARIANT_DUMMY

    artifact_path = tmp_path / "model.joblib"
    save_pipeline_artifact(selection1.final_pipeline, artifact_path)
    loaded = load_pipeline_artifact(artifact_path)

    np.testing.assert_array_equal(
        selection1.final_pipeline.predict(test_texts),
        loaded.predict(test_texts),
    )
    np.testing.assert_allclose(
        selection1.final_pipeline.predict_proba(test_texts),
        loaded.predict_proba(test_texts),
        atol=1e-12,
    )
    np.testing.assert_allclose(
        selection1.final_pipeline.predict_proba(test_texts),
        selection2.final_pipeline.predict_proba(test_texts),
        atol=1e-12,
    )

    top_features = extract_top_features_by_class(loaded, top_k=5)
    if selection1.selected_variant in (VARIANT_TFIDF_LOGREG, VARIANT_TFIDF_LINEARSVC):
        assert set(top_features.keys()) == set(CANONICAL_LABELS)
        assert all(len(items) > 0 for items in top_features.values())
