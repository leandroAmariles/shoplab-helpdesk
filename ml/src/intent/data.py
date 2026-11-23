"""Dataset loading, SHA-256 hashing and anti-leakage checks for the intent classifier (LAB-118)."""

import hashlib
import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from intent.constants import CANONICAL_LABELS, DEFAULT_REVIEW_PATH, SPLITS
from intent.errors import ClassifierConfigurationError, DataLeakageError
from intent_dataset.dataset import Example, load_examples
from intent_dataset.errors import InvalidDatasetError
from intent_dataset.splitting import effective_groups


@dataclass(frozen=True)
class SplitDataset:
    """Validated dataset separated into train, val and test splits."""

    dataset_path: Path
    dataset_sha256: str
    human_reviewed: bool
    train: tuple[Example, ...]
    val: tuple[Example, ...]
    test: tuple[Example, ...]


def compute_file_sha256(path: Path) -> str:
    """Return the lowercase hex SHA-256 digest of a file."""
    if not path.exists() or not path.is_file():
        raise ClassifierConfigurationError(f"No se encontró el archivo requerido: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_dataset_human_reviewed(review_path: Path | None = DEFAULT_REVIEW_PATH) -> bool:
    """Return True only when REVIEW.md exists and is no longer marked as BORRADOR / pendiente."""
    if review_path is None or not review_path.exists():
        return False
    text = review_path.read_text(encoding="utf-8", errors="ignore")
    if re.search(r"\*\*Estado\*\*:\s*\*\*BORRADOR\*\*", text, flags=re.IGNORECASE):
        return False
    return bool(re.search(r"\*\*Estado\*\*:\s*\*\*(COMPLETADO|APROBADO|VALIDADO)\*\*", text, flags=re.IGNORECASE))


def assert_no_test_examples(examples: Sequence[Example]) -> None:
    """Raise DataLeakageError if any example in the sequence belongs to the test split."""
    offending = [ex.id for ex in examples if ex.split == "test"]
    if offending:
        sample = ", ".join(offending[:5])
        raise DataLeakageError(
            f"Se detectaron {len(offending)} ejemplos de la partición 'test' ({sample}) en una operación de "
            "entrenamiento o validación. La partición 'test' solo puede leerse en evaluate.py."
        )


def _verify_split_integrity(examples: Sequence[Example]) -> None:
    if not examples:
        raise ClassifierConfigurationError("El dataset está vacío; ejecuta primero el flujo de LAB-117.")

    group_splits: dict[str, set[str]] = {}
    for ex in examples:
        if ex.label not in CANONICAL_LABELS:
            raise DataLeakageError(f"Etiqueta inválida {ex.label!r} en el ejemplo {ex.id}.")
        if ex.split not in SPLITS:
            raise DataLeakageError(f"Partición inválida {ex.split!r} en el ejemplo {ex.id}.")
        group_splits.setdefault(ex.group_id, set()).add(ex.split)

    leaked_groups = [gid for gid, splits in group_splits.items() if len(splits) > 1]
    if leaked_groups:
        raise DataLeakageError(
            f"Fuga entre particiones por group_id: {leaked_groups[:5]} aparecen en más de una partición."
        )

    eff_groups = effective_groups(list(examples))
    leaked_effective = [
        group[0].id
        for group in eff_groups
        if len({member.split for member in group}) > 1
    ]
    if leaked_effective:
        raise DataLeakageError(
            "Fuga entre particiones por texto normalizado o grupo efectivo: "
            f"{leaked_effective[:5]} cruzan particiones."
        )


def validate_dataset_integrity(examples: Sequence[Example]) -> None:
    """Validate that labels, splits and group/text disjointness satisfy integrity invariants."""
    _verify_split_integrity(examples)


def load_intents_jsonl(dataset_path: Path) -> list[Example]:
    """Load examples from intents.jsonl using the intent_dataset schema."""
    if not dataset_path.exists():
        raise ClassifierConfigurationError(
            f"No se encontró el dataset en {dataset_path}. Verifica la ruta o ejecuta LAB-117."
        )
    try:
        return list(load_examples(dataset_path))
    except InvalidDatasetError as exc:
        raise ClassifierConfigurationError(f"Dataset inválido en {dataset_path}: {exc}") from exc


def load_split_dataset(
    dataset_path: Path,
    *,
    review_path: Path | None = DEFAULT_REVIEW_PATH,
) -> SplitDataset:
    """Load intents.jsonl, verify zero leakage across splits, and separate train/val/test."""
    if not dataset_path.exists():
        raise ClassifierConfigurationError(
            f"No se encontró el dataset en {dataset_path}. Verifica la ruta o ejecuta LAB-117."
        )
    try:
        examples = load_examples(dataset_path)
    except InvalidDatasetError as exc:
        raise ClassifierConfigurationError(f"Dataset inválido en {dataset_path}: {exc}") from exc

    _verify_split_integrity(examples)

    train = tuple(ex for ex in examples if ex.split == "train")
    val = tuple(ex for ex in examples if ex.split == "val")
    test = tuple(ex for ex in examples if ex.split == "test")

    if not train or not val or not test:
        raise DataLeakageError(
            f"Las tres particiones deben tener ejemplos (train={len(train)}, val={len(val)}, test={len(test)})."
        )

    return SplitDataset(
        dataset_path=dataset_path,
        dataset_sha256=compute_file_sha256(dataset_path),
        human_reviewed=is_dataset_human_reviewed(review_path),
        train=train,
        val=val,
        test=test,
    )
