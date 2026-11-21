"""Cohen's kappa, the 50-case sample, the blind sheet of annotator B and the agreement report."""

import csv
import json
from collections import Counter

import pytest
from intent_samples import make_example

from intent_dataset.agreement import (
    annotate_sheet,
    cohen_kappa,
    compute_agreement,
    read_annotator_sheet,
    read_sample,
    select_sample,
    write_annotator_sheet,
    write_report,
    write_sample,
)
from intent_dataset.constants import LABELS, LANGUAGES, DatasetRules
from intent_dataset.errors import InvalidDatasetError, ReviewError
from intent_dataset.paraphrase import LlmClient, LlmSettings

RULES = DatasetRules(agreement_sample_size=10)


def _pool(per_cell: int = 8):
    examples, number = [], 0
    for label in LABELS:
        for language in LANGUAGES:
            for k in range(per_cell):
                number += 1
                golden = k < 2
                examples.append(
                    make_example(
                        f"I{number:04d}",
                        text=f"{label} {language} ejemplo {k}",
                        label=label,
                        language=language,
                        source="golden" if golden else "manual_seed",
                        group_id=f"G{number:03d}" if golden else f"S{number:03d}",
                        golden_id=f"G{number:03d}" if golden else None,
                    )
                )
    return examples


# --- kappa -----------------------------------------------------------------------------------------------------------


def test_perfect_agreement_is_one():
    result = cohen_kappa(["a", "b", "c", "a"], ["a", "b", "c", "a"])
    assert result.kappa == 1.0 and result.observed == 1.0


def test_the_textbook_example_gives_point_four():
    first = ["yes"] * 25 + ["no"] * 25
    second = ["yes"] * 20 + ["no"] * 5 + ["yes"] * 10 + ["no"] * 15
    result = cohen_kappa(first, second)
    assert result.observed == pytest.approx(0.7)
    assert result.expected == pytest.approx(0.5)
    assert result.kappa == pytest.approx(0.4)


def test_chance_level_agreement_is_zero():
    assert cohen_kappa(["x", "x", "y", "y"], ["x", "y", "x", "y"]).kappa == pytest.approx(0.0)


def test_systematic_disagreement_is_negative():
    assert cohen_kappa(["x", "x", "y", "y"], ["y", "y", "x", "x"]).kappa == pytest.approx(-1.0)


def test_when_expected_agreement_is_one_kappa_is_undefined_not_a_division_by_zero():
    result = cohen_kappa(["x", "x"], ["x", "x"])
    assert result.kappa is None and "no definido" in result.note


def test_kappa_needs_two_equal_length_non_empty_lists():
    with pytest.raises(ValueError, match="longitud"):
        cohen_kappa(["a"], ["a", "b"])
    with pytest.raises(ValueError, match="vacía"):
        cohen_kappa([], [])


# --- sample ----------------------------------------------------------------------------------------------------------


def test_the_sample_has_the_requested_size_the_same_per_class_and_both_languages():
    chosen = select_sample(_pool(), RULES, seed=117)
    assert len(chosen) == 10
    assert Counter(e.label for e in chosen) == {label: 2 for label in LABELS}
    assert {e.language for e in chosen} == set(LANGUAGES)
    assert [e.id for e in chosen] == sorted(e.id for e in chosen)


def test_the_sample_is_deterministic_for_a_seed_and_differs_for_another():
    first = select_sample(_pool(), RULES, seed=117)
    assert [e.id for e in first] == [e.id for e in select_sample(_pool(), RULES, seed=117)]
    assert [e.id for e in first] != [e.id for e in select_sample(_pool(), RULES, seed=3)]


def test_non_golden_examples_are_preferred_when_there_is_a_choice():
    chosen = select_sample(_pool(), RULES, seed=117)
    assert all(e.source != "golden" for e in chosen)


def test_golden_examples_are_used_when_there_are_not_enough_others():
    pool = [e for e in _pool(per_cell=2)]  # only golden (k < 2)
    assert len(select_sample(pool, RULES, seed=1)) == 10


def test_include_puts_chosen_examples_first():
    pool = _pool()
    chosen = select_sample(pool, RULES, seed=117, include=["I0001"])
    assert "I0001" in {e.id for e in chosen}
    assert len(chosen) == 10


def test_a_class_that_cannot_fill_its_share_is_an_error():
    pool = [e for e in _pool() if e.label != "escalado"]
    with pytest.raises(InvalidDatasetError, match="escalado"):
        select_sample(pool, RULES, seed=1)


def test_the_sample_is_frozen_with_label_a_and_no_other_label(tmp_path):
    chosen = select_sample(_pool(), RULES, seed=117)
    path = tmp_path / "agreement-sample.jsonl"
    write_sample(path, chosen)
    rows = read_sample(path)
    assert [r["id"] for r in rows] == [e.id for e in chosen]
    assert all(r["label_b"] is None and r["final"] is None and r["reason"] is None for r in rows)
    assert {r["id"]: r["label_a"] for r in rows} == {e.id: e.label for e in chosen}


def test_an_existing_sample_is_not_overwritten_without_force(tmp_path):
    path = tmp_path / "agreement-sample.jsonl"
    write_sample(path, select_sample(_pool(), RULES, seed=117))
    with pytest.raises(ReviewError, match="--force"):
        write_sample(path, select_sample(_pool(), RULES, seed=3))
    write_sample(path, select_sample(_pool(), RULES, seed=3), force=True)


def test_the_sheet_of_annotator_b_has_no_labels_at_all(tmp_path):
    chosen = select_sample(_pool(), RULES, seed=117)
    sheet = tmp_path / "annotator-b.csv"
    write_annotator_sheet(sheet, chosen)
    with sheet.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    assert list(rows[0].keys()) == ["id", "text", "label"]  # no column can carry the label of annotator A
    assert all(row["label"] == "" for row in rows)
    assert [row["id"] for row in rows] == [e.id for e in chosen]
    assert read_annotator_sheet(sheet) == {e.id: "" for e in chosen}


# --- agreement report ------------------------------------------------------------------------------------------------


def _frozen(tmp_path):
    chosen = select_sample(_pool(), RULES, seed=117)
    path = tmp_path / "agreement-sample.jsonl"
    write_sample(path, chosen)
    return chosen, read_sample(path)


def test_agreement_fills_label_b_and_resolves_the_cases_that_agree(tmp_path):
    chosen, sample = _frozen(tmp_path)
    b = {e.id: e.label for e in chosen}
    b[chosen[0].id] = "escalado" if chosen[0].label != "escalado" else "charla"
    updated, report = compute_agreement(sample, b, kind="person", today="2026-11-30")
    by_id = {r["id"]: r for r in updated}
    assert by_id[chosen[0].id]["final"] is None and by_id[chosen[0].id]["label_b"] == b[chosen[0].id]
    assert by_id[chosen[1].id]["final"] == chosen[1].label
    assert report["n"] == 10 and report["disagreements"] == 1
    assert report["observed_agreement"] == pytest.approx(0.9)
    assert report["annotator_b"] == "person" and report["annotator_b_model"] is None
    assert report["computed_on"] == "2026-11-30"
    assert sum(sum(row.values()) for row in report["confusion"].values()) == 10
    assert all(count > 0 for row in report["confusion"].values() for count in row.values())


def test_agreement_with_a_model_records_which_one(tmp_path):
    chosen, sample = _frozen(tmp_path)
    _, report = compute_agreement(sample, {e.id: e.label for e in chosen}, kind="local_model", model="judge")
    assert (report["annotator_b"], report["annotator_b_model"]) == ("local_model", "judge")
    assert report["kappa"] == 1.0


def test_a_model_kind_without_a_model_name_or_an_unknown_kind_is_rejected(tmp_path):
    chosen, sample = _frozen(tmp_path)
    with pytest.raises(ReviewError, match="kind"):
        compute_agreement(sample, {e.id: e.label for e in chosen}, kind="robot")


def test_every_problem_in_the_sheet_of_b_is_listed(tmp_path):
    chosen, sample = _frozen(tmp_path)
    b = {e.id: e.label for e in chosen}
    del b[chosen[0].id]
    b[chosen[1].id] = ""
    b[chosen[2].id] = "otra"
    with pytest.raises(ReviewError) as error:
        compute_agreement(sample, b, kind="person")
    message = str(error.value)
    assert chosen[0].id in message and chosen[1].id in message and chosen[2].id in message


def test_the_report_is_written_as_json_and_read_back(tmp_path):
    chosen, sample = _frozen(tmp_path)
    _, report = compute_agreement(sample, {e.id: e.label for e in chosen}, kind="person", today="2026-11-30")
    path = tmp_path / "agreement.json"
    write_report(path, report)
    assert json.loads(path.read_text(encoding="utf-8")) == report
    assert path.read_text(encoding="utf-8").endswith("\n")


# --- annotator B as the local model ----------------------------------------------------------------------------------


class _Gateway:
    def __init__(self, answers):
        self.answers = list(answers)
        self.payloads = []

    def __call__(self, url, headers, payload):
        self.payloads.append(payload)
        return {"choices": [{"message": {"content": self.answers.pop(0)}}]}


def _client(gateway):
    return LlmClient(LlmSettings("http://gw/v1", "judge", "k"), post=gateway)


def test_the_model_annotates_with_the_guide_as_instruction_and_never_sees_label_a():
    gateway = _Gateway(["pedido", "politica."])
    rows = {"I0001": "¿dónde está mi pedido?", "I0002": "¿cuántos días tengo para devolver?"}
    labels, invalid = annotate_sheet(rows, "GUÍA DE PRUEBA", _client(gateway))
    assert labels == {"I0001": "pedido", "I0002": "politica"} and invalid == []
    system = gateway.payloads[0]["messages"][0]["content"]
    assert "GUÍA DE PRUEBA" in system
    assert all(label in system for label in LABELS)
    sent = json.dumps(gateway.payloads, ensure_ascii=False)
    assert "label_a" not in sent


def test_an_answer_that_is_not_one_label_is_left_empty_and_reported():
    gateway = _Gateway(["no sé", "pedido o producto", "ESCALADO"])
    rows = {"I0001": "a", "I0002": "b", "I0003": "c"}
    labels, invalid = annotate_sheet(rows, "guía", _client(gateway))
    assert labels == {"I0001": "", "I0002": "", "I0003": "escalado"}
    assert invalid == ["I0001", "I0002"]
