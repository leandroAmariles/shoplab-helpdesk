"""Which versions of the prompts a quality run measures (specs/LAB-85-versioned-prompts, FR-017 to FR-020).

``quality --prompt chat-system=3 --prompt chat-context=staging`` fixes a prompt to a version number or to the version a
label points to. Everything here is pure: it reads the arguments, checks them and formats the result; looking up the
label in Langfuse is ``prompt_client``'s job. A run that fixes nothing measures what ``production`` serves.
"""

import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from shoplab_eval.constants import (
    PRODUCTION_LABEL,
    PROMPT_LABEL_PATTERN,
    PROMPT_NAMES,
    PROMPT_VERSIONS_SEPARATOR,
)


@dataclass(frozen=True)
class PromptRequest:
    """What was asked for one prompt: ``value`` is a version number (digits) or the name of a label."""

    name: str
    value: str

    @property
    def is_version(self) -> bool:
        return self.value.isdigit()


@dataclass(frozen=True)
class PromptChoice:
    """A prompt fixed to a concrete version: ``requested`` is what was written, ``version`` the number it resolved to."""

    name: str
    requested: str
    version: int


def parse_prompt_arguments(arguments: Sequence[str]) -> tuple[PromptRequest, ...]:
    """The ``--prompt NAME=VALUE`` arguments, checked. Raises ``ValueError`` (a usage error) naming what is wrong."""
    requests: list[PromptRequest] = []
    seen: set[str] = set()
    for argument in arguments:
        name, separator, value = argument.partition("=")
        name, value = name.strip(), value.strip()
        if not separator or not name or not value:
            raise ValueError(f"--prompt {argument!r} no es válido: debe ser NOMBRE=VERSIÓN o NOMBRE=ETIQUETA")
        if name not in PROMPT_NAMES:
            valid = ", ".join(PROMPT_NAMES)
            raise ValueError(f"--prompt {argument!r}: el prompt {name!r} no existe; los válidos son {valid}")
        if name in seen:
            raise ValueError(f"--prompt {name!r} está repetido: cada prompt se fija una sola vez")
        if value.isdigit():
            if int(value) < 1:
                raise ValueError(f"--prompt {argument!r}: la versión es un número desde 1")
        elif not re.fullmatch(PROMPT_LABEL_PATTERN, value):
            raise ValueError(
                f"--prompt {argument!r}: {value!r} no es un número de versión ni un nombre de etiqueta válido "
                "(letras, cifras, puntos y guiones)"
            )
        seen.add(name)
        requests.append(PromptRequest(name, value))
    return tuple(requests)


def versions_header(choices: Iterable[PromptChoice]) -> str:
    """The value of ``X-Prompt-Versions`` for the versions already resolved; empty when nothing is fixed."""
    return ",".join(f"{choice.name}={choice.version}" for choice in choices)


def format_prompt_versions(choices: Iterable[PromptChoice]) -> str:
    """The ``prompt_versions`` column: ``name=version`` pairs in alphabetical order, or ``production`` if none."""
    pairs = sorted(f"{choice.name}={choice.version}" for choice in choices)
    return PROMPT_VERSIONS_SEPARATOR.join(pairs) if pairs else PRODUCTION_LABEL


def parse_prompt_versions(column: str) -> dict[str, str]:
    """The inverse of ``format_prompt_versions``: ``{name: version}``; empty for ``production`` or an empty cell."""
    if not column.strip() or column.strip() == PRODUCTION_LABEL:
        return {}
    versions: dict[str, str] = {}
    for pair in column.split(PROMPT_VERSIONS_SEPARATOR):
        name, _, version = pair.partition("=")
        versions[name.strip()] = version.strip() or PRODUCTION_LABEL
    return versions
