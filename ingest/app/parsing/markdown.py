"""Markdown parsing: the document as sections under its headings, keeping tables, lists and code blocks whole."""

import re

from markdown_it import MarkdownIt

from app.domain.models import ParsedDocument, ParsedSection

_FRONT_MATTER = re.compile(r"\A---[ \t]*\r?\n.*?\r?\n---[ \t]*(?:\r?\n|\Z)", re.DOTALL)
_STANDALONE_BLOCKS = frozenset({"fence", "code_block", "html_block"})


def parse_markdown(text: str) -> ParsedDocument:
    text = _FRONT_MATTER.sub("", text, count=1)
    lines = text.splitlines()
    tokens = MarkdownIt("commonmark").enable("table").parse(text)

    sections: list[ParsedSection] = []
    stack: list[tuple[int, str]] = []
    path: tuple[str, ...] = ()
    blocks: list[str] = []
    title: str | None = None
    started = False

    def flush() -> None:
        if blocks or path:
            sections.append(ParsedSection(path=path, blocks=tuple(blocks)))

    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.type == "heading_open":
            heading = tokens[index + 1].content.strip()
            index += 3
            if not heading:
                continue
            if started or blocks:
                flush()
            started = True
            level = int(token.tag[1])
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, heading))
            path = tuple(name for _, name in stack)
            blocks = []
            if level == 1 and title is None:
                title = heading
        elif token.level == 0 and token.nesting == 1:
            block = _source(lines, token.map)
            if block:
                blocks.append(block)
            index = _after_close(tokens, index)
        elif token.level == 0 and token.nesting == 0 and token.type in _STANDALONE_BLOCKS:
            block = _source(lines, token.map)
            if block:
                blocks.append(block)
            index += 1
        else:
            index += 1
    flush()
    return ParsedDocument(title=title, sections=tuple(sections))


def _source(lines: list[str], line_map: list[int] | None) -> str:
    if not line_map:
        return ""
    return "\n".join(lines[line_map[0]:line_map[1]]).strip()


def _after_close(tokens: list, opening: int) -> int:
    """Index right after the token that closes the block opened at `opening`."""
    index = opening + 1
    while index < len(tokens) and not (tokens[index].nesting == -1 and tokens[index].level == 0):
        index += 1
    return index + 1
