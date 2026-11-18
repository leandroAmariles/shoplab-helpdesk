"""The rules of the golden dataset: one function per level, all of them collect every failure instead of stopping."""

import re
from collections import defaultdict

from shoplab_eval.constants import CATEGORIES, OFF_TOPIC, TOPICS, GoldenRules
from shoplab_eval.corpus import CorpusDocument, normalize_text
from shoplab_eval.errors import Issue, sort_issues
from shoplab_eval.golden import GoldenCase

SET = "SET"
ALLOWED_EMAIL_SUFFIX = "@shoplab.example"

_ID = re.compile(r"G\d{3}")
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_LONG_DIGITS = re.compile(r"\d{8,}")


def validate_cases(
    cases: list[GoldenCase],
    parse_issues: list[Issue],
    corpus: dict[str, CorpusDocument],
    rules: GoldenRules = GoldenRules(),  # noqa: B008 - frozen dataclass, safe as a default
) -> list[Issue]:
    """Every failure of the dataset: those found while reading, those of each case and those of the whole set."""
    issues = [*parse_issues, *validate_case_rules(cases, corpus, rules), *validate_set_rules(cases, corpus, rules)]
    return sort_issues(issues)


def validate_case_rules(
    cases: list[GoldenCase],
    corpus: dict[str, CorpusDocument],
    rules: GoldenRules = GoldenRules(),  # noqa: B008
) -> list[Issue]:
    """Rules that look at one case at a time (and, for ``G-ID``, at the ids already seen)."""
    issues: list[Issue] = []
    first_line_of_id: dict[str, int] = {}
    for case in cases:
        issues.extend(_id_issues(case, first_line_of_id))
        issues.extend(_text_issues(case, rules))
        issues.extend(_category_issues(case))
        issues.extend(_document_issues(case, corpus, rules))
        issues.extend(_content_issues(case, corpus))
    return issues


def validate_set_rules(
    cases: list[GoldenCase],
    corpus: dict[str, CorpusDocument],
    rules: GoldenRules = GoldenRules(),  # noqa: B008
) -> list[Issue]:
    """Rules about the dataset as a whole: counts, balance, coverage of the corpus and duplicated questions."""
    total = len(cases)
    low, high = rules.min_cases, rules.max_cases
    issues: list[Issue] = []
    if not low <= total <= high:
        issues.append(Issue("G-COUNT", SET, f"hay {total} casos y debe haber entre {low} y {high}"))
    if total == 0:
        # Nothing to balance or cover: the other rules would only repeat the same problem.
        return issues
    issues.extend(_per_topic_issues(cases, rules))
    issues.extend(_unanswered_issues(cases, rules))
    multi = sum(1 for case in cases if case.is_multi_document)
    if multi < rules.min_multi_document:
        message = f"hay {multi} casos con dos documentos esperados y se necesitan al menos {rules.min_multi_document}"
        issues.append(Issue("G-MULTI-DOC", SET, message))
    expected = {doc for case in cases for doc in case.expected_documents}
    uncovered = sorted(set(corpus) - expected)
    if uncovered:
        issues.append(Issue("G-COVERAGE", SET, "ningún caso espera estos documentos: " + ", ".join(uncovered)))
    issues.extend(_duplicate_issues(cases))
    return issues


def _id_issues(case: GoldenCase, first_line_of_id: dict[str, int]) -> list[Issue]:
    issues: list[Issue] = []
    if not _ID.fullmatch(case.id):
        message = f"el id '{case.id}' no cumple el formato: 'G' seguido de tres dígitos (G001)"
        issues.append(Issue("G-ID", case.id, message, case.line))
    if case.id in first_line_of_id:
        message = f"el id {case.id} ya lo usa el caso de la línea {first_line_of_id[case.id]}"
        issues.append(Issue("G-ID", case.id, message, case.line))
    else:
        first_line_of_id[case.id] = case.line
    return issues


def _text_issues(case: GoldenCase, rules: GoldenRules) -> list[Issue]:
    issues: list[Issue] = []
    question_low, question_high = rules.question_length
    length = len(case.question.strip())
    if not question_low <= length <= question_high:
        message = f"la pregunta tiene {length} caracteres y debe tener entre {question_low} y {question_high}"
        issues.append(Issue("G-QUESTION-LENGTH", case.id, message, case.line))
    answer_low, answer_high = rules.answer_length
    length = len(case.reference_answer.strip())
    if not answer_low <= length <= answer_high:
        message = f"la respuesta de referencia tiene {length} caracteres y debe tener entre {answer_low} y {answer_high}"
        issues.append(Issue("G-ANSWER-LENGTH", case.id, message, case.line))
    return issues


def _category_issues(case: GoldenCase) -> list[Issue]:
    if case.category in CATEGORIES:
        return []
    message = f"la categoría '{case.category}' no es válida; usa una de: " + ", ".join(CATEGORIES)
    return [Issue("G-CATEGORY", case.id, message, case.line)]


def _document_issues(case: GoldenCase, corpus: dict[str, CorpusDocument], rules: GoldenRules) -> list[Issue]:
    issues: list[Issue] = []
    documents = case.expected_documents
    if len(documents) > rules.max_expected_documents or len(set(documents)) != len(documents):
        message = (
            f"hay {len(documents)} documentos esperados ({', '.join(documents)}): como máximo "
            f"{rules.max_expected_documents} y sin repetir"
        )
        issues.append(Issue("G-DOC-COUNT", case.id, message, case.line))
    existing = [doc for doc in documents if doc in corpus]
    for doc in documents:
        if doc not in corpus:
            message = f"el documento esperado '{doc}' no existe en el corpus"
            issues.append(Issue("G-DOC-MISSING", case.id, message, case.line))
    if case.category == OFF_TOPIC and documents:
        message = "una pregunta 'off_topic' no puede tener documentos esperados"
        issues.append(Issue("G-CATEGORY-TOPIC", case.id, message, case.line))
    elif existing and case.category in TOPICS and case.category not in {corpus[doc].topic for doc in existing}:
        topics = ", ".join(sorted({corpus[doc].topic for doc in existing}))
        message = f"la categoría '{case.category}' no es el tema de sus documentos esperados ({topics})"
        issues.append(Issue("G-CATEGORY-TOPIC", case.id, message, case.line))
    return issues


def _content_issues(case: GoldenCase, corpus: dict[str, CorpusDocument]) -> list[Issue]:
    issues: list[Issue] = []
    if not case.answerable and any(char.isdigit() for char in case.reference_answer):
        message = "la respuesta de un caso sin respuesta no puede contener cifras (ni plazos ni importes inventados)"
        issues.append(Issue("G-NOANSWER-DIGITS", case.id, message, case.line))
    question = normalize_text(case.question)
    if question and any(f" {question} " in f" {document.text} " for document in corpus.values()):
        message = "la pregunta es una frase copiada de un documento; reescríbela como lo haría un cliente"
        issues.append(Issue("G-QUESTION-VERBATIM", case.id, message, case.line))
    for name, text in (("la pregunta", case.question), ("la respuesta de referencia", case.reference_answer)):
        if _has_personal_data(text):
            message = f"{name} contiene un correo que no es {ALLOWED_EMAIL_SUFFIX} o una secuencia de 8 o más dígitos"
            issues.append(Issue("G-PII", case.id, message, case.line))
    return issues


def _has_personal_data(text: str) -> bool:
    foreign_email = any(not email.lower().endswith(ALLOWED_EMAIL_SUFFIX) for email in _EMAIL.findall(text))
    return foreign_email or bool(_LONG_DIGITS.search(text))


def _per_topic_issues(cases: list[GoldenCase], rules: GoldenRules) -> list[Issue]:
    answered: dict[str, int] = defaultdict(int)
    for case in cases:
        if case.answerable:
            answered[case.category] += 1
    issues: list[Issue] = []
    for topic in TOPICS:
        if answered[topic] < rules.min_answered_per_topic:
            message = (
                f"el tema '{topic}' tiene {answered[topic]} casos con respuesta y se necesitan al menos "
                f"{rules.min_answered_per_topic}"
            )
            issues.append(Issue("G-PER-TOPIC", SET, message))
    return issues


def _unanswered_issues(cases: list[GoldenCase], rules: GoldenRules) -> list[Issue]:
    total = len(cases)
    unanswered = [case for case in cases if not case.answerable]
    share = round(len(unanswered) / total, 9)
    low_share, high_share = rules.unanswered_min_share, rules.unanswered_max_share
    issues: list[Issue] = []
    if len(unanswered) < rules.min_unanswered or not low_share <= share <= high_share:
        message = (
            f"hay {len(unanswered)} casos sin respuesta ({share * 100:.1f} %) y debe haber al menos "
            f"{rules.min_unanswered}, entre el {low_share * 100:.0f} % y el {high_share * 100:.0f} % del total"
        )
        issues.append(Issue("G-UNANSWERED", SET, message))
    counts = {kind: sum(1 for case in unanswered if case.kind == kind) for kind in ("unanswered_topic", "off_topic")}
    needed = {"unanswered_topic": rules.min_unanswered_topic, "off_topic": rules.min_off_topic}
    for kind, count in counts.items():
        if count < needed[kind]:
            message = f"hay {count} casos '{kind}' sin respuesta y se necesitan al menos {needed[kind]}"
            issues.append(Issue("G-UNANSWERED-KINDS", SET, message))
    return issues


def _duplicate_issues(cases: list[GoldenCase]) -> list[Issue]:
    by_question: dict[str, list[str]] = defaultdict(list)
    for case in cases:
        by_question[normalize_text(case.question)].append(case.id)
    return [
        Issue("G-DUPLICATE", SET, "las preguntas de estos casos son iguales: " + ", ".join(ids))
        for ids in by_question.values()
        if len(ids) > 1
    ]
