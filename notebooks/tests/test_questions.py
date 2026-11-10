import pytest

from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document, load_corpus
from chunking_lab.errors import InvalidQuestionsError
from chunking_lab.questions import Question, load_questions, validate_questions

LONG = "El plazo para devolver es de siete días corridos desde la entrega, y el reembolso incluye el flete original."
SHORT = "Aceptamos Visa y Mastercard en todos los pedidos."


def doc(document_id: str, body: str) -> Document:
    return Document(id=document_id, topic=document_id.split("-")[0], raw=body, body=body)


@pytest.fixture
def documents():
    return [
        doc("returns-01-policy", f"# Devoluciones\n\n{LONG}\n"),
        doc("payments-01-methods", f"# Pagos\n\n{SHORT} Además, ofrecemos tres cuotas sin interés.\n"),
    ]


def question(qid: str, topic: str = "returns", document: str = "returns-01-policy", passage: str = LONG,
             text: str = "¿Cuántos días tengo para devolver?") -> Question:
    return Question(id=qid, topic=topic, question=text, document=document, passage=passage)


def valid_set() -> list[Question]:
    """Four questions per topic of the two-topic corpus above; 2 of the 8 passages are long (> 100)."""
    return [
        question("Q01"), question("Q02"), question("Q03", passage=LONG[:60]), question("Q04", passage=LONG[:70]),
        question("Q05", "payments", "payments-01-methods", SHORT),
        question("Q06", "payments", "payments-01-methods", SHORT[:45]),
        question("Q07", "payments", "payments-01-methods", "Además, ofrecemos tres cuotas sin interés."),
        question("Q08", "payments", "payments-01-methods", SHORT + " Además, ofrecemos tres cuotas sin interés."),
    ]


def codes(error: InvalidQuestionsError) -> set[str]:
    return {code for code, _, _ in error.failures}


def check(questions, documents, **kwargs):
    return validate_questions(questions, documents, expected_total=8, per_topic=4, min_long=2, **kwargs)


def test_a_valid_set_passes(documents):
    check(valid_set(), documents)


def test_count_and_topics(documents):
    with pytest.raises(InvalidQuestionsError) as raised:
        check(valid_set()[:-1], documents)
    assert {"Q-COUNT", "Q-TOPICS"} <= codes(raised.value)


def test_ids_must_have_the_format_and_be_unique(documents):
    questions = valid_set()
    questions[1] = question("Q01")
    questions[2] = question("q3", passage=LONG[:60])
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, documents)
    assert "Q-ID" in codes(raised.value)
    assert sum(1 for code, _, _ in raised.value.failures if code == "Q-ID") == 2


def test_document_must_exist_and_match_the_topic(documents):
    questions = valid_set()
    questions[0] = question("Q01", document="returns-99-missing")
    questions[1] = question("Q02", topic="payments", document="returns-01-policy")
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, documents)
    assert [c for c, _, _ in raised.value.failures].count("Q-DOC") == 2


def test_passage_must_be_in_the_expected_document(documents):
    questions = valid_set()
    questions[0] = question("Q01", passage="Texto que no está en ningún documento del corpus de prueba.")
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, documents)
    assert any(c == "Q-PASSAGE-MISSING" and q == "Q01" for c, q, _ in raised.value.failures)


def test_passage_must_not_appear_in_another_document(documents):
    shared = "Este pasaje se repite literalmente en dos documentos distintos."
    docs = [doc("returns-01-policy", f"{LONG}\n{shared}"), doc("payments-01-methods", f"{SHORT}\n{shared}")]
    questions = valid_set()
    questions[0] = question("Q01", passage=shared)
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, docs)
    assert any(c == "Q-PASSAGE-AMBIGUOUS" and q == "Q01" for c, q, _ in raised.value.failures)


def test_passage_length_is_between_40_and_300_normalized_characters():
    long_body = "palabra " * 80
    docs = [doc("returns-01-policy", f"{LONG}\n{long_body}"), doc("payments-01-methods", SHORT)]
    questions = valid_set()
    questions[0] = question("Q01", passage="Corto.")
    questions[1] = question("Q02", passage=long_body.strip())
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, docs)
    assert [q for c, q, _ in raised.value.failures if c == "Q-PASSAGE-LENGTH"] == ["Q01", "Q02"]


def test_enough_long_passages(documents):
    questions = [q if q.id not in ("Q01", "Q02") else question(q.id, passage=LONG[:60]) for q in valid_set()]
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, documents, )
    assert "Q-LONG-PASSAGES" in codes(raised.value)


def test_all_failures_are_reported_together_with_the_question_id(documents):
    questions = valid_set()
    questions[0] = question("Q01", passage="Corto.")
    questions[1] = question("Q02", document="returns-99-missing")
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, documents)
    message = str(raised.value)
    assert "Q01" in message
    assert "Q02" in message
    assert len(raised.value.failures) >= 2


def test_a_passage_across_a_markdown_line_break_is_found_after_collapsing_whitespace():
    body = "El plazo para devolver es de siete días corridos\ndesde la entrega y se cuenta en días naturales."
    docs = [doc("returns-01-policy", body), doc("payments-01-methods", SHORT)]
    questions = valid_set()
    questions[0] = question("Q01", passage="El plazo para devolver es de siete días corridos desde la entrega")
    questions[1] = question("Q02", passage=body.replace("\n", " "))
    check_codes = set()
    try:
        check(questions, docs)
    except InvalidQuestionsError as error:
        check_codes = {c for c, q, _ in error.failures if q in ("Q01", "Q02")}
    assert "Q-PASSAGE-MISSING" not in check_codes


def test_case_and_accents_are_respected():
    docs = [doc("returns-01-policy", LONG), doc("payments-01-methods", SHORT)]
    questions = valid_set()
    questions[0] = question("Q01", passage=LONG.lower())
    with pytest.raises(InvalidQuestionsError) as raised:
        check(questions, docs)
    assert any(c == "Q-PASSAGE-MISSING" and q == "Q01" for c, q, _ in raised.value.failures)


def write(tmp_path, text):
    path = tmp_path / "questions.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_load_reads_toml_with_accents(tmp_path):
    path = write(tmp_path, '[[question]]\nid = "Q01"\ntopic = "returns"\nquestion = "¿Cuántos días?"\n'
                          'document = "returns-01-policy"\npassage = "siete días corridos"\n')
    assert load_questions(path) == [
        Question(id="Q01", topic="returns", question="¿Cuántos días?", document="returns-01-policy",
                 passage="siete días corridos")]


def test_load_rejects_unknown_missing_and_empty_keys(tmp_path):
    path = write(tmp_path, '[[question]]\nid = "Q01"\ntopic = "returns"\nquestion = ""\n'
                          'document = "returns-01-policy"\nextra = "x"\n')
    with pytest.raises(InvalidQuestionsError) as raised:
        load_questions(path)
    messages = " ".join(m for _, _, m in raised.value.failures)
    assert {c for c, _, _ in raised.value.failures} == {"Q-FIELDS"}
    assert "extra" in messages
    assert "passage" in messages
    assert "question" in messages


def test_the_real_questions_file_is_valid_against_the_real_corpus():
    config = ExperimentConfig()
    questions = load_questions(config.questions_path)
    validate_questions(questions, load_corpus(config.kb_dir))
    assert len(questions) == 20
