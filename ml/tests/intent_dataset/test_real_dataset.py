"""Control over the real files of ml/data/intents (FR-019, FR-023): every PR that touches ml/** runs it.

It needs no network, database or model. It cannot tell whether a label is the right one or whether a personal name
slipped through (only a person reading the examples can); it checks everything a program can, and that the data sheet
and the review log say who did what.
"""

import json
from collections import Counter

import pytest

from intent_dataset.agreement import read_sample
from intent_dataset.constants import (
    AGREEMENT_PATH,
    DATASET_PATH,
    DATASHEET_PATH,
    GOLDEN_PATH,
    GUIDE_PATH,
    INTENTS_DIR,
    LABELS,
    LANGUAGES,
    SAMPLE_PATH,
    SPLITS,
    DatasetRules,
)
from intent_dataset.dataset import load_examples
from intent_dataset.validation import validate_dataset


@pytest.fixture(scope="module")
def examples():
    return load_examples(DATASET_PATH)


@pytest.fixture(scope="module")
def report():
    return json.loads(AGREEMENT_PATH.read_text(encoding="utf-8"))


def test_the_published_dataset_passes_every_rule(examples, report):
    golden_ids = frozenset(
        json.loads(line)["id"] for line in GOLDEN_PATH.read_text(encoding="utf-8").splitlines() if line.strip()
    )
    issues = validate_dataset(
        examples,
        DatasetRules(),
        golden_ids=golden_ids,
        sample=read_sample(SAMPLE_PATH),
        report=report,
        datasheet=DATASHEET_PATH.read_text(encoding="utf-8"),
        guide=GUIDE_PATH.read_text(encoding="utf-8"),
    )
    assert [issue.format() for issue in issues] == []


def test_the_dataset_meets_the_thresholds_of_the_spec(examples):
    assert len(examples) >= 600
    cells = Counter((example.label, example.language) for example in examples)
    assert all(cells[(label, language)] >= 20 for label in LABELS for language in LANGUAGES)
    in_split = Counter((example.split, example.label, example.language) for example in examples)
    assert all(in_split[(split, label, language)] >= 1 for split in SPLITS for label in LABELS for language in LANGUAGES)
    assert {example.source for example in examples} == {"golden", "olist_review", "manual_seed", "paraphrase"}


def test_the_agreement_report_states_which_kind_of_annotator_b_it_is(report):
    assert report["annotator_b"] in ("person", "local_model")
    sheet = DATASHEET_PATH.read_text(encoding="utf-8")
    if report["annotator_b"] == "local_model":
        assert "no es un acuerdo entre personas" in sheet


def test_the_review_log_exists_and_names_who_reviewed():
    log = (INTENTS_DIR / "REVIEW.md").read_text(encoding="utf-8")
    assert "Revisor" in log and "Persona revisora" in log
