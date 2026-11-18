"""The real eval/golden.jsonl against the real data/kb: this is what keeps the dataset honest when the corpus changes."""

import re
import shutil
from pathlib import Path

import pytest

from shoplab_eval import InvalidGoldenError, load_golden, validate_golden
from shoplab_eval.constants import DEFAULT_KB_DIR, GoldenRules
from shoplab_eval.corpus import load_corpus
from shoplab_eval.summary import format_summary

# A period or an amount written in words would be an invented fact in an answer that must admit it has no data.
PERIOD_WORDS = re.compile(r"\b(días?|semanas?|meses|mes|horas?|años?|reales)\b", re.IGNORECASE)


def test_the_real_dataset_is_valid() -> None:
    cases = load_golden()

    rules = GoldenRules()
    assert rules.min_cases <= len(cases) <= rules.max_cases


def test_every_document_of_the_corpus_is_expected_by_some_case() -> None:
    expected = {doc for case in load_golden() for doc in case.expected_documents}

    assert expected == set(load_corpus(DEFAULT_KB_DIR))


def test_validation_and_summary_are_deterministic() -> None:
    first = validate_golden()
    second = validate_golden()

    assert first == second
    assert format_summary(first) == format_summary(second)


def test_unanswered_reference_answers_do_not_state_periods_or_amounts_in_words() -> None:
    offenders = [
        f"{case.id}: {match.group(0)}"
        for case in load_golden()
        if not case.answerable
        for match in [PERIOD_WORDS.search(case.reference_answer)]
        if match
    ]

    assert offenders == []


def test_a_removed_document_is_reported_once_per_case_that_expects_it(tmp_path: Path) -> None:
    cases = load_golden()
    removed = "payments-03-boleto-and-vouchers"
    affected = sorted(case.id for case in cases if removed in case.expected_documents)
    assert affected, "the real dataset must have cases that expect this document"
    kb_copy = tmp_path / "kb"
    shutil.copytree(DEFAULT_KB_DIR, kb_copy)
    (kb_copy / f"{removed}.md").unlink()

    with pytest.raises(InvalidGoldenError) as error:
        load_golden(kb_dir=kb_copy)

    missing = sorted(issue.case_id for issue in error.value.issues if issue.code == "G-DOC-MISSING")
    assert missing == affected
    assert all(removed in issue.message for issue in error.value.issues if issue.code == "G-DOC-MISSING")
