"""Unit and integration tests for the intent CLI (train.py, evaluate.py, wrappers, exit codes) (LAB-118)."""

import json
from pathlib import Path

from intent.constants import (
    DEFAULT_SEED,
    SPLIT_TEST,
    SPLIT_TRAIN,
    VARIANT_TFIDF_LOGREG,
)
from intent.evaluate import main as evaluate_main
from intent.train import main as train_main
from intent_dataset.dataset import Example, dump_examples


def test_train_and_evaluate_cli_lifecycle(tmp_path: Path, synthetic_dataset_path: Path) -> None:
    artifact_path = tmp_path / "intent_classifier.joblib"
    metadata_path = tmp_path / "train_metadata.json"
    cache_path = tmp_path / "embeddings_cache.npz"
    report_path = tmp_path / "eval_report.json"

    train_args = [
        "--dataset",
        str(synthetic_dataset_path),
        "--artifact",
        str(artifact_path),
        "--metadata-out",
        str(metadata_path),
        "--embeddings-cache",
        str(cache_path),
        "--seed",
        str(DEFAULT_SEED),
        "--cv-folds",
        "2",
        "--variant",
        VARIANT_TFIDF_LOGREG,
    ]

    ret_train = train_main(train_args)
    assert ret_train == 0
    assert artifact_path.exists()
    assert metadata_path.exists()

    meta = json.loads(metadata_path.read_text(encoding="utf-8"))
    assert meta["selected_variant"] == VARIANT_TFIDF_LOGREG
    assert meta["seed"] == DEFAULT_SEED
    assert "variants" in meta
    assert len(meta["variants"]) == 4

    eval_args = [
        "--dataset",
        str(synthetic_dataset_path),
        "--artifact",
        str(artifact_path),
        "--report-out",
        str(report_path),
        "--min-f1-macro",
        "0.20",
    ]

    ret_eval = evaluate_main(eval_args)
    assert ret_eval == 0
    assert report_path.exists()

    rep = json.loads(report_path.read_text(encoding="utf-8"))
    assert rep["schema_version"] == "1.0"
    assert rep["passed_target"] is True
    assert "per_class" in rep
    assert "confusion_matrix" in rep
    assert "calibration" in rep


def test_evaluate_cli_fails_when_target_not_met(tmp_path: Path, synthetic_dataset_path: Path) -> None:
    artifact_path = tmp_path / "intent_classifier.joblib"
    metadata_path = tmp_path / "train_metadata.json"
    cache_path = tmp_path / "embeddings_cache.npz"
    report_path = tmp_path / "eval_report.json"

    train_main(
        [
            "--dataset",
            str(synthetic_dataset_path),
            "--artifact",
            str(artifact_path),
            "--metadata-out",
            str(metadata_path),
            "--embeddings-cache",
            str(cache_path),
            "--cv-folds",
            "2",
            "--variant",
            VARIANT_TFIDF_LOGREG,
        ]
    )

    ret_eval = evaluate_main(
        [
            "--dataset",
            str(synthetic_dataset_path),
            "--artifact",
            str(artifact_path),
            "--report-out",
            str(report_path),
            "--min-f1-macro",
            "1.05",
        ]
    )
    assert ret_eval == 1
    assert report_path.exists()
    rep = json.loads(report_path.read_text(encoding="utf-8"))
    assert rep["passed_target"] is False


def test_cli_exit_code_2_on_missing_file_or_invalid_args(tmp_path: Path) -> None:
    missing = tmp_path / "non_existent.jsonl"
    assert train_main(["--dataset", str(missing)]) == 2
    assert evaluate_main(["--dataset", str(missing)]) == 2


def test_train_cli_detects_data_leakage(tmp_path: Path, synthetic_examples: list[Example]) -> None:
    # Corrupt synthetic dataset by assigning the same group_id across train and test
    corrupted: list[Example] = []
    for ex in synthetic_examples:
        if ex.split == SPLIT_TEST and corrupted:
            # Overwrite group_id to a train group
            corrupted.append(
                Example(
                    id=ex.id,
                    text=ex.text,
                    label=ex.label,
                    language=ex.language,
                    source=ex.source,
                    group_id="S_LEAKED_GROUP",
                    parent_id=ex.parent_id,
                    golden_id=ex.golden_id,
                    split=SPLIT_TEST,
                    reviewed=ex.reviewed,
                )
            )
        elif ex.split == SPLIT_TRAIN and not any(e.group_id == "S_LEAKED_GROUP" for e in corrupted):
            corrupted.append(
                Example(
                    id=ex.id,
                    text=ex.text,
                    label=ex.label,
                    language=ex.language,
                    source=ex.source,
                    group_id="S_LEAKED_GROUP",
                    parent_id=ex.parent_id,
                    golden_id=ex.golden_id,
                    split=SPLIT_TRAIN,
                    reviewed=ex.reviewed,
                )
            )
        else:
            corrupted.append(ex)

    bad_path = tmp_path / "corrupted_leakage.jsonl"
    dump_examples(corrupted, bad_path)

    ret = train_main(
        [
            "--dataset",
            str(bad_path),
            "--artifact",
            str(tmp_path / "dummy.joblib"),
            "--metadata-out",
            str(tmp_path / "dummy.json"),
            "--embeddings-cache",
            str(tmp_path / "cache.npz"),
        ]
    )
    # Must exit with code 1 due to DataLeakageError
    assert ret == 1


def test_train_determinism_two_runs(tmp_path: Path, synthetic_dataset_path: Path) -> None:
    cache_path = tmp_path / "cache.npz"

    meta1_path = tmp_path / "meta1.json"
    art1_path = tmp_path / "art1.joblib"
    ret1 = train_main(
        [
            "--dataset",
            str(synthetic_dataset_path),
            "--artifact",
            str(art1_path),
            "--metadata-out",
            str(meta1_path),
            "--embeddings-cache",
            str(cache_path),
            "--seed",
            "118",
            "--cv-folds",
            "2",
            "--variant",
            VARIANT_TFIDF_LOGREG,
        ]
    )
    assert ret1 == 0

    meta2_path = tmp_path / "meta2.json"
    art2_path = tmp_path / "art2.joblib"
    ret2 = train_main(
        [
            "--dataset",
            str(synthetic_dataset_path),
            "--artifact",
            str(art2_path),
            "--metadata-out",
            str(meta2_path),
            "--embeddings-cache",
            str(cache_path),
            "--seed",
            "118",
            "--cv-folds",
            "2",
            "--variant",
            VARIANT_TFIDF_LOGREG,
        ]
    )
    assert ret2 == 0

    m1 = json.loads(meta1_path.read_text(encoding="utf-8"))
    m2 = json.loads(meta2_path.read_text(encoding="utf-8"))
    assert m1["selected_variant"] == m2["selected_variant"]
    assert m1["variants"][0]["val_f1_macro"] == m2["variants"][0]["val_f1_macro"]
    assert m1["variants"][1]["val_f1_macro"] == m2["variants"][1]["val_f1_macro"]


def test_subcommand_dispatcher(tmp_path: Path, synthetic_dataset_path: Path) -> None:
    from intent.__main__ import main as dispatcher_main

    # Dispatcher help
    assert dispatcher_main(["--help"]) == 0
    # Dispatcher unknown
    assert dispatcher_main(["unknown_subcmd"]) == 2

