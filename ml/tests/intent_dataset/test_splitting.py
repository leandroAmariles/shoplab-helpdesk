"""Group-aware, stratified, deterministic and stable split (research R6)."""

from collections import Counter, defaultdict
from dataclasses import replace

import pytest
from intent_samples import make_example

from intent_dataset.constants import LABELS, LANGUAGES, SPLITS, DatasetRules
from intent_dataset.errors import InvalidDatasetError
from intent_dataset.splitting import assign_splits, effective_groups
from intent_dataset.text import normalize_text


def _pool(groups_per_stratum: int = 14) -> list:
    """Examples without split: every (label, language) stratum has ``groups_per_stratum`` groups of 1 to 3 examples."""
    examples = []
    number = 0
    group_number = 0
    for label in LABELS:
        for language in LANGUAGES:
            for group in range(groups_per_stratum):
                group_number += 1
                for variant in range(1 + group % 3):
                    number += 1
                    examples.append(
                        make_example(
                            f"I{number:04d}",
                            text=f"{label} {language} grupo {group} variante {variant}",
                            label=label,
                            language=language,
                            group_id=f"S{group_number:03d}",
                            split=None,
                        )
                    )
    return examples


def _group_splits(examples):
    """Splits used by each effective group (must be exactly one)."""
    result = defaultdict(set)
    for members in effective_groups(examples):
        for member in members:
            result[min(m.id for m in members)].add(member.split)
    return result


def test_effective_groups_join_the_same_group_id_and_the_same_normalized_text():
    examples = [
        make_example("I0001", text="¿Dónde está mi pedido?", group_id="S001"),
        make_example("I0002", text="otra versión", group_id="S001"),
        make_example("I0003", text="donde esta mi pedido", group_id="S002"),
        make_example("I0004", text="algo distinto", group_id="S003"),
    ]
    groups = effective_groups(examples)
    assert sorted(sorted(m.id for m in group) for group in groups) == [["I0001", "I0002", "I0003"], ["I0004"]]


def test_effective_groups_chain_through_text_and_group_id():
    examples = [
        make_example("I0001", text="uno", group_id="S001"),
        make_example("I0002", text="uno", group_id="S002"),
        make_example("I0003", text="dos", group_id="S002"),
    ]
    assert [sorted(m.id for m in group) for group in effective_groups(examples)] == [["I0001", "I0002", "I0003"]]


def test_every_example_gets_a_split_and_no_group_is_divided():
    assigned = assign_splits(_pool(), DatasetRules())
    assert all(example.split in SPLITS for example in assigned)
    assert all(len(splits) == 1 for splits in _group_splits(assigned).values())


def test_no_normalized_text_is_in_two_splits():
    pool = _pool()
    pool.append(make_example("I9999", text=normalize_text(pool[0].text).upper(), label=pool[0].label,
                             language=pool[0].language, group_id="S777", split=None))  # fmt: skip
    assigned = assign_splits(pool, DatasetRules())
    splits_by_text = defaultdict(set)
    for example in assigned:
        splits_by_text[normalize_text(example.text)].add(example.split)
    assert all(len(splits) == 1 for splits in splits_by_text.values())


def test_proportions_are_close_to_the_ratios_overall_and_every_stratum_is_in_every_split():
    assigned = assign_splits(_pool(), DatasetRules())
    counts = Counter(example.split for example in assigned)
    for split, ratio in zip(SPLITS, DatasetRules().ratios, strict=True):
        assert abs(counts[split] / len(assigned) - ratio) < 0.07
    strata = Counter((e.split, e.label, e.language) for e in assigned)
    assert all(strata[(s, label, language)] >= 1 for s in SPLITS for label in LABELS for language in LANGUAGES)


def test_the_same_input_and_seed_give_the_same_result_and_another_seed_a_different_one():
    first = assign_splits(_pool(), DatasetRules())
    again = assign_splits(_pool(), DatasetRules())
    other = assign_splits(_pool(), replace(DatasetRules(), seed=999))
    assert [e.split for e in first] == [e.split for e in again]
    assert [e.split for e in first] != [e.split for e in other]


def test_the_input_order_does_not_change_the_result():
    pool = _pool()
    forward = {e.id: e.split for e in assign_splits(pool, DatasetRules())}
    backward = {e.id: e.split for e in assign_splits(list(reversed(pool)), DatasetRules())}
    assert forward == backward


def test_existing_assignments_never_move_when_examples_are_added():
    assigned = assign_splits(_pool(), DatasetRules())
    more = [
        make_example("I8001", text="mensaje nuevo uno", label="pedido", language="es", group_id="S901", split=None),
        make_example("I8002", text="mensaje nuevo dos", label="charla", language="pt", group_id="S902", split=None),
    ]
    grown = assign_splits([*assigned, *more], DatasetRules())
    before = {e.id: e.split for e in assigned}
    assert {e.id: e.split for e in grown if e.id in before} == before
    assert all(e.split in SPLITS for e in grown)


def test_a_new_example_of_an_assigned_group_inherits_its_split():
    assigned = assign_splits(_pool(), DatasetRules())
    member = assigned[0]
    newcomer = make_example("I8001", text="una paráfrasis nueva", label=member.label, language=member.language,
                            group_id=member.group_id, split=None)  # fmt: skip
    grown = assign_splits([*assigned, newcomer], DatasetRules())
    assert {e.id: e.split for e in grown}["I8001"] == member.split


def test_a_group_already_in_two_splits_is_an_error_naming_it():
    pool = _pool()
    assigned = assign_splits(pool, DatasetRules())
    first = assigned[0]
    twin = make_example("I8001", text="otro texto distinto", label=first.label, language=first.language,
                        group_id=first.group_id, split="test" if first.split != "test" else "val")  # fmt: skip
    with pytest.raises(InvalidDatasetError, match=first.group_id):
        assign_splits([*assigned, twin], DatasetRules())


def test_a_split_that_would_lack_a_class_is_an_error_and_returns_no_partial_result():
    pool = [e for e in _pool() if e.label != "charla"]
    one_group = [
        make_example(f"I{9000 + n}", text=f"charla única {n}", label="charla", language=language, group_id="S900",
                     split=None)
        for n, language in enumerate(["es", "pt", "es"])
    ]  # fmt: skip
    with pytest.raises(InvalidDatasetError, match="charla"):
        assign_splits([*pool, *one_group], DatasetRules())
    assert all(e.split is None for e in pool)


def test_a_stratum_with_a_single_example_does_not_break_the_assignment():
    pool = _pool()
    pool.append(make_example("I9500", text="único", label="pedido", language="pt", group_id="S950", split=None))
    assert all(e.split in SPLITS for e in assign_splits(pool, DatasetRules()))


def test_a_group_larger_than_the_target_of_a_split_is_still_assigned_whole():
    pool = _pool(groups_per_stratum=10)
    giant = [
        make_example(f"I{9100 + n}", text=f"gigante {n}", label="producto", language="es", group_id="S960", split=None)
        for n in range(40)
    ]
    assigned = assign_splits([*pool, *giant], DatasetRules())
    assert len({e.split for e in assigned if e.group_id == "S960"}) == 1
