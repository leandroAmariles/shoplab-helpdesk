from pathlib import Path
from typing import Any

import pytest

from helpers import make_case, write_golden
from shoplab_eval.constants import GoldenRules
from shoplab_eval.corpus import load_corpus
from shoplab_eval.errors import Issue
from shoplab_eval.golden import parse_golden
from shoplab_eval.validation import validate_case_rules


def run(tmp_path: Path, toy_kb: Path, cases: list[dict[str, Any]]) -> list[Issue]:
    parsed = parse_golden(write_golden(tmp_path / "golden.jsonl", cases))
    return validate_case_rules(parsed.cases, load_corpus(toy_kb), GoldenRules())


def codes(issues: list[Issue]) -> list[str]:
    return [issue.code for issue in issues]


def test_a_valid_case_has_no_issues(tmp_path: Path, toy_kb: Path) -> None:
    assert run(tmp_path, toy_kb, [make_case()]) == []


@pytest.mark.parametrize("bad_id", ["g001", "G01", "G0001", "001", "X001", " G001"])
def test_id_must_be_g_plus_three_digits(tmp_path: Path, toy_kb: Path, bad_id: str) -> None:
    assert codes(run(tmp_path, toy_kb, [make_case(id=bad_id)])) == ["G-ID"]


def test_repeated_id_is_reported_on_the_second_case(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(), make_case(question="¿Cuál es el plazo de un reembolso completo?")])

    assert codes(issues) == ["G-ID"]
    assert issues[0].line == 2
    assert "G001" in issues[0].message


@pytest.mark.parametrize(("length", "valid"), [(9, False), (10, True), (300, True), (301, False)])
def test_question_length_boundaries(tmp_path: Path, toy_kb: Path, length: int, valid: bool) -> None:
    question = "¿" + "x" * (length - 2) + "?"
    issues = run(tmp_path, toy_kb, [make_case(question=question)])

    assert ("G-QUESTION-LENGTH" in codes(issues)) is not valid


def test_question_length_ignores_surrounding_spaces(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(question="   ¿Y esto?   ")])

    assert codes(issues) == ["G-QUESTION-LENGTH"]


@pytest.mark.parametrize(("length", "valid"), [(19, False), (20, True), (800, True), (801, False)])
def test_answer_length_boundaries(tmp_path: Path, toy_kb: Path, length: int, valid: bool) -> None:
    issues = run(tmp_path, toy_kb, [make_case(reference_answer="a" * length)])

    assert ("G-ANSWER-LENGTH" in codes(issues)) is not valid


def test_category_must_be_one_of_the_six(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(category="pagos")])

    assert codes(issues) == ["G-CATEGORY"]


def test_three_documents_or_a_repeated_one_break_the_document_count(tmp_path: Path, toy_kb: Path) -> None:
    three = make_case(id="G001", expected_documents=["payments-01-a", "payments-02-b", "returns-01-a"])
    repeated = make_case(
        id="G002",
        question="¿Se puede pagar con boleto bancario?",
        expected_documents=["payments-01-a", "payments-01-a"],
    )

    issues = run(tmp_path, toy_kb, [three, repeated])

    assert [(issue.code, issue.case_id) for issue in issues] == [("G-DOC-COUNT", "G001"), ("G-DOC-COUNT", "G002")]


def test_missing_document_names_the_document_and_the_case(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(expected_documents=["payments-99-no-existe"])])

    assert [(issue.code, issue.case_id) for issue in issues] == [("G-DOC-MISSING", "G001")]
    assert "payments-99-no-existe" in issues[0].message


def test_each_missing_document_is_reported(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(expected_documents=["no-uno", "no-dos"])])

    assert codes(issues) == ["G-DOC-MISSING", "G-DOC-MISSING"]


def test_category_must_be_the_topic_of_an_expected_document(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(category="returns")])

    assert codes(issues) == ["G-CATEGORY-TOPIC"]


def test_two_documents_of_different_topics_pass_if_the_category_matches_one(tmp_path: Path, toy_kb: Path) -> None:
    both = ["payments-01-a", "returns-01-a"]
    first = make_case(id="G001", expected_documents=both, category="payments")
    second = make_case(
        id="G002", question="¿Y si devuelvo algo que pagué en cuotas?", expected_documents=both, category="returns"
    )
    third = make_case(
        id="G003", question="¿Qué pasa con un cambio de pedido enviado?", expected_documents=both, category="shipping"
    )

    issues = run(tmp_path, toy_kb, [first, second, third])

    assert [(issue.code, issue.case_id) for issue in issues] == [("G-CATEGORY-TOPIC", "G003")]


def test_off_topic_with_documents_is_rejected(tmp_path: Path, toy_kb: Path) -> None:
    issues = run(tmp_path, toy_kb, [make_case(category="off_topic")])

    assert codes(issues) == ["G-CATEGORY-TOPIC"]


def test_off_topic_without_documents_is_valid(tmp_path: Path, toy_kb: Path) -> None:
    case = make_case(
        question="¿Me recomiendas una receta de pasta?",
        reference_answer="Solo puedo ayudarte con consultas sobre la tienda.",
        expected_documents=[],
        category="off_topic",
    )

    assert run(tmp_path, toy_kb, [case]) == []


def test_digits_in_an_unanswered_reference_answer_are_rejected(tmp_path: Path, toy_kb: Path) -> None:
    unanswered = make_case(
        expected_documents=[],
        reference_answer="No tengo información, pero suele tardar 7 días en responder.",
    )

    assert codes(run(tmp_path, toy_kb, [unanswered])) == ["G-NOANSWER-DIGITS"]


def test_digits_in_an_answered_reference_answer_are_fine(tmp_path: Path, toy_kb: Path) -> None:
    answered = make_case(reference_answer="Puedes dividir la compra en hasta 12 cuotas sin interés con tarjeta.")

    assert run(tmp_path, toy_kb, [answered]) == []


def test_a_sentence_copied_from_a_document_is_rejected_whatever_the_case_and_punctuation(
    tmp_path: Path, toy_kb: Path
) -> None:
    copied = make_case(question="¡PUEDES PAGAR con tarjeta de crédito, en hasta 12 cuotas sin interés!")

    assert codes(run(tmp_path, toy_kb, [copied])) == ["G-QUESTION-VERBATIM"]


def test_a_question_that_is_only_part_of_a_word_is_not_verbatim(tmp_path: Path, toy_kb: Path) -> None:
    # "pagar con tarjet" is inside "pagar con tarjeta" of the document, but "tarjet" is not a whole word there.
    assert run(tmp_path, toy_kb, [make_case(question="¿Pagar con tarjet?")]) == []


def test_a_question_in_other_words_is_not_verbatim(tmp_path: Path, toy_kb: Path) -> None:
    assert run(tmp_path, toy_kb, [make_case()]) == []


@pytest.mark.parametrize(
    ("text", "flagged"),
    [
        ("Escríbeme a persona@gmail.com y te respondo", True),
        ("Escríbeme a atencion@shoplab.example y te respondo", False),
        ("Mi documento es 12345678 por si hace falta", True),
        ("Mi documento es 1234567 por si hace falta", False),
    ],
)
def test_personal_data_is_rejected_in_question_and_answer(
    tmp_path: Path, toy_kb: Path, text: str, flagged: bool
) -> None:
    in_question = run(tmp_path, toy_kb, [make_case(question=f"¿Puedo cambiar un pedido? {text}")])
    in_answer = run(tmp_path, toy_kb, [make_case(reference_answer=f"Puedes dividir la compra en cuotas. {text}")])

    assert ("G-PII" in codes(in_question)) is flagged
    assert ("G-PII" in codes(in_answer)) is flagged


def test_several_errors_in_one_case_are_reported_together(tmp_path: Path, toy_kb: Path) -> None:
    broken = make_case(
        id="bad",
        question="corta",
        reference_answer="corta",
        category="pagos",
        expected_documents=["no-existe"],
    )

    assert sorted(codes(run(tmp_path, toy_kb, [broken]))) == [
        "G-ANSWER-LENGTH",
        "G-CATEGORY",
        "G-DOC-MISSING",
        "G-ID",
        "G-QUESTION-LENGTH",
    ]


def test_a_broken_case_does_not_stop_the_others_from_being_checked(tmp_path: Path, toy_kb: Path) -> None:
    broken_structure = make_case(id="G001", expected_documents="no-es-lista")
    wrong_category = make_case(id="G002", question="¿Cómo se paga con boleto bancario?", category="returns")
    path = write_golden(tmp_path / "golden.jsonl", [broken_structure, wrong_category])

    parsed = parse_golden(path)
    issues = validate_case_rules(parsed.cases, load_corpus(toy_kb), GoldenRules())

    assert [issue.code for issue in parsed.issues] == ["G-FIELDS"]
    assert [(issue.code, issue.case_id) for issue in issues] == [("G-CATEGORY-TOPIC", "G002")]
