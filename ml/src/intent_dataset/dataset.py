"""Reading and writing ``intents.jsonl``: one JSON object per line, fixed key order, sorted by id, UTF-8 without BOM."""

import json
import os
import re
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

from intent_dataset.constants import EXAMPLE_KEYS
from intent_dataset.errors import InvalidDatasetError

_ID_NUMBER = re.compile(r"I(\d+)")


@dataclass(frozen=True)
class Example:
    """One labeled customer message. The text is already masked (see data-model.md for every rule)."""

    id: str
    text: str
    label: str
    language: str
    source: str
    group_id: str
    parent_id: str | None
    golden_id: str | None
    split: str | None
    reviewed: bool


def load_examples(path: Path) -> list[Example]:
    """Reads the dataset. Blank lines are tolerated; unknown or missing keys and broken JSON name their line."""
    if not path.is_file():
        raise InvalidDatasetError(f"No existe {path.name}: construye el dataset (ver ml/README.md) o revisa la ruta.")
    examples: list[Example] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as error:
            raise InvalidDatasetError(f"{path.name}, línea {number}: JSON inválido ({error.msg}).") from error
        if not isinstance(raw, dict):
            raise InvalidDatasetError(f"{path.name}, línea {number}: se esperaba un objeto JSON.")
        unknown = sorted(set(raw) - set(EXAMPLE_KEYS))
        if unknown:
            raise InvalidDatasetError(f"{path.name}, línea {number}: claves desconocidas: {', '.join(unknown)}.")
        missing = [key for key in EXAMPLE_KEYS if key not in raw]
        if missing:
            raise InvalidDatasetError(f"{path.name}, línea {number}: falta la clave {', '.join(missing)}.")
        examples.append(Example(**raw))
    return examples


def dump_examples(examples: list[Example], path: Path) -> None:
    """Writes the dataset atomically (temporary file in the same folder, then rename), sorted by id."""
    ordered = sorted(examples, key=lambda example: example.id)
    lines = []
    for example in ordered:
        values = asdict(example)
        lines.append(json.dumps({key: values[key] for key in EXAMPLE_KEYS}, ensure_ascii=False))
    payload = ("\n".join(lines) + "\n").encode("utf-8") if lines else b""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".", suffix=".tmp")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(payload)
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def next_id(examples: list[Example]) -> str:
    """The next ``I`` + 4 digits id; ids are never reused or renumbered."""
    highest = 0
    for example in examples:
        match = _ID_NUMBER.fullmatch(example.id)
        if match:
            highest = max(highest, int(match.group(1)))
    return f"I{highest + 1:04d}"
