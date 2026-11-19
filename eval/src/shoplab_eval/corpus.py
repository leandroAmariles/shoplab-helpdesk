"""Reads the documents of the knowledge base (data/kb) that the golden cases point to."""

from dataclasses import dataclass
from pathlib import Path

import yaml

from shoplab_eval.constants import CONTROL_FILES, DEFAULT_KB_DIR


@dataclass(frozen=True)
class CorpusDocument:
    """A document of the knowledge base: its id (file name without ``.md``), topic, title and normalized body.

    The title is the one the chat shows in its ``sources`` event (LAB-23): it is how a delivered fragment is traced
    back to the document id of the golden dataset (LAB-29).
    """

    id: str
    topic: str
    title: str
    text: str


def normalize_text(text: str) -> str:
    """Case-folds, turns every character that is not a letter or a digit into a space and collapses the spaces.

    Accents and eñes are kept, so «días» and «dias» stay different words. Used to compare questions with each other
    and with the documents.
    """
    folded = text.casefold()
    cleaned = "".join(char if char.isalnum() else " " for char in folded)
    return " ".join(cleaned.split())


def load_corpus(kb_dir: Path = DEFAULT_KB_DIR) -> dict[str, CorpusDocument]:
    """Loads every ``*.md`` of ``kb_dir`` except the control files, ordered by id."""
    if not kb_dir.is_dir():
        raise FileNotFoundError(f"No existe el directorio del corpus: {kb_dir}")
    documents: dict[str, CorpusDocument] = {}
    for path in sorted(kb_dir.glob("*.md")):
        if path.name in CONTROL_FILES:
            continue
        documents[path.stem] = _read_document(path)
    return documents


def _read_document(path: Path) -> CorpusDocument:
    raw = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    if not raw.startswith("---\n"):
        raise ValueError(f"{path.name}: el documento no tiene front matter")
    end = raw.find("\n---\n", 4)
    if end == -1:
        raise ValueError(f"{path.name}: el front matter no se cierra con '---'")
    try:
        meta = yaml.safe_load(raw[4:end])
    except yaml.YAMLError as error:
        raise ValueError(f"{path.name}: el front matter no es YAML válido ({error})") from error
    topic = meta.get("topic") if isinstance(meta, dict) else None
    if not isinstance(topic, str) or not topic.strip():
        raise ValueError(f"{path.name}: el front matter no tiene 'topic'")
    title = meta.get("title") if isinstance(meta, dict) else None
    if not isinstance(title, str) or not title.strip():
        raise ValueError(f"{path.name}: el front matter no tiene 'title'")
    return CorpusDocument(id=path.stem, topic=topic.strip(), title=title.strip(), text=normalize_text(raw[end + 5 :]))


def title_index(corpus: dict[str, CorpusDocument]) -> dict[str, str]:
    """Maps each document title to its id. A title shared by two documents is an error: a fragment is not traceable."""
    index: dict[str, str] = {}
    for document in corpus.values():
        other = index.get(document.title)
        if other is not None:
            raise ValueError(
                f"El título {document.title!r} lo comparten {other} y {document.id}: un fragmento entregado por el chat "
                "no se podría asociar a un único documento"
            )
        index[document.title] = document.id
    return index
