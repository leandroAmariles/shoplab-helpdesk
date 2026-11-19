"""The Markdown documents of the knowledge base (`data/kb`)."""

import re
from dataclasses import dataclass
from pathlib import Path

from chunking_lab.errors import CorpusError

EXCLUDED_FILES = frozenset({"INDEX.md", "REVIEW.md"})
# Same criterion as the ingest parser: a leading `---` ... `---` block.
_FRONT_MATTER = re.compile(r"\A---[ \t]*\r?\n.*?\r?\n---[ \t]*(?:\r?\n|\Z)", re.DOTALL)


@dataclass(frozen=True)
class Document:
    """`raw` is the file as is; `body` is what fixed-size and semantic chunking work on (no front matter)."""

    id: str
    topic: str
    raw: str
    body: str


def strip_front_matter(text: str) -> str:
    return _FRONT_MATTER.sub("", text, count=1)


def load_corpus(kb_dir: Path) -> list[Document]:
    """Every Markdown document of `kb_dir` except INDEX.md and REVIEW.md, ordered by id."""
    paths = sorted(p for p in Path(kb_dir).glob("*.md") if p.name not in EXCLUDED_FILES)
    if not paths:
        raise CorpusError(f"No hay documentos Markdown en {kb_dir}")
    documents = []
    for path in paths:
        raw = path.read_text(encoding="utf-8")
        document_id = path.stem
        documents.append(Document(id=document_id, topic=document_id.split("-", 1)[0], raw=raw,
                                  body=strip_front_matter(raw)))
    return documents
