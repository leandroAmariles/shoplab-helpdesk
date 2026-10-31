from app.chunking.splitter import split_sections
from app.domain.models import ChunkingConfig, ParsedSection

CONFIG = ChunkingConfig(chunk_size=100, chunk_overlap=20)
NO_OVERLAP = ChunkingConfig(chunk_size=100, chunk_overlap=0)


def section(path, *blocks):
    return ParsedSection(path=tuple(path), blocks=tuple(blocks))


def sentence_block(count, prefix="Frase"):
    return " ".join(f"{prefix} número {index} termina aquí." for index in range(count))


def test_a_section_that_fits_is_one_chunk_with_its_heading_first():
    chunks = split_sections([section(["Devoluciones", "Plazos"], "Tienes 30 días.")], CONFIG, "politica.md")

    assert len(chunks) == 1
    assert chunks[0].text == "Plazos\n\nTienes 30 días."
    assert chunks[0].section == "Devoluciones > Plazos"
    assert chunks[0].document == "politica.md"
    assert chunks[0].position == 0


def test_a_long_section_is_split_into_chunks_that_never_exceed_the_size():
    chunks = split_sections([section(["A"], sentence_block(40))], CONFIG, "doc.md")

    assert len(chunks) > 3
    assert all(0 < len(chunk.text) <= 100 for chunk in chunks)
    assert {chunk.section for chunk in chunks} == {"A"}


def test_consecutive_chunks_of_a_section_repeat_the_overlap_exactly():
    chunks = split_sections([section(["A"], sentence_block(40))], CONFIG, "doc.md")

    for previous, current in zip(chunks, chunks[1:], strict=False):
        assert current.text.startswith(previous.text[-20:])


def test_without_overlap_nothing_is_repeated():
    chunks = split_sections([section(["A"], sentence_block(40))], NO_OVERLAP, "doc.md")

    for previous, current in zip(chunks, chunks[1:], strict=False):
        assert not current.text.startswith(previous.text[-20:])
    joined = " ".join(chunk.text for chunk in chunks)
    assert "Frase número 39 termina aquí." in joined


def test_the_overlap_is_not_carried_across_sections():
    chunks = split_sections(
        [section(["A"], sentence_block(10)), section(["B"], "Texto de la segunda sección.")], CONFIG, "doc.md"
    )

    first_of_b = next(chunk for chunk in chunks if chunk.section == "B")
    previous = chunks[chunks.index(first_of_b) - 1]
    assert first_of_b.text == "B\n\nTexto de la segunda sección."
    assert not first_of_b.text.startswith(previous.text[-20:])


def test_a_chunk_never_mixes_two_sections():
    chunks = split_sections(
        [section(["A"], "Uno."), section(["A", "B"], "Dos."), section(["C"], "Tres.")], CONFIG, "doc.md"
    )

    assert [chunk.section for chunk in chunks] == ["A", "A > B", "C"]
    assert "Dos." not in chunks[0].text and "Tres." not in chunks[1].text


def test_positions_are_consecutive_from_zero_across_sections():
    chunks = split_sections(
        [section(["A"], sentence_block(20)), section(["B"], sentence_block(20))], CONFIG, "doc.md"
    )

    assert [chunk.position for chunk in chunks] == list(range(len(chunks)))


def test_a_section_without_text_of_its_own_gives_no_chunk():
    chunks = split_sections([section(["Solo título"]), section(["Solo título", "Hijo"], "Contenido.")], CONFIG, "d.md")

    assert [chunk.section for chunk in chunks] == ["Solo título > Hijo"]


def test_text_before_the_first_heading_is_a_chunk_with_an_empty_section():
    chunks = split_sections([section([], "Texto suelto sin encabezado.")], CONFIG, "d.md")

    assert len(chunks) == 1
    assert chunks[0].section == ""
    assert chunks[0].text == "Texto suelto sin encabezado."


def test_a_block_that_fits_is_not_cut():
    table = "| Motivo | Plazo |\n|---|---|\n| Arrepentimiento | 7 días |"
    chunks = split_sections([section(["A"], sentence_block(2), table)], NO_OVERLAP, "d.md")

    assert any(table in chunk.text for chunk in chunks)


def test_a_block_that_does_not_fit_is_cut_at_its_lines_first():
    lines = [f"línea {index:02d} con algo de texto de relleno" for index in range(8)]
    chunks = split_sections([section(["A"], "\n".join(lines))], NO_OVERLAP, "d.md")

    assert len(chunks) > 1
    for chunk in chunks:
        assert all(part in lines or part == "A" or part == "" for part in chunk.text.split("\n"))


def test_a_word_longer_than_the_limit_is_cut_by_characters():
    chunks = split_sections([section(["A"], "x" * 450)], CONFIG, "d.md")

    assert all(len(chunk.text) <= 100 for chunk in chunks)
    assert "".join(chunk.text for chunk in chunks).count("x") >= 450


def test_accents_and_enyes_are_preserved():
    chunks = split_sections([section(["Garantía"], "La reparación del año cubre niños y señoras.")], CONFIG, "d.md")

    assert "reparación del año cubre niños y señoras." in chunks[0].text
    assert chunks[0].section == "Garantía"


def test_the_result_is_deterministic():
    sections = [section(["A"], sentence_block(30)), section(["B"], sentence_block(5))]

    assert split_sections(sections, CONFIG, "d.md") == split_sections(sections, CONFIG, "d.md")


def test_the_maximum_overlap_still_makes_progress():
    config = ChunkingConfig(chunk_size=100, chunk_overlap=99)

    chunks = split_sections([section(["A"], "palabra " * 40)], config, "d.md")

    assert all(len(chunk.text) <= 100 for chunk in chunks)
    assert len(chunks) > 1
