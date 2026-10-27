"""Reads the Markdown documents of the corpus and the tables of its control files."""

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from kb_tools.constants import CONTROL_FILES


@dataclass(frozen=True)
class ReadError:
    path: Path
    message: str


@dataclass(frozen=True)
class Document:
    id: str
    path: Path
    meta: dict[str, object]
    body: str
    has_pdf: bool = False
    raw: str = field(default="", repr=False)

    def meta_text(self, name: str) -> str:
        value = self.meta.get(name)
        return value.strip() if isinstance(value, str) else ""

    @property
    def title(self) -> str:
        return self.meta_text("title")

    @property
    def topic(self) -> str:
        return self.meta_text("topic")

    @property
    def type(self) -> str:
        return self.meta_text("type")

    @property
    def pdf_path(self) -> Path:
        return self.path.with_suffix(".pdf")


def split_front_matter(text: str) -> tuple[dict[str, object], str]:
    """Returns the front matter and the body; raises ValueError when the front matter is missing or invalid."""
    normalized = text.replace("\r\n", "\n")
    if not normalized.startswith("---\n"):
        raise ValueError("falta el front matter (el archivo debe empezar con '---')")
    end = normalized.find("\n---\n", 4)
    if end == -1:
        raise ValueError("el front matter no está cerrado con '---'")
    try:
        meta = yaml.safe_load(normalized[4:end]) or {}
    except yaml.YAMLError as error:
        raise ValueError(f"front matter con YAML inválido: {error}") from error
    if not isinstance(meta, dict):
        raise ValueError("el front matter debe ser un mapa clave: valor")
    return meta, normalized[end + 5 :]


def load_corpus(kb_dir: Path) -> tuple[list[Document], list[ReadError]]:
    """Reads every document of the corpus; unreadable ones are reported instead of raised."""
    documents: list[Document] = []
    errors: list[ReadError] = []
    for path in sorted(kb_dir.glob("*.md")):
        if path.name in CONTROL_FILES:
            continue
        raw = path.read_text(encoding="utf-8")
        try:
            meta, body = split_front_matter(raw)
        except ValueError as error:
            errors.append(ReadError(path, str(error)))
            meta, body = {}, raw
        documents.append(Document(path.stem, path, meta, body, path.with_suffix(".pdf").exists(), raw))
    return documents, errors


def read_table(path: Path) -> list[dict[str, str]] | None:
    """Parses the first Markdown table of the file into rows keyed by header; None when the file does not exist."""
    if not path.exists():
        return None
    rows: list[list[str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("|"):
            rows.append([cell.strip() for cell in stripped.strip("|").split("|")])
        elif rows:
            break
    if len(rows) < 2:
        return []
    header = rows[0]
    return [dict(zip(header, row, strict=False)) for row in rows[2:]]
