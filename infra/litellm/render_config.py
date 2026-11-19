"""Renders the LiteLLM configuration template.

Expands ``${VAR}`` and ``${VAR:-default}`` (defaults may nest, e.g. ``${A:-${B:-x}}``) from the process environment.
LiteLLM's own ``os.environ/NAME`` references are left untouched: LiteLLM resolves those itself, so secrets never end
up in the rendered file.

An optional block delimited by ``#@cloud-begin`` / ``#@cloud-end`` is kept only when ``CLOUD_PROVIDER_API_KEY`` is not
empty (FR-011); otherwise the block is dropped before expansion, so a missing cloud key never needs the cloud variables.

Usage: ``python render_config.py <template> <output>``
"""

from __future__ import annotations

import os
import sys
from collections.abc import Mapping

CLOUD_BEGIN = "#@cloud-begin"
CLOUD_END = "#@cloud-end"
CLOUD_KEY = "CLOUD_PROVIDER_API_KEY"


class MissingVariableError(ValueError):
    """A ``${VAR}`` without a default is not defined (or is empty)."""


def _find_closing(text: str, start: int) -> int:
    """Index of the ``}`` that closes the ``${`` opened at ``start`` (nested ``${`` aware)."""
    depth = 0
    i = start
    while i < len(text):
        if text.startswith("${", i):
            depth += 1
            i += 2
            continue
        if text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError(f"unterminated ${{ at position {start}")


def expand(text: str, env: Mapping[str, str]) -> str:
    """Expands every ``${...}`` occurrence of ``text`` using ``env``."""
    out: list[str] = []
    i = 0
    while i < len(text):
        if not text.startswith("${", i):
            out.append(text[i])
            i += 1
            continue
        end = _find_closing(text, i)
        body = text[i + 2 : end]
        name, sep, default = body.partition(":-")
        value = env.get(name, "")
        if value:
            out.append(value)
        elif sep:
            out.append(expand(default, env))
        else:
            raise MissingVariableError(f"required variable {name} is not defined")
        i = end + 1
    return "".join(out)


def apply_cloud_block(text: str, env: Mapping[str, str]) -> str:
    """Keeps the cloud block (markers removed) only when the provider key is set; otherwise drops it."""
    keep_cloud = bool(env.get(CLOUD_KEY, ""))
    lines: list[str] = []
    inside = False
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped == CLOUD_BEGIN:
            inside = True
            continue
        if stripped == CLOUD_END:
            inside = False
            continue
        if inside and not keep_cloud:
            continue
        lines.append(line)
    if inside:
        raise ValueError(f"{CLOUD_BEGIN} without {CLOUD_END}")
    return "\n".join(lines)


def render(text: str, env: Mapping[str, str]) -> str:
    return expand(apply_cloud_block(text, env), env)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: render_config.py <template> <output>", file=sys.stderr)
        return 2
    source, target = argv[1], argv[2]
    with open(source, encoding="utf-8") as handle:
        template = handle.read()
    try:
        rendered = render(template, os.environ)
    except (MissingVariableError, ValueError) as error:
        print(f"render_config: {error}", file=sys.stderr)
        return 1
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
