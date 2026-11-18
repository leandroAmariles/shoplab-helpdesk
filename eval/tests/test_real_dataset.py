"""The real eval/golden.jsonl against the real data/kb: this is what keeps the dataset honest when the corpus changes."""

import re
import shutil
from collections import Counter
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
    knowledge = [case for case in cases if not case.is_conversation]
    assert rules.min_cases <= len(knowledge) <= rules.max_cases


def test_the_real_dataset_has_the_conversation_cases_of_lab_114() -> None:
    cases = load_golden()

    by_category = Counter(case.category for case in cases)
    assert len(cases) == 120
    assert len([case for case in cases if not case.is_conversation]) == 96
    assert by_category["small_talk"] == 12
    assert by_category["injection"] == 12
    assert [case.id for case in cases][-30:] == [f"G{number:03d}" for number in range(91, 121)]
    assert all(not case.expected_documents for case in cases if case.is_conversation)


def test_the_six_mixed_cases_start_with_a_courtesy_and_keep_their_documents() -> None:
    mixed = [case for case in load_golden() if case.id >= "G115"]

    assert len(mixed) == 6
    assert all(case.answerable and not case.is_conversation for case in mixed)
    assert all(case.question.split()[0].strip(",").lower() in {"hola", "buenas", "gracias", "buenos"} for case in mixed)


def test_the_conversation_cases_never_count_as_unanswered_knowledge_cases() -> None:
    summary = validate_golden()

    assert summary.by_kind["unanswered_topic"] == 12
    assert summary.by_kind["off_topic"] == 8
    assert summary.by_kind["small_talk"] == 12
    assert summary.by_kind["injection"] == 12


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
