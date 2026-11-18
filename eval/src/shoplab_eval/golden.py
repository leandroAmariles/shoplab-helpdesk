"""The golden cases and their reading from ``eval/golden.jsonl``."""

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

from shoplab_eval.constants import DEFAULT_GOLDEN_PATH, DEFAULT_KB_DIR, OFF_TOPIC, REQUIRED_KEYS
from shoplab_eval.corpus import CorpusDocument, load_corpus
from shoplab_eval.errors import InvalidGoldenError, Issue

if TYPE_CHECKING:
    from shoplab_eval.summary import GoldenSummary

_BOM = "﻿"


@dataclass(frozen=True)
class GoldenCase:
    """A reference question with its expected answer.

    ``expected_documents`` is empty when the knowledge base cannot answer the question. ``line`` is where the case is
    in the file; it only orders reports and is not part of the case.
    """

    id: str
    question: str
    reference_answer: str
    expected_documents: tuple[str, ...]
    category: str
    line: int = field(default=0, compare=False, repr=False)

    @property
    def answerable(self) -> bool:
        return bool(self.expected_documents)

    @property
    def kind(self) -> str:
        if self.answerable:
            return "answered"
        return "off_topic" if self.category == OFF_TOPIC else "unanswered_topic"

    @property
    def is_multi_document(self) -> bool:
        return len(self.expected_documents) == 2


@dataclass(frozen=True)
class ParsedGolden:
    """What could be read from the file: the cases with a valid structure and the problems found on the way."""

    cases: list[GoldenCase]
    issues: list[Issue]


def parse_golden(path: Path = DEFAULT_GOLDEN_PATH) -> ParsedGolden:
    """Reads the file line by line. Only the structure is checked here; the other rules belong to the validation."""
    cases: list[GoldenCase] = []
    issues: list[Issue] = []
    try:
        text = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError as error:
        message = f"el archivo no está en UTF-8 ({error.reason} en el byte {error.start})"
        return ParsedGolden([], [Issue("G-JSON", "LINE 1", message, line=1)])
    if text.startswith(_BOM):
        text = text[1:]
        message = "el archivo empieza con BOM; guárdalo como UTF-8 sin BOM"
        issues.append(Issue("G-JSON", "LINE 1", message, line=1))
    for number, line in enumerate(text.split("\n"), start=1):
        if not line.strip():
            continue
        case = _parse_line(number, line.rstrip("\r"), issues)
        if case is not None:
            cases.append(case)
    return ParsedGolden(cases, issues)


def load_golden(path: Path = DEFAULT_GOLDEN_PATH, kb_dir: Path = DEFAULT_KB_DIR) -> list[GoldenCase]:
    """Reads and validates the dataset; raises ``InvalidGoldenError`` with **every** failure if anything is wrong."""
    return _validated(path, kb_dir)[0]


def validate_golden(path: Path = DEFAULT_GOLDEN_PATH, kb_dir: Path = DEFAULT_KB_DIR) -> "GoldenSummary":
    """Like ``load_golden`` but returns the summary of the dataset instead of the cases."""
    from shoplab_eval.summary import summarize

    cases, corpus = _validated(path, kb_dir)
    return summarize(cases, corpus)


def _validated(path: Path, kb_dir: Path) -> tuple[list[GoldenCase], dict[str, CorpusDocument]]:
    # Imported here because the validation itself needs GoldenCase from this module.
    from shoplab_eval.validation import validate_cases

    parsed = parse_golden(path)
    corpus = load_corpus(kb_dir)
    issues = validate_cases(parsed.cases, parsed.issues, corpus)
    if issues:
        raise InvalidGoldenError(issues)
    return parsed.cases, corpus


def _parse_line(number: int, line: str, issues: list[Issue]) -> GoldenCase | None:
    try:
        data = json.loads(line)
    except json.JSONDecodeError as error:
        issues.append(Issue("G-JSON", f"LINE {number}", f"la línea no es JSON válido ({error.msg})", line=number))
        return None
    if not isinstance(data, dict):
        issues.append(Issue("G-FIELDS", f"LINE {number}", "la línea debe ser un objeto JSON", line=number))
        return None
    case_id = data.get("id") if isinstance(data.get("id"), str) and data["id"] else f"LINE {number}"
    problems = _structure_problems(data)
    if problems:
        issues.append(Issue("G-FIELDS", case_id, "; ".join(problems), line=number))
        return None
    return GoldenCase(
        id=data["id"],
        question=data["question"],
        reference_answer=data["reference_answer"],
        expected_documents=tuple(data["expected_documents"]),
        category=data["category"],
        line=number,
    )


def _structure_problems(data: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    missing = [key for key in REQUIRED_KEYS if key not in data]
    if missing:
        problems.append("faltan las claves " + ", ".join(missing))
    unknown = sorted(set(data) - set(REQUIRED_KEYS))
    if unknown:
        problems.append("claves desconocidas " + ", ".join(unknown))
    for key in ("id", "question", "reference_answer", "category"):
        if key in data and not isinstance(data[key], str):
            problems.append(f"'{key}' debe ser una cadena")
    documents = data.get("expected_documents")
    if "expected_documents" in data and not (
        isinstance(documents, list) and all(isinstance(item, str) for item in documents)
    ):
        problems.append("'expected_documents' debe ser una lista de cadenas")
    return problems
