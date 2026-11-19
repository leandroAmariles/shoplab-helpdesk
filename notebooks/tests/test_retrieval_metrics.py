import numpy as np
import pytest

from chunking_lab.metrics import hit_rate, is_correct, mean_reciprocal_rank, rank_of_first_correct
from chunking_lab.questions import Question
from chunking_lab.retrieval import ChunkIndex, run_retrieval
from chunking_lab.strategies import Chunk, Strategy
from helpers import FakeEmbedder

PASSAGE = "El plazo es de siete días corridos."
QUESTION = Question(id="Q01", topic="returns", question="¿Cuántos días?", document="returns-01-x", passage=PASSAGE)


def chunk(position: int, text: str, document: str = "returns-01-x") -> Chunk:
    return Chunk(strategy=Strategy.FIXED, document=document, position=position, section="", text=text)


def test_search_returns_the_top_k_by_cosine_over_the_whole_corpus():
    chunks = [chunk(0, "a"), chunk(1, "b", "other-doc"), chunk(2, "c"), chunk(3, "d")]
    vectors = np.array([[1.0, 0.0], [0.9, 0.1], [0.0, 1.0], [0.5, 0.5]])
    index = ChunkIndex(chunks, vectors)
    hits = index.search(np.array([1.0, 0.0]), top_k=3)
    assert [h.chunk.text for h in hits] == ["a", "b", "d"]
    assert hits[0].score == pytest.approx(1.0)
    assert hits[0].score >= hits[1].score >= hits[2].score


def test_ties_are_broken_by_document_then_position():
    chunks = [chunk(1, "b2", "b-doc"), chunk(0, "b1", "b-doc"), chunk(0, "a1", "a-doc")]
    index = ChunkIndex(chunks, np.array([[1.0, 0.0]] * 3))
    hits = index.search(np.array([1.0, 0.0]), top_k=3)
    assert [h.chunk.text for h in hits] == ["a1", "b1", "b2"]


def test_top_k_larger_than_the_corpus_returns_everything():
    index = ChunkIndex([chunk(0, "a")], np.array([[1.0, 0.0]]))
    assert len(index.search(np.array([1.0, 0.0]), top_k=4)) == 1


def test_a_chunk_is_correct_only_with_the_expected_document_and_the_passage():
    assert is_correct(chunk(0, f"intro {PASSAGE} fin"), QUESTION)
    assert is_correct(chunk(0, "El plazo es de\n  siete   días corridos."), QUESTION)
    assert not is_correct(chunk(0, "otro texto del mismo documento"), QUESTION)
    assert not is_correct(chunk(0, PASSAGE, "other-doc"), QUESTION)


def test_a_passage_split_across_two_chunks_is_a_miss():
    first, second = chunk(0, "El plazo es de siete"), chunk(1, "días corridos.")
    assert rank_of_first_correct([first, second], QUESTION) is None


def test_rank_is_the_one_based_position_of_the_first_correct_chunk():
    hits = [chunk(0, "nada"), chunk(1, PASSAGE), chunk(2, PASSAGE)]
    assert rank_of_first_correct(hits, QUESTION) == 2
    assert rank_of_first_correct(hits[:1], QUESTION) is None


def test_hit_rate_and_mrr():
    ranks = [1, 2, None, 4]
    assert hit_rate(ranks) == pytest.approx(0.75)
    assert mean_reciprocal_rank(ranks) == pytest.approx((1 + 0.5 + 0 + 0.25) / 4)
    assert hit_rate([None, None]) == 0.0
    assert mean_reciprocal_rank([None, None]) == 0.0


def test_run_retrieval_ranks_every_question_and_is_deterministic():
    chunks = [chunk(0, "nada"), chunk(1, PASSAGE), chunk(2, "más nada")]
    embedder = FakeEmbedder({"nada": [0.0, 1.0], PASSAGE: [1.0, 0.0], "más nada": [0.0, -1.0],
                             QUESTION.question: [1.0, 0.1]})
    index = ChunkIndex.build(chunks, embedder)
    first = run_retrieval(index, [QUESTION], embedder, top_k=2)
    second = run_retrieval(index, [QUESTION], embedder, top_k=2)
    assert first == second
    assert [r.question_id for r in first] == ["Q01"]
    assert first[0].rank == 1
    assert [h.chunk.text for h in first[0].hits] == [PASSAGE, "nada"]
