"""The test questions (a TOML data file) and their validation against the corpus (contracts/questions-format.md)."""

import re
import tomllib
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from chunking_lab.corpus import Document
from chunking_lab.errors import InvalidQuestionsError
from chunking_lab.text import normalize

EXPECTED_TOTAL = 20
PER_TOPIC = 4
MIN_LONG_PASSAGES = 5
LONG_PASSAGE = 100
PASSAGE_RANGE = (40, 300)
FIELDS = ("id", "topic", "question", "document", "passage")
_ID = re.compile(r"^Q\d{2}$")

Failure = tuple[str, str, str]


@dataclass(frozen=True)
class Question:
    id: str
    topic: str
    question: str
    document: str
    passage: str


def load_questions(path: Path) -> list[Question]:
    """Reads the `[[question]]` tables; unknown, missing or empty keys are rejected together."""
    with open(path, "rb") as handle:
        entries = tomllib.load(handle).get("question", [])
    failures: list[Failure] = []
    questions: list[Question] = []
    for index, entry in enumerate(entries, start=1):
        label = str(entry.get("id") or f"#{index}")
        unknown = sorted(set(entry) - set(FIELDS))
        if unknown:
            failures.append(("Q-FIELDS", label, f"claves desconocidas: {', '.join(unknown)}"))
        for key in FIELDS:
            if not isinstance(entry.get(key), str) or not entry[key].strip():
                failures.append(("Q-FIELDS", label, f"falta la clave '{key}' o está vacía"))
        if not failures:
            questions.append(Question(**{key: entry[key] for key in FIELDS}))
    if failures:
        raise InvalidQuestionsError(failures)
    return questions


def validate_questions(questions: list[Question], documents: list[Document], expected_total: int = EXPECTED_TOTAL,
                       per_topic: int = PER_TOPIC, min_long: int = MIN_LONG_PASSAGES) -> None:
    """Checks every rule and raises one InvalidQuestionsError listing all the failures."""
    failures: list[Failure] = []
    by_id = {d.id: d for d in documents}
    bodies = {d.id: normalize(d.body) for d in documents}
    topics = sorted({d.topic for d in documents})

    if len(questions) != expected_total:
        failures.append(("Q-COUNT", "-", f"hay {len(questions)} preguntas y deben ser {expected_total}"))
    counts = Counter(q.topic for q in questions)
    for topic in sorted(set(topics) | set(counts)):
        if counts[topic] != per_topic or topic not in topics:
            failures.append(("Q-TOPICS", "-", f"el tema '{topic}' tiene {counts[topic]} preguntas y deben ser "
                                              f"{per_topic}"))

    seen: set[str] = set()
    for q in questions:
        if not _ID.match(q.id):
            failures.append(("Q-ID", q.id, "el id debe tener el formato Q + dos dígitos"))
        elif q.id in seen:
            failures.append(("Q-ID", q.id, "id repetido"))
        seen.add(q.id)

        passage = normalize(q.passage)
        low, high = PASSAGE_RANGE
        if not low <= len(passage) <= high:
            failures.append(("Q-PASSAGE-LENGTH", q.id, f"el pasaje mide {len(passage)} caracteres y debe medir "
                                                       f"entre {low} y {high}"))
        document = by_id.get(q.document)
        if document is None:
            failures.append(("Q-DOC", q.id, f"el documento '{q.document}' no existe en el corpus"))
            continue
        if document.topic != q.topic:
            failures.append(("Q-DOC", q.id, f"el tema '{q.topic}' no coincide con el del documento "
                                            f"('{document.topic}')"))
        if passage not in bodies[document.id]:
            failures.append(("Q-PASSAGE-MISSING", q.id, f"el pasaje no aparece en {q.document}"))
        others = sorted(doc_id for doc_id, body in bodies.items() if doc_id != document.id and passage in body)
        if others:
            failures.append(("Q-PASSAGE-AMBIGUOUS", q.id, f"el pasaje también aparece en {', '.join(others)}"))

    long_passages = sum(1 for q in questions if len(normalize(q.passage)) > LONG_PASSAGE)
    if long_passages < min_long:
        failures.append(("Q-LONG-PASSAGES", "-", f"solo {long_passages} pasajes superan {LONG_PASSAGE} caracteres "
                                                 f"y deben ser al menos {min_long}"))
    if failures:
        raise InvalidQuestionsError(failures)
