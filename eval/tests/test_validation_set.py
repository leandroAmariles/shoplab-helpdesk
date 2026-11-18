from pathlib import Path
from typing import Any

import pytest

from helpers import NO_ANSWER, make_case, valid_default_set, valid_knowledge_set, write_golden
from shoplab_eval.constants import GoldenRules
from shoplab_eval.corpus import load_corpus
from shoplab_eval.errors import InvalidGoldenError, Issue
from shoplab_eval.golden import parse_golden
from shoplab_eval.validation import validate_cases, validate_set_rules


def set_issues(tmp_path: Path, toy_kb: Path, cases: list[dict[str, Any]]) -> list[Issue]:
    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", cases))
    return validate_set_rules(parsed.cases, load_corpus(toy_kb), GoldenRules())


def codes(issues: list[Issue]) -> list[str]:
    return [issue.code for issue in issues]


def extra_answered(count: int, start: int) -> list[dict[str, Any]]:
    return [
        make_case(
            id=f"G{start + index:03d}",
            question=f"Pregunta adicional número {start + index:03d} sobre cuotas de otro tipo",
            expected_documents=["returns-01-a"],
            category="returns",
        )
        for index in range(count)
    ]


def test_the_minimal_valid_set_has_no_set_issues(tmp_path: Path, toy_kb: Path) -> None:
    assert set_issues(tmp_path, toy_kb, valid_default_set()) == []


@pytest.mark.parametrize(("size", "valid"), [(79, False), (80, True), (100, True), (101, False)])
def test_total_number_of_cases(tmp_path: Path, toy_kb: Path, size: int, valid: bool) -> None:
    base = valid_knowledge_set()
    cases = base[:size] if size <= len(base) else base + extra_answered(size - len(base), len(base) + 1)

    assert ("G-COUNT" in codes(set_issues(tmp_path, toy_kb, cases))) is not valid


def test_an_empty_file_reports_only_the_count(tmp_path: Path, toy_kb: Path) -> None:
    assert codes(set_issues(tmp_path, toy_kb, [])) == ["G-COUNT"]


def test_a_topic_with_fewer_than_twelve_answered_cases(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    index = next(i for i, case in enumerate(cases) if case["category"] == "warranty" and case["expected_documents"])
    cases[index] = {**cases[index], "expected_documents": [], "reference_answer": NO_ANSWER}

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-PER-TOPIC"]

    assert len(issues) == 1
    assert "warranty" in issues[0].message


def test_fewer_than_twelve_unanswered_cases(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    index = max(i for i, case in enumerate(cases) if case["category"] == "off_topic")
    cases[index] = make_case(
        id=cases[index]["id"], question=cases[index]["question"], category="returns", expected_documents=["returns-01-a"]
    )

    assert "G-UNANSWERED" in codes(set_issues(tmp_path, toy_kb, cases))


def test_unanswered_share_above_the_maximum(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    answered = [case for case in cases if case["expected_documents"]]
    unanswered = [case for case in cases if not case["expected_documents"]]

    # 30 answered + 12 unanswered: 28.6 %, above the 25 % maximum.
    issues = [
        issue for issue in set_issues(tmp_path, toy_kb, answered[:30] + unanswered) if issue.code == "G-UNANSWERED"
    ]

    assert len(issues) == 1
    assert "25" in issues[0].message


def test_share_below_the_minimum_with_enough_cases(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set() + extra_answered(20, 81)

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-UNANSWERED"]

    assert len(issues) == 1
    assert "15" in issues[0].message


def test_not_enough_unanswered_of_either_kind(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    # Turn two off-topic cases into topic ones: 8 topic, 4 off topic.
    off_topic = [i for i, case in enumerate(cases) if case["category"] == "off_topic"][:2]
    for index in off_topic:
        cases[index] = {**cases[index], "category": "payments"}

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-UNANSWERED-KINDS"]

    assert len(issues) == 1
    assert "off_topic" in issues[0].message


def test_not_enough_unanswered_of_the_topic(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    topic_unanswered = [
        i for i, case in enumerate(cases) if not case["expected_documents"] and case["category"] != "off_topic"
    ]
    for index in topic_unanswered[:2]:
        cases[index] = {**cases[index], "category": "off_topic"}

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-UNANSWERED-KINDS"]

    assert len(issues) == 1
    assert "unanswered_topic" in issues[0].message


@pytest.mark.parametrize(("multi", "valid"), [(7, False), (8, True)])
def test_cases_with_two_documents(tmp_path: Path, toy_kb: Path, multi: int, valid: bool) -> None:
    cases = valid_knowledge_set()
    multi_indexes = [i for i, case in enumerate(cases) if len(case["expected_documents"]) == 2]
    for index in multi_indexes[: 8 - multi]:
        cases[index] = {**cases[index], "expected_documents": ["payments-01-a"]}

    assert ("G-MULTI-DOC" in codes(set_issues(tmp_path, toy_kb, cases))) is not valid


def test_a_document_without_cases_is_listed_by_id(tmp_path: Path, toy_kb: Path) -> None:
    cases = [
        {**case, "expected_documents": ["returns-01-a"], "category": "returns"}
        if case["expected_documents"] == ["privacy-01-a"]
        else case
        for case in valid_knowledge_set()
    ]

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-COVERAGE"]

    assert len(issues) == 1
    assert "privacy-01-a" in issues[0].message


def test_two_questions_that_differ_only_in_case_spaces_or_punctuation(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()
    cases[1] = {**cases[1], "question": "  " + cases[0]["question"].upper() + "!!  "}

    issues = [issue for issue in set_issues(tmp_path, toy_kb, cases) if issue.code == "G-DUPLICATE"]

    assert len(issues) == 1
    assert "G001" in issues[0].message
    assert "G002" in issues[0].message


def test_all_failures_come_out_together_in_one_error_and_in_a_stable_order(tmp_path: Path, toy_kb: Path) -> None:
    cases = valid_knowledge_set()[:30]
    cases[0] = {**cases[0], "category": "pagos"}
    cases[1] = {**cases[1], "expected_documents": ["no-existe"]}
    path = write_golden(tmp_path / "golden.jsonl", cases)
    parsed = parse_golden(path)
    corpus = load_corpus(toy_kb)

    with pytest.raises(InvalidGoldenError) as first:
        raise InvalidGoldenError(validate_cases(parsed.cases, parsed.issues, corpus, GoldenRules()))
    with pytest.raises(InvalidGoldenError) as second:
        raise InvalidGoldenError(validate_cases(parsed.cases, parsed.issues, corpus, GoldenRules()))

    found = {issue.code for issue in first.value.issues}
    assert {"G-CATEGORY", "G-DOC-MISSING", "G-COUNT", "G-UNANSWERED", "G-COVERAGE"} <= found
    assert str(first.value) == str(second.value)
    lines = str(first.value).splitlines()
    assert lines[0].startswith("G-CATEGORY G001")
    assert lines[-1].split()[1] == "SET:"


def test_parse_issues_are_part_of_the_report(tmp_path: Path, toy_kb: Path) -> None:
    path = tmp_path / "golden.jsonl"
    path.write_text("{no es json}\n", encoding="utf-8")
    parsed = parse_golden(path)

    issues = validate_cases(parsed.cases, parsed.issues, load_corpus(toy_kb), GoldenRules())

    assert "G-JSON" in codes(issues)
    assert "G-COUNT" in codes(issues)
