"""Sources of candidates: the real golden file, and Olist reviews through a fake read-only connection."""

import hashlib
from collections import Counter

import pytest

from intent_dataset.constants import GOLDEN_PATH
from intent_dataset.errors import ConfigurationError
from intent_dataset.sources import hint_for, read_golden, read_reviews
from olist_eda.config import AnalysisSettings


class FakeCursor:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows


class FakeConnection:
    def __init__(self, rows):
        self.rows = rows
        self.statements: list[str] = []
        self.closed = False

    def execute(self, sql, params=None):
        self.statements.append(sql)
        return FakeCursor(self.rows)

    def close(self):
        self.closed = True


def _reviews(*messages):
    return [(f"{index:032x}", message) for index, message in enumerate(messages, start=1)]


def _read(rows, **options):
    connection = FakeConnection(rows)
    options.setdefault("limit", 100)
    options.setdefault("seed", 117)
    candidates = read_reviews(AnalysisSettings(), connector=lambda settings: connection, **options)
    return candidates, connection


# --- golden (the real eval/golden.jsonl) -----------------------------------------------------------------------------


def test_golden_gives_120_candidates_with_the_proposed_label_of_their_category():
    candidates = read_golden(GOLDEN_PATH)
    assert len(candidates) == 120
    assert Counter(c.proposed_label for c in candidates) == {"politica": 88, "charla": 32}
    assert {c.language for c in candidates} == {"es"}
    assert {c.source for c in candidates} == {"golden"}


def test_golden_candidates_carry_their_traceability_and_no_decision():
    first = read_golden(GOLDEN_PATH)[0]
    assert first.group_id == first.golden_id == "G001"
    assert first.parent_id == ""
    assert first.candidate_id == "CG001"
    assert first.text.startswith("Mi boleto venció")
    assert first.decision == first.label == first.text_edit == first.pii_checked == ""


def test_golden_off_topic_and_injection_are_proposed_as_charla():
    by_id = {c.golden_id: c for c in read_golden(GOLDEN_PATH)}
    assert by_id["G083"].proposed_label == "charla"  # off_topic
    assert by_id["G103"].proposed_label == "charla"  # injection
    assert by_id["G091"].proposed_label == "charla"  # small_talk


def test_golden_with_an_unknown_category_is_rejected(tmp_path):
    path = tmp_path / "golden.jsonl"
    path.write_text('{"id": "G001", "question": "hola", "category": "nueva"}\n', encoding="utf-8")
    with pytest.raises(ConfigurationError, match="nueva"):
        read_golden(path)


def test_golden_missing_file_is_a_configuration_error(tmp_path):
    with pytest.raises(ConfigurationError, match="golden"):
        read_golden(tmp_path / "nope.jsonl")


# --- Olist reviews (fake connection) ---------------------------------------------------------------------------------


def test_reviews_are_masked_before_they_are_returned():
    candidates, _ = _read(_reviews("meu pedido não chegou, escreva para ana@correo.com ou ligue 11 91234-5678"))
    assert [c.text for c in candidates] == ["meu pedido não chegou, escreva para [EMAIL] ou ligue [PHONE]"]


def test_reviews_are_read_only_and_the_connection_is_closed():
    _, connection = _read(_reviews("o produto chegou antes do prazo, muito bom"))
    assert len(connection.statements) == 1
    assert connection.statements[0].lstrip().upper().startswith("SELECT")
    assert connection.closed


def test_empty_short_and_long_comments_are_dropped():
    long_comment = "muito bom " * 40
    candidates, _ = _read(_reviews("", None, "ok", "recomendo", long_comment, "o produto chegou rápido e bem embalado"))
    assert [c.text for c in candidates] == ["o produto chegou rápido e bem embalado"]


def test_duplicates_by_normalized_text_are_removed():
    rows = _reviews("Produto ótimo, chegou rápido!", "produto otimo chegou rapido", "chegou tudo certo e no prazo")
    candidates, _ = _read(rows)
    assert len(candidates) == 2


def test_a_comment_that_is_only_personal_data_is_dropped():
    candidates, _ = _read(_reviews("ana@correo.com 11 91234-5678 01310-100 123.456.789-09"))
    assert candidates == []


def test_review_candidates_are_portuguese_with_a_hashed_group_and_no_real_id():
    rows = _reviews("o produto chegou rápido e bem embalado")
    candidates, _ = _read(rows)
    candidate = candidates[0]
    review_id = rows[0][0]
    expected = "R" + hashlib.sha256(review_id.encode()).hexdigest()[:8]
    assert (candidate.source, candidate.language, candidate.group_id) == ("olist_review", "pt", expected)
    assert candidate.candidate_id == "C" + expected
    assert candidate.golden_id == candidate.parent_id == candidate.proposed_label == ""
    assert all(review_id not in str(value) for value in vars(candidate).values())


def test_selection_is_deterministic_for_a_seed_and_respects_the_limit():
    rows = _reviews(*[f"comentário número {n} sobre o pedido e o produto" for n in range(60)])
    first, _ = _read(rows, limit=10, seed=7)
    again, _ = _read(rows, limit=10, seed=7)
    other, _ = _read(rows, limit=10, seed=8)
    assert len(first) == 10
    assert [c.text for c in first] == [c.text for c in again]
    assert [c.text for c in first] != [c.text for c in other]


def test_selection_spreads_the_candidates_over_the_hints():
    rows = _reviews(
        *[f"meu pedido {n} não chegou no prazo combinado" for n in range(20)],
        *[f"o produto {n} veio com defeito de fábrica" for n in range(20)],
        *[f"quero reclamar e falar com um atendente {n}" for n in range(20)],
    )
    candidates, _ = _read(rows, limit=9)
    assert Counter(c.hint for c in candidates) == {"pedido": 3, "producto": 3, "escalado": 3}


@pytest.mark.parametrize(
    ("text", "hint"),
    [
        ("meu pedido não chegou no prazo", "pedido"),
        ("o produto veio com defeito", "producto"),
        ("vou no Procon, quero falar com um atendente", "escalado"),
        ("gostei muito, recomendo a todos", ""),
    ],
)
def test_hint_is_a_keyword_guess_not_a_label(text, hint):
    assert hint_for(text) == hint


def test_a_hint_is_never_written_as_a_proposed_label():
    candidates, _ = _read(_reviews("meu pedido não chegou no prazo"))
    assert candidates[0].hint == "pedido"
    assert candidates[0].proposed_label == ""
