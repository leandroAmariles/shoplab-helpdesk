import math

import numpy as np

from chunking_lab.config import ExperimentConfig
from chunking_lab.corpus import Document
from chunking_lab.strategies import Strategy, build_chunks
from chunking_lab.strategies.semantic import adjacent_distances, semantic_units
from helpers import FakeEmbedder


def document(body: str) -> Document:
    return Document(id="returns-01-x", topic="returns", raw=body, body=body)


def angles(mapping: dict[str, float]) -> FakeEmbedder:
    """Each text gets a unit vector at the given angle: consecutive distance is 1 - cos(delta)."""
    return FakeEmbedder({text: [math.cos(a), math.sin(a)] for text, a in mapping.items()})


def config(**kwargs) -> ExperimentConfig:
    return ExperimentConfig(**{"chunk_size": 1000, "chunk_overlap": 0, "min_chunk_chars": 0, **kwargs})


def test_units_split_paragraphs_into_sentences():
    assert [u.text for u in semantic_units("Uno. Dos! Tres?")] == ["Uno.", "Dos!", "Tres?"]


def test_a_table_is_one_unit_and_each_list_item_is_a_unit():
    table = "| a | b |\n|---|---|\n| 1 | 2 |"
    units = semantic_units(f"{table}\n\n- uno\n- dos\n  sigue\n")
    assert [u.kind for u in units] == ["table", "list_item", "list_item"]
    assert units[0].text == table
    assert units[2].text == "- dos\n  sigue"


def test_a_heading_only_block_is_prepended_to_the_next_unit():
    units = semantic_units("# Título\n\nPrimera frase. Segunda.\n\n## Otro\n\nTexto.")
    assert [u.text for u in units] == ["Título\nPrimera frase.", "Segunda.", "Otro\nTexto."]


def test_a_trailing_heading_without_text_is_its_own_unit():
    assert [u.text for u in semantic_units("Hola.\n\n## Final")] == ["Hola.", "Final"]


def test_a_unit_longer_than_the_size_is_cut_by_characters():
    long = "x" * 250
    chunks = build_chunks(Strategy.SEMANTIC, [document(long)], config(chunk_size=100), FakeEmbedder())
    assert [len(c.text) for c in chunks] == [100, 100, 50]
    assert all(len(c.text) <= 100 for c in chunks)


def test_cuts_where_the_topic_changes():
    sentences = ["A1.", "A2.", "A3.", "B1.", "B2.", "B3."]
    embedder = angles({s: (0.0 if s.startswith("A") else 1.5) for s in sentences})
    chunks = build_chunks(Strategy.SEMANTIC, [document(" ".join(sentences))], config(), embedder)
    assert [c.text for c in chunks] == ["A1.\nA2.\nA3.", "B1.\nB2.\nB3."]
    assert [c.position for c in chunks] == [0, 1]
    assert all(c.strategy is Strategy.SEMANTIC and c.section == "" for c in chunks)


def test_adjacent_distances_are_cosine_distances():
    units = semantic_units("A. B. C.")
    embedder = angles({"A.": 0.0, "B.": 0.0, "C.": math.pi / 2})
    distances = adjacent_distances(units, embedder)
    assert np.allclose(distances, [0.0, 1.0], atol=1e-6)


def sentences_of(n: int, width: int = 30) -> list[str]:
    return [f"S{i}".ljust(width - 1, "x") + "." for i in range(n)]


def test_an_oversized_group_is_split_at_its_largest_internal_distance_until_it_fits():
    sentences = sentences_of(6)
    steps = [0.1, 0.2, 0.3, 0.4, 0.5]
    cumulative = [0.0]
    for step in steps:
        cumulative.append(cumulative[-1] + step)
    embedder = angles(dict(zip(sentences, cumulative, strict=True)))
    chunks = build_chunks(Strategy.SEMANTIC, [document(" ".join(sentences))], config(chunk_size=100), embedder)
    assert [c.text for c in chunks] == [
        "\n".join(sentences[0:3]), sentences[3], sentences[4], sentences[5]]
    assert all(len(c.text) <= 100 for c in chunks)


def test_fragments_shorter_than_the_minimum_are_merged_with_the_closest_neighbour():
    sentences = ["A1.", "A2.", "B1."]
    embedder = angles({"A1.": 0.0, "A2.": 0.0, "B1.": 1.5})
    chunks = build_chunks(Strategy.SEMANTIC, [document(" ".join(sentences))], config(min_chunk_chars=50), embedder)
    assert [c.text for c in chunks] == ["A1.\nA2.\nB1."]


def test_a_merge_never_exceeds_the_size():
    sentences = sentences_of(4, width=60)
    embedder = angles(dict(zip(sentences, [0.0, 0.0, 1.5, 1.5], strict=True)))
    chunks = build_chunks(
        Strategy.SEMANTIC, [document(" ".join(sentences))], config(chunk_size=130, min_chunk_chars=100), embedder)
    assert all(len(c.text) <= 130 for c in chunks)


def test_a_document_with_one_unit_gives_one_chunk():
    chunks = build_chunks(Strategy.SEMANTIC, [document("Una sola frase.")], config(), FakeEmbedder())
    assert [c.text for c in chunks] == ["Una sola frase."]


def test_a_higher_percentile_never_produces_more_chunks():
    sentences = sentences_of(10)
    embedder = angles({s: (i * i * 0.05) for i, s in enumerate(sentences)})
    counts = [len(build_chunks(Strategy.SEMANTIC, [document(" ".join(sentences))],
                               config(breakpoint_percentile=p, chunk_size=10_000), embedder)) for p in (30, 60, 90)]
    assert counts[0] >= counts[1] >= counts[2]


def test_no_overlap_and_no_text_lost():
    sentences = sentences_of(8)
    embedder = angles({s: (i % 3) * 1.0 for i, s in enumerate(sentences)})
    chunks = build_chunks(Strategy.SEMANTIC, [document(" ".join(sentences))], config(), embedder)
    assert "\n".join(c.text for c in chunks).split("\n") == sentences


def test_same_input_same_chunks():
    sentences = sentences_of(8)
    body = " ".join(sentences)
    first = build_chunks(Strategy.SEMANTIC, [document(body)], config(), FakeEmbedder())
    second = build_chunks(Strategy.SEMANTIC, [document(body)], config(), FakeEmbedder())
    assert first == second
