from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document
from chunking_lab.strategies import Strategy, build_chunks
from chunking_lab.strategies.fixed import split_fixed


def document(body: str, document_id: str = "returns-01-x") -> Document:
    return Document(id=document_id, topic="returns", raw="---\na: b\n---\n" + body, body=body)


def test_windows_step_by_size_minus_overlap():
    text = "".join(chr(ord("a") + i % 26) for i in range(2500))
    windows = split_fixed(text, 1000, 100)
    assert [len(w) for w in windows] == [1000, 1000, 700]
    assert windows[0] == text[0:1000]
    assert windows[1] == text[900:1900]
    assert windows[2] == text[1800:2500]


def test_each_chunk_repeats_the_last_overlap_characters_of_the_previous():
    text = "".join(chr(ord("a") + i % 26) for i in range(3000))
    windows = split_fixed(text, 1000, 100)
    for previous, current in zip(windows, windows[1:], strict=False):
        assert current[:100] == previous[-100:]


def test_overlap_zero_means_no_repetition():
    text = "x" * 150 + "y" * 150
    assert split_fixed(text, 100, 0) == ["x" * 100, "x" * 50 + "y" * 50, "y" * 100]


def test_no_chunk_exceeds_the_size_and_the_last_one_may_be_shorter():
    windows = split_fixed("palabra " * 400, 1000, 100)
    assert all(0 < len(w) <= 1000 for w in windows)
    assert len(windows[-1]) < 1000


def test_text_that_fits_is_a_single_chunk_and_blank_text_is_none():
    assert split_fixed("hola", 1000, 100) == ["hola"]
    assert split_fixed("   \n  ", 1000, 100) == []


def test_never_emits_a_chunk_made_only_of_the_previous_overlap():
    text = "a" * 1000
    assert split_fixed(text, 1000, 100) == [text]


def test_cuts_in_the_middle_of_a_word_when_it_has_to():
    windows = split_fixed("abcdefghij" * 30, 100, 10)
    assert windows[0] == "abcdefghij" * 10
    assert windows[1].startswith("abcdefghij")  # starts at 90 -> 'a' again, never snapped to a boundary


def test_build_chunks_uses_the_body_without_front_matter_and_numbers_positions():
    config = ExperimentConfig(chunk_size=100, chunk_overlap=10, min_chunk_chars=0)
    chunks = build_chunks(Strategy.FIXED, [document("z" * 250), document("w" * 50, "returns-02-y")], config)
    assert [(c.document, c.position) for c in chunks] == [
        ("returns-01-x", 0), ("returns-01-x", 1), ("returns-01-x", 2), ("returns-02-y", 0)]
    assert all(c.strategy is Strategy.FIXED and c.section == "" for c in chunks)
    assert all("a: b" not in c.text for c in chunks)


def test_same_input_same_chunks():
    config = ExperimentConfig()
    docs = [document("Texto de prueba. " * 200)]
    assert build_chunks(Strategy.FIXED, docs, config) == build_chunks(Strategy.FIXED, docs, config)
