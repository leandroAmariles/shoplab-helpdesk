"""Unit tests for dataset loading and anti-leakage guards (LAB-118)."""

from dataclasses import replace
from pathlib import Path

import pytest

from intent.data import (
    assert_no_test_examples,
    compute_file_sha256,
    is_dataset_human_reviewed,
    load_split_dataset,
)
from intent.errors import ClassifierConfigurationError, DataLeakageError
from intent_dataset.dataset import Example, dump_examples


def test_load_split_dataset_separates_train_val_test_and_computes_sha256(
    synthetic_dataset_path: Path,
    tmp_path: Path,
) -> None:
    review_md = tmp_path / "REVIEW.md"
    review_md.write_text("# Revisión\n- **Estado**: **BORRADOR**\n", encoding="utf-8")

    dataset = load_split_dataset(synthetic_dataset_path, review_path=review_md)

    assert len(dataset.train) > 0
    assert len(dataset.val) > 0
    assert len(dataset.test) > 0
    assert all(ex.split == "train" for ex in dataset.train)
    assert all(ex.split == "val" for ex in dataset.val)
    assert all(ex.split == "test" for ex in dataset.test)
    assert dataset.dataset_sha256 == compute_file_sha256(synthetic_dataset_path)
    assert len(dataset.dataset_sha256) == 64
    assert dataset.human_reviewed is False


def test_is_dataset_human_reviewed_detects_completed_review(tmp_path: Path) -> None:
    review_md = tmp_path / "REVIEW.md"
    review_md.write_text("# Revisión\n- **Estado**: **COMPLETADO**\n- **Revisor**: Ana\n", encoding="utf-8")
    assert is_dataset_human_reviewed(review_md) is True
    assert is_dataset_human_reviewed(tmp_path / "missing.md") is False


def test_load_split_dataset_missing_file_raises_configuration_error(tmp_path: Path) -> None:
    with pytest.raises(ClassifierConfigurationError, match="No se encontró"):
        load_split_dataset(tmp_path / "does_not_exist.jsonl")


def test_load_split_dataset_rejects_group_id_leakage_across_splits(
    synthetic_examples: list[Example],
    tmp_path: Path,
) -> None:
    leaked = list(synthetic_examples)
    train_group = next(ex.group_id for ex in leaked if ex.split == "train")
    test_idx = next(i for i, ex in enumerate(leaked) if ex.split == "test")
    leaked[test_idx] = replace(leaked[test_idx], group_id=train_group, parent_id=None)

    bad_path = tmp_path / "leaked_group.jsonl"
    dump_examples(leaked, bad_path)

    with pytest.raises(DataLeakageError, match="group_id"):
        load_split_dataset(bad_path)


def test_load_split_dataset_rejects_normalized_text_leakage_across_splits(
    synthetic_examples: list[Example],
    tmp_path: Path,
) -> None:
    leaked = list(synthetic_examples)
    train_text = next(ex.text for ex in leaked if ex.split == "train")
    test_idx = next(i for i, ex in enumerate(leaked) if ex.split == "test")
    leaked[test_idx] = replace(leaked[test_idx], text=f"  {train_text.upper()}!! ")

    bad_path = tmp_path / "leaked_text.jsonl"
    dump_examples(leaked, bad_path)

    with pytest.raises(DataLeakageError, match="fuga|particiones"):
        load_split_dataset(bad_path)


def test_assert_no_test_examples_rejects_any_test_split_item(synthetic_examples: list[Example]) -> None:
    train_only = [ex for ex in synthetic_examples if ex.split == "train"]
    assert_no_test_examples(train_only)

    with pytest.raises(DataLeakageError, match="test"):
        assert_no_test_examples(synthetic_examples)
