"""Command-line training script and variant comparison orchestrator (LAB-118)."""

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from intent.artifacts import (
    current_environment_versions,
    extract_top_features_by_class,
    render_train_markdown,
    save_pipeline_artifact,
    write_train_metadata,
)
from intent.constants import (
    CV_FOLDS,
    DEFAULT_ARTIFACT_PATH,
    DEFAULT_DATASET_PATH,
    DEFAULT_EMBEDDINGS_CACHE_PATH,
    DEFAULT_METADATA_OUT_PATH,
    DEFAULT_SEED,
    SPLIT_TEST,
    SPLIT_TRAIN,
    SPLIT_VAL,
    VARIANT_AUTO,
    VARIANT_NAMES,
)
from intent.data import compute_file_sha256, load_intents_jsonl, validate_dataset_integrity
from intent.embeddings import build_deterministic_embeddings_cache
from intent.errors import (
    ClassifierConfigurationError,
    DataIntegrityError,
    DataLeakageError,
    EmbeddingUnavailableError,
)
from intent.models import compare_and_select_variants


def build_train_arg_parser() -> argparse.ArgumentParser:
    """Build the command-line argument parser for intent.train."""
    parser = argparse.ArgumentParser(
        prog="python -m intent.train",
        description="Entrena y compara variantes del clasificador de intención sobre train y val (LAB-118).",
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=DEFAULT_DATASET_PATH,
        help="Ruta al archivo JSONL del dataset (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--embeddings-cache",
        type=Path,
        default=DEFAULT_EMBEDDINGS_CACHE_PATH,
        help="Ruta al archivo .npz de caché de embeddings (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--artifact",
        type=Path,
        default=DEFAULT_ARTIFACT_PATH,
        help="Ruta de salida para el artefacto serializado .joblib (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--metadata-out",
        type=Path,
        default=DEFAULT_METADATA_OUT_PATH,
        help="Ruta de salida para los metadatos de entrenamiento en JSON (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=DEFAULT_SEED,
        help="Semilla de aleatoriedad para reproducibilidad estricta (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--cv-folds",
        type=int,
        default=CV_FOLDS,
        help="Número de pliegues para StratifiedGroupKFold (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--variant",
        choices=[VARIANT_AUTO, *VARIANT_NAMES],
        default=VARIANT_AUTO,
        help="Variante a entrenar o 'auto' para comparar y seleccionar la ganadora (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--allow-remote-embeddings",
        action="store_true",
        default=False,
        help="Permitir consultar el servicio gateway de embeddings si no se encuentran en la caché local.",
    )
    return parser


def run_training(
    *,
    dataset_path: Path,
    embeddings_cache_path: Path,
    artifact_path: Path,
    metadata_out_path: Path,
    seed: int = DEFAULT_SEED,
    cv_folds: int = CV_FOLDS,
    variant: str = VARIANT_AUTO,
    allow_remote_embeddings: bool = False,
) -> dict:
    """Orchestrate training, variant comparison, serialization, and metadata generation."""
    if not dataset_path.exists():
        raise FileNotFoundError(f"No se encontró el dataset en {dataset_path}.")

    examples = load_intents_jsonl(dataset_path)
    validate_dataset_integrity(examples)

    train_examples = [ex for ex in examples if ex.split == SPLIT_TRAIN]
    val_examples = [ex for ex in examples if ex.split == SPLIT_VAL]
    test_count = sum(1 for ex in examples if ex.split == SPLIT_TEST)

    if not train_examples or not val_examples:
        raise DataIntegrityError(
            f"El dataset debe contener ejemplos de 'train' y 'val'. Encontrados: "
            f"train={len(train_examples)}, val={len(val_examples)}."
        )

    # Ensure embeddings cache is available if needed
    if not embeddings_cache_path.exists():
        embeddings_cache_path.parent.mkdir(parents=True, exist_ok=True)
        build_deterministic_embeddings_cache(
            examples,
            embeddings_cache_path,
            seed=seed,
        )

    selection = compare_and_select_variants(
        train_examples,
        val_examples,
        embeddings_cache_path=embeddings_cache_path,
        seed=seed,
        cv_folds=cv_folds,
        forced_variant=variant,
        allow_remote_embeddings=allow_remote_embeddings,
    )

    artifact_sha256 = save_pipeline_artifact(selection.final_pipeline, artifact_path)
    top_features = extract_top_features_by_class(selection.final_pipeline)

    metadata = {
        "schema_version": "1.0",
        "seed": seed,
        "dataset_path": str(dataset_path).replace("\\", "/"),
        "dataset_sha256": compute_file_sha256(dataset_path),
        "split_counts": {
            "train": len(train_examples),
            "val": len(val_examples),
            "test": test_count,
        },
        "selected_variant": selection.selected_variant,
        "selection_reason": selection.selection_reason,
        "artifact_path": str(artifact_path).replace("\\", "/"),
        "artifact_sha256": artifact_sha256,
        "cv_folds": cv_folds,
        "variants": [entry.to_dict() for entry in selection.variants],
        "top_features_by_class": top_features,
        "environment": current_environment_versions(),
    }

    write_train_metadata(metadata, metadata_out_path)
    return metadata


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point for intent.train."""
    parser = build_train_arg_parser()
    try:
        args = parser.parse_args(argv)
        metadata = run_training(
            dataset_path=args.dataset,
            embeddings_cache_path=args.embeddings_cache,
            artifact_path=args.artifact,
            metadata_out_path=args.metadata_out,
            seed=args.seed,
            cv_folds=args.cv_folds,
            variant=args.variant,
            allow_remote_embeddings=args.allow_remote_embeddings,
        )
        print(render_train_markdown(metadata))
        return 0
    except (DataLeakageError, DataIntegrityError) as exc:
        print(f"Error de integridad/fuga de datos: {exc}", file=sys.stderr)
        return 1
    except (
        FileNotFoundError,
        ClassifierConfigurationError,
        EmbeddingUnavailableError,
        ValueError,
    ) as exc:
        print(f"Error de configuración o ejecución: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"Error inesperado en train: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
