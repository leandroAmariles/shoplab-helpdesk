"""The arguments ``--prompt NAME=VALUE`` and how a run records its prompts (specs/LAB-85, FR-017 and FR-020).

All of it is pure: no network, no model.
"""

import pytest

from shoplab_eval.prompt_selection import (
    PromptChoice,
    PromptRequest,
    format_prompt_versions,
    parse_prompt_arguments,
    parse_prompt_versions,
    versions_header,
)


def test_a_version_number_and_a_label_are_told_apart() -> None:
    requests = parse_prompt_arguments(["chat-system=3", "chat-context=staging"])

    assert requests == (PromptRequest("chat-system", "3"), PromptRequest("chat-context", "staging"))
    assert requests[0].is_version is True
    assert requests[1].is_version is False


def test_without_arguments_nothing_is_fixed() -> None:
    assert parse_prompt_arguments([]) == ()


def test_spaces_around_the_name_and_the_value_are_ignored() -> None:
    assert parse_prompt_arguments([" chat-system = 2 "]) == (PromptRequest("chat-system", "2"),)


@pytest.mark.parametrize(
    ("argument", "fragment"),
    [
        ("chat-system", "NOMBRE=VERSIÓN"),
        ("chat-system=", "NOMBRE=VERSIÓN"),
        ("=3", "NOMBRE=VERSIÓN"),
        ("otro=3", "no existe"),
        ("chat-system=0", "desde 1"),
        ("chat-system=prod uction", "etiqueta"),
        ("chat-system=a/b", "etiqueta"),
    ],
)
def test_a_wrong_argument_is_a_usage_error_that_says_what_is_wrong(argument: str, fragment: str) -> None:
    with pytest.raises(ValueError, match=fragment):
        parse_prompt_arguments([argument])


def test_the_same_prompt_twice_is_a_usage_error() -> None:
    with pytest.raises(ValueError, match="repetido"):
        parse_prompt_arguments(["chat-system=1", "chat-system=2"])


def test_the_valid_names_are_listed_in_the_error() -> None:
    with pytest.raises(ValueError, match="chat-small-talk") as error:
        parse_prompt_arguments(["nada=1"])

    assert "chat-context" in str(error.value)


def test_the_header_for_the_backend_carries_the_resolved_versions() -> None:
    choices = [PromptChoice("chat-system", "staging", 3), PromptChoice("chat-context", "2", 2)]

    assert versions_header(choices) == "chat-system=3,chat-context=2"
    assert versions_header([]) == ""


def test_the_column_lists_the_versions_alphabetically_and_says_production_when_none_is_fixed() -> None:
    choices = [PromptChoice("chat-system", "staging", 3), PromptChoice("chat-context", "2", 2)]

    assert format_prompt_versions(choices) == "chat-context=2|chat-system=3"
    assert format_prompt_versions([]) == "production"


def test_the_column_is_read_back_into_versions() -> None:
    assert parse_prompt_versions("chat-context=2|chat-system=3") == {"chat-context": "2", "chat-system": "3"}
    assert parse_prompt_versions("production") == {}
    assert parse_prompt_versions("") == {}
