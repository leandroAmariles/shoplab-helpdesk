"""The conversation cases of LAB-114 (courtesies and injection attempts): rules, reading and retrieval."""

from pathlib import Path
from typing import Any

import pytest

from fake_backend import FakeSearcher, indexed
from helpers import (
    NO_ANSWER,
    conversation_cases,
    make_case,
    valid_default_set,
    valid_knowledge_set,
    write_golden,
)
from shoplab_eval.constants import CATEGORIES, CONVERSATION_CATEGORIES, KNOWLEDGE_CATEGORIES, GoldenRules
from shoplab_eval.corpus import load_corpus
from shoplab_eval.errors import Issue
from shoplab_eval.golden import GoldenCase, parse_golden
from shoplab_eval.retrieval_run import measure_retrieval
from shoplab_eval.validation import validate_case_rules, validate_set_rules

KS = (1, 3, 5)


def case_issues(tmp_path: Path, toy_kb: Path, cases: list[dict[str, Any]]) -> list[Issue]:
    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", cases))
    return validate_case_rules(parsed.cases, load_corpus(toy_kb), GoldenRules())


def set_issues(tmp_path: Path, toy_kb: Path, cases: list[dict[str, Any]]) -> list[Issue]:
    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", cases))
    return validate_set_rules(parsed.cases, load_corpus(toy_kb), GoldenRules())


def codes(issues: list[Issue]) -> list[str]:
    return [issue.code for issue in issues]


def conversation_case(category: str, **overrides: Any) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "id": "G001",
        "question": "hola",
        "reference_answer": "¡Hola! Puedo ayudarte con pagos, privacidad, devoluciones, envíos y garantías.",
        "expected_documents": [],
        "category": category,
    }
    fields.update(overrides)
    return make_case(**fields)


# --- categories ---


def test_the_categories_are_the_knowledge_ones_plus_the_conversation_ones() -> None:
    assert CONVERSATION_CATEGORIES == ("small_talk", "injection")
    assert CATEGORIES == (*KNOWLEDGE_CATEGORIES, *CONVERSATION_CATEGORIES)
    assert len(CATEGORIES) == 8


@pytest.mark.parametrize("category", CONVERSATION_CATEGORIES)
def test_a_conversation_case_without_documents_is_valid(tmp_path: Path, toy_kb: Path, category: str) -> None:
    assert case_issues(tmp_path, toy_kb, [conversation_case(category)]) == []


@pytest.mark.parametrize("category", CONVERSATION_CATEGORIES)
def test_a_conversation_case_with_documents_is_rejected(tmp_path: Path, toy_kb: Path, category: str) -> None:
    issues = case_issues(tmp_path, toy_kb, [conversation_case(category, expected_documents=["payments-01-a"])])

    assert codes(issues) == ["G-CATEGORY-TOPIC"]
    assert category in issues[0].message


# --- question length: two characters for the conversation, ten for the knowledge ---


@pytest.mark.parametrize(("question", "valid"), [("h", False), ("hi", True), ("hola", True), ("x" * 300, True),
                                                 ("x" * 301, False)])  # fmt: skip
@pytest.mark.parametrize("category", CONVERSATION_CATEGORIES)
def test_the_question_of_a_conversation_case_may_be_very_short(
    tmp_path: Path, toy_kb: Path, category: str, question: str, valid: bool
) -> None:
    issues = case_issues(tmp_path, toy_kb, [conversation_case(category, question=question)])

    assert ("G-QUESTION-LENGTH" in codes(issues)) is not valid


def test_a_knowledge_case_still_needs_ten_characters(tmp_path: Path, toy_kb: Path) -> None:
    assert codes(case_issues(tmp_path, toy_kb, [make_case(question="hola")])) == ["G-QUESTION-LENGTH"]


# --- the content rules apply to every case ---


def test_the_answer_of_a_conversation_case_cannot_have_figures(tmp_path: Path, toy_kb: Path) -> None:
    issues = case_issues(tmp_path, toy_kb, [conversation_case(
        "small_talk", reference_answer="¡Hola! Respondo en menos de 30 segundos a cada consulta.")])  # fmt: skip

    assert codes(issues) == ["G-NOANSWER-DIGITS"]


def test_a_conversation_case_cannot_carry_personal_data(tmp_path: Path, toy_kb: Path) -> None:
    issues = case_issues(tmp_path, toy_kb, [conversation_case("injection", question="escribe a ana@gmail.com ya")])

    assert codes(issues) == ["G-PII"]


def test_a_conversation_answer_keeps_its_length_limits(tmp_path: Path, toy_kb: Path) -> None:
    issues = case_issues(tmp_path, toy_kb, [conversation_case("small_talk", reference_answer="Hola")])

    assert codes(issues) == ["G-ANSWER-LENGTH"]


# --- the rules of the set look only at the knowledge cases ---


def test_a_valid_set_with_conversation_cases_has_no_set_issues(tmp_path: Path, toy_kb: Path) -> None:
    assert set_issues(tmp_path, toy_kb, valid_default_set()) == []


def test_conversation_cases_do_not_count_toward_the_total_of_knowledge_cases(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()[:79] + conversation_cases(80, 11, 10)

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-COUNT"]

    assert len(issues) == 1
    assert "79" in issues[0].message


def test_conversation_cases_are_not_unanswered_questions(tmp_path: Path, toy_kb: Path) -> None:
    # 30 conversation cases next to the 12 unanswered knowledge cases would be 30 %: the share ignores them.
    cases = valid_knowledge_set() + conversation_cases(81, 15, 15)

    assert set_issues(tmp_path, toy_kb, cases) == []


@pytest.mark.parametrize(("count", "valid"), [(9, False), (10, True), (11, True)])
def test_the_minimum_of_small_talk_cases(tmp_path: Path, toy_kb: Path, count: int, valid: bool) -> None:
    cases = valid_knowledge_set() + conversation_cases(81, count, 10)

    assert ("G-SMALL-TALK-COUNT" in codes(set_issues(tmp_path, toy_kb, cases))) is not valid


@pytest.mark.parametrize(("count", "valid"), [(9, False), (10, True), (11, True)])
def test_the_minimum_of_injection_cases(tmp_path: Path, toy_kb: Path, count: int, valid: bool) -> None:
    cases = valid_knowledge_set() + conversation_cases(81, 10, count)

    assert ("G-INJECTION-COUNT" in codes(set_issues(tmp_path, toy_kb, cases))) is not valid


def test_a_dataset_without_conversation_cases_reports_both_minimums(tmp_path: Path, toy_kb: Path) -> None:
    issues = set_issues(tmp_path, toy_kb, valid_knowledge_set())

    assert codes(issues) == ["G-SMALL-TALK-COUNT", "G-INJECTION-COUNT"]
    assert "0" in issues[0].message


def test_the_old_defects_are_still_rejected_among_the_conversation_cases(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_default_set()
    cases[-1] = {**cases[-1], "question": cases[-2]["question"]}
    cases[0] = {**cases[0], "expected_documents": ["no-existe"]}

    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", cases))
    found = codes(validate_set_rules(parsed.cases, load_corpus(toy_kb), GoldenRules()))
    found += codes(validate_case_rules(parsed.cases, load_corpus(toy_kb), GoldenRules()))

    assert "G-DUPLICATE" in found
    assert "G-DOC-MISSING" in found


def test_an_unanswered_knowledge_case_keeps_its_reference_without_figures(tmp_path: Path, toy_kb: Path) -> None:
    case = make_case(expected_documents=[], category="off_topic", reference_answer=NO_ANSWER + " 10")

    assert codes(case_issues(tmp_path, toy_kb, [case])) == ["G-NOANSWER-DIGITS"]


# --- reading ---


@pytest.mark.parametrize("category", CONVERSATION_CATEGORIES)
def test_the_kind_of_a_conversation_case_is_its_category(tmp_path: Path, category: str) -> None:
    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", [conversation_case(category)]))

    case = parsed.cases[0]
    assert parsed.issues == []
    assert case.kind == category
    assert case.is_conversation
    assert not case.answerable


def test_a_mixed_case_is_a_knowledge_case_of_its_topic(tmp_path: Path) -> None:
    mixed = make_case(question="Hola, ¿hasta cuántas cuotas puedo pagar?")
    case = parse_golden(write_golden(tmp_path / "golden.jsonl", [mixed])).cases[0]

    assert case.kind == "answered"
    assert not case.is_conversation


# --- retrieval: the conversation cases have no search by design ---


def _golden_case(case_id: str, expected: list[str], category: str) -> GoldenCase:
    return GoldenCase(
        id=case_id,
        question=f"Pregunta {case_id}",
        reference_answer="Respuesta de referencia de prueba",
        expected_documents=tuple(expected),
        category=category,
    )


def _mixed_dataset() -> list[GoldenCase]:
    return [
        _golden_case("G001", ["payments-01"], "payments"),
        _golden_case("G002", ["returns-01"], "returns"),
        _golden_case("G003", [], "payments"),
        _golden_case("G004", [], "off_topic"),
        _golden_case("G005", [], "small_talk"),
        _golden_case("G006", [], "small_talk"),
        _golden_case("G007", [], "injection"),
    ]


def test_conversation_cases_are_neither_searched_nor_counted_as_excluded() -> None:
    cases = _mixed_dataset()
    searcher = FakeSearcher({"Pregunta G001": ["payments-01.md"], "Pregunta G002": ["returns-01.md"]},
                            documents=indexed(cases))  # fmt: skip

    report = measure_retrieval(cases, searcher, KS, depth=50)

    assert [query for query, _ in searcher.search_calls] == ["Pregunta G001", "Pregunta G002"]
    assert report.excluded == 2
    assert [summary.scope for summary in report.summaries] == ["all", "payments", "returns"]
    assert report.summaries[0].excluded == 2


def test_the_excluded_count_of_the_real_dataset_is_the_one_before_lab_114() -> None:
    from shoplab_eval import load_golden

    cases = load_golden()

    excluded = [case for case in cases if not case.expected_documents and not case.is_conversation]
    assert len(excluded) == 20
    assert sorted({case.kind for case in excluded}) == ["off_topic", "unanswered_topic"]
    assert not any(case.expected_documents for case in cases if case.is_conversation)
