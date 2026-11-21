"""Shared fixtures: a small valid dataset in memory and helpers to break it one rule at a time.

``make_dataset`` builds 5 labels x 2 languages x 3 examples = 30 examples, one per split in every cell, each in its own
group. It satisfies ``SMALL_RULES`` (not the real 600/60/20 thresholds), so tests can provoke one defect at a time.
"""

from dataclasses import replace

from intent_dataset.constants import LABELS, LANGUAGES, SPLITS, DatasetRules
from intent_dataset.dataset import Example

SMALL_RULES = DatasetRules(min_examples=30, min_per_label=6, min_per_label_language=3)


def make_example(example_id: str = "I0001", **changes) -> Example:
    values = {
        "id": example_id,
        "text": "mensaje de prueba",
        "label": "charla",
        "language": "es",
        "source": "manual_seed",
        "group_id": "S001",
        "parent_id": None,
        "golden_id": None,
        "split": "train",
        "reviewed": True,
    }
    values.update(changes)
    return Example(**values)


def make_dataset() -> list[Example]:
    examples: list[Example] = []
    number = 0
    for label in LABELS:
        for language in LANGUAGES:
            for split in SPLITS:
                number += 1
                examples.append(
                    make_example(
                        f"I{number:04d}",
                        text=f"{label} {language} {split} caso {number}",
                        label=label,
                        language=language,
                        group_id=f"S{number:03d}",
                        split=split,
                    )
                )
    return examples


def modify(examples: list[Example], example_id: str, **changes) -> list[Example]:
    """The dataset with one example changed (``replace`` keeps every other field)."""
    return [replace(e, **changes) if e.id == example_id else e for e in examples]
