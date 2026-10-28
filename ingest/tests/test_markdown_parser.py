from app.parsing.markdown import parse_markdown


def paths(parsed):
    return [section.path for section in parsed.sections]


def test_nested_headings_build_the_section_path():
    parsed = parse_markdown("# Devoluciones\n\nIntro.\n\n## Plazos\n\nTreinta días.\n\n### Excepciones\n\nNinguna.\n")

    assert paths(parsed) == [
        ("Devoluciones",),
        ("Devoluciones", "Plazos"),
        ("Devoluciones", "Plazos", "Excepciones"),
    ]
    assert parsed.sections[1].blocks == ("Treinta días.",)


def test_a_sibling_heading_replaces_the_previous_one_at_its_level():
    parsed = parse_markdown("# A\n\n## B\n\ntexto b\n\n## C\n\ntexto c\n")

    assert paths(parsed) == [("A",), ("A", "B"), ("A", "C")]


def test_skipped_levels_do_not_invent_intermediate_headings():
    parsed = parse_markdown("# A\n\n### C\n\ntexto\n")

    assert paths(parsed) == [("A",), ("A", "C")]


def test_a_document_without_headings_is_one_section_with_an_empty_path():
    parsed = parse_markdown("Primer párrafo.\n\nSegundo párrafo.\n")

    assert paths(parsed) == [()]
    assert parsed.sections[0].blocks == ("Primer párrafo.", "Segundo párrafo.")


def test_the_text_before_the_first_heading_is_its_own_section():
    parsed = parse_markdown("Preámbulo.\n\n# Título\n\nCuerpo.\n")

    assert paths(parsed) == [(), ("Título",)]


def test_a_heading_without_text_keeps_an_empty_section_that_yields_no_chunk():
    parsed = parse_markdown("# A\n\n## B\n\n## C\n\ntexto\n")

    assert parsed.sections[0].blocks == ()
    assert parsed.sections[1].blocks == ()
    assert parsed.sections[2].blocks == ("texto",)


def test_tables_lists_and_code_blocks_are_kept_whole():
    source = (
        "# A\n\n| Motivo | Plazo |\n|---|---|\n| Arrepentimiento | 7 días |\n\n"
        "- uno\n- dos\n\n```python\nprint('hola')\n```\n"
    )

    blocks = parse_markdown(source).sections[0].blocks

    assert blocks[0] == "| Motivo | Plazo |\n|---|---|\n| Arrepentimiento | 7 días |"
    assert blocks[1] == "- uno\n- dos"
    assert blocks[2] == "```python\nprint('hola')\n```"


def test_the_title_is_the_first_level_one_heading():
    assert parse_markdown("## Antes\n\n# Política de envíos\n\n# Otro\n").title == "Política de envíos"


def test_there_is_no_title_without_a_level_one_heading():
    assert parse_markdown("## Solo secundario\n\ntexto\n").title is None


def test_the_front_matter_is_discarded():
    parsed = parse_markdown("---\ntitle: Garantía\ntopic: warranty\n---\n# Garantía legal\n\nTexto.\n")

    assert parsed.title == "Garantía legal"
    assert paths(parsed) == [("Garantía legal",)]
    assert "topic" not in " ".join(parsed.sections[0].blocks)


def test_accents_and_enyes_are_preserved():
    parsed = parse_markdown("# Garantía\n\nLa reparación del año cubre a los niños.\n")

    assert parsed.sections[0].path == ("Garantía",)
    assert parsed.sections[0].blocks == ("La reparación del año cubre a los niños.",)


def test_setext_headings_are_headings_too():
    parsed = parse_markdown("Título\n======\n\nTexto.\n")

    assert paths(parsed) == [("Título",)]
