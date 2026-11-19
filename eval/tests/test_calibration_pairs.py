"""The calibration pairs are real: fragments in the corpus, cases in the dataset (no model here)."""

import pytest

from calibration_pairs import FAITHFULNESS_PAIRS, PRECISION_TRIPLES, RELEVANCE_PAIRS, golden_case
from shoplab_eval.constants import DEFAULT_KB_DIR
from shoplab_eval.corpus import load_corpus, normalize_text


@pytest.fixture(scope="module")
def corpus_text() -> str:
    return " ".join(document.text for document in load_corpus(DEFAULT_KB_DIR).values())


def test_there_are_the_pairs_the_success_criteria_ask_for() -> None:
    assert len(FAITHFULNESS_PAIRS) == 5  # SC-002
    assert len(RELEVANCE_PAIRS) == 2  # SC-003
    assert len(PRECISION_TRIPLES) == 3  # SC-003


@pytest.mark.parametrize("pair", FAITHFULNESS_PAIRS, ids=lambda pair: pair.case_id)
def test_faithfulness_contexts_are_literal_excerpts_of_the_corpus(pair, corpus_text: str) -> None:
    for fragment in pair.context:
        assert normalize_text(fragment) in corpus_text, fragment


@pytest.mark.parametrize("triple", PRECISION_TRIPLES, ids=lambda triple: triple.case_id)
def test_precision_fragments_are_literal_excerpts_of_the_corpus(triple, corpus_text: str) -> None:
    for fragment in (triple.useful, *triple.irrelevant):
        assert normalize_text(fragment) in corpus_text, fragment


@pytest.mark.parametrize("triple", PRECISION_TRIPLES, ids=lambda triple: triple.case_id)
def test_the_two_orders_hold_the_same_fragments_with_the_useful_one_first_and_last(triple) -> None:
    assert sorted(triple.useful_first) == sorted(triple.useful_last)
    assert triple.useful_first[0] == triple.useful
    assert triple.useful_last[-1] == triple.useful


def test_every_pair_points_at_a_case_with_an_answer() -> None:
    for pair in (*FAITHFULNESS_PAIRS, *RELEVANCE_PAIRS, *PRECISION_TRIPLES):
        assert golden_case(pair.case_id)["expected_documents"], pair.case_id


def test_the_invented_answer_is_the_faithful_one_plus_something_more() -> None:
    for pair in FAITHFULNESS_PAIRS:
        assert pair.invented_answer.startswith(pair.faithful_answer)
        assert len(pair.invented_answer) > len(pair.faithful_answer)
        assert pair.invented_addition.strip()


def test_the_off_target_answer_is_not_the_relevant_one() -> None:
    for pair in RELEVANCE_PAIRS:
        assert pair.off_target_answer != pair.relevant_answer
