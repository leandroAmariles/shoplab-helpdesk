"""Group-aware, stratified, deterministic and stable train/val/test split (research R6).

* **Effective group**: the examples that share a ``group_id`` plus those with the same normalized text, joined
  transitively. A group is assigned whole to one split, so a paraphrase never sits in a different split than its origin
  and a duplicate never crosses splits (FR-014).
* **Strata**: (label, language). Each split aims at ``ratio`` of every stratum, and a group goes to the split that is
  furthest below its target on the strata the group contains.
* **Stability**: an example that already has a ``split`` keeps it, and the new examples of an assigned group inherit it,
  so adding examples later moves nothing (FR-015). The result does not depend on the input order.
"""

import random
from collections import Counter, defaultdict
from dataclasses import replace

from intent_dataset.constants import LABELS, LANGUAGES, SPLITS, DatasetRules
from intent_dataset.dataset import Example
from intent_dataset.errors import InvalidDatasetError
from intent_dataset.text import normalize_text


def effective_groups(examples: list[Example]) -> list[list[Example]]:
    """The groups of examples that must stay together, each sorted by id and the list sorted by its first id."""
    ordered = sorted(examples, key=lambda example: example.id)
    parent = list(range(len(ordered)))

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    def union(first: int, second: int) -> None:
        root_first, root_second = find(first), find(second)
        if root_first != root_second:
            parent[max(root_first, root_second)] = min(root_first, root_second)

    first_by_key: dict[tuple[str, str], int] = {}
    for index, example in enumerate(ordered):
        text_key = normalize_text(example.text) if isinstance(example.text, str) else repr(example.text)
        for key in (("group", example.group_id), ("text", text_key)):
            if key in first_by_key:
                union(first_by_key[key], index)
            else:
                first_by_key[key] = index
    members: dict[int, list[Example]] = defaultdict(list)
    for index, example in enumerate(ordered):
        members[find(index)].append(example)
    return [members[root] for root in sorted(members)]


def _targets(examples: list[Example], rules: DatasetRules) -> dict[str, Counter]:
    totals = Counter((example.label, example.language) for example in examples)
    return {
        split: Counter({stratum: max(1, round(total * ratio)) for stratum, total in totals.items()})
        for split, ratio in zip(SPLITS, rules.ratios, strict=True)
    }


def _pinned_split(group: list[Example]) -> str | None:
    splits = {example.split for example in group if example.split is not None}
    if len(splits) > 1:
        groups = sorted({example.group_id for example in group})
        raise InvalidDatasetError(
            f"El grupo {', '.join(groups)} ya tiene ejemplos en particiones distintas ({', '.join(sorted(splits))}): "
            "un grupo no puede repartirse entre particiones. Corrige los `split` a mano."
        )
    return next(iter(splits)) if splits else None


def _check_coverage(assigned: list[Example]) -> None:
    for split in SPLITS:
        members = [example for example in assigned if example.split == split]
        missing_labels = [label for label in LABELS if not any(e.label == label for e in members)]
        missing_languages = [language for language in LANGUAGES if not any(e.language == language for e in members)]
        if missing_labels or missing_languages:
            missing = [f"la clase {label}" for label in missing_labels]
            missing += [f"el idioma {language}" for language in missing_languages]
            raise InvalidDatasetError(
                f"La partición {split} se quedaría sin {', '.join(missing)}. Hacen falta más grupos distintos de esa "
                "clase o idioma antes de partir; no se ha cambiado nada."
            )


def assign_splits(examples: list[Example], rules: DatasetRules, *, seed: int | None = None) -> list[Example]:
    """The examples with ``split`` assigned to the ones that lacked it. Raises without returning a partial result."""
    groups = effective_groups(examples)
    targets = _targets(examples, rules)
    counts: dict[str, Counter] = {split: Counter() for split in SPLITS}
    assignment: dict[str, str] = {}
    pending: list[list[Example]] = []
    for group in groups:
        pinned = _pinned_split(group)
        if pinned is None:
            pending.append(group)
            continue
        for example in group:
            assignment[example.id] = pinned
            counts[pinned][(example.label, example.language)] += 1
    random_source = random.Random(rules.seed if seed is None else seed)
    random_source.shuffle(pending)
    pending.sort(key=len, reverse=True)
    for group in pending:
        in_group = Counter((example.label, example.language) for example in group)
        best = max(
            SPLITS,
            key=lambda split: (
                sum(
                    amount * (targets[split][stratum] - counts[split][stratum]) / targets[split][stratum]
                    for stratum, amount in in_group.items()
                ),
                -SPLITS.index(split),
            ),
        )
        for example in group:
            assignment[example.id] = best
        counts[best].update(in_group)
    assigned = [replace(example, split=assignment[example.id]) for example in examples]
    _check_coverage(assigned)
    return assigned
