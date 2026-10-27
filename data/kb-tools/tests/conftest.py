"""Builds a minimal valid corpus in a temporary directory so rules can be broken one at a time."""

from pathlib import Path

from kb_tools.constants import CONTROL_FILES, TOPICS
from kb_tools.document import load_corpus

TITLES = {
    "shipping": "Envíos",
    "returns": "Devoluciones",
    "warranty": "Garantías",
    "payments": "Pagos",
    "privacy": "Privacidad",
}


def document_text(title: str, topic: str, doc_type: str = "policy", extra: str = "") -> str:
    return (
        f"---\ntitle: {title}\ntopic: {topic}\ntype: {doc_type}\nlanguage: es\n"
        f"scope: Alcance de {title}.\n---\n# {title}\n\n## Alcance\nTexto de {title}.\n\n"
        f"## Cómo funciona\nPaso uno y paso dos.\n\n## Plazos y costos\n- Plazo: 30 días corridos\n\n"
        f"## Contacto\nsoporte@shoplab.example\n{extra}"
    )


def write_index(kb_dir: Path) -> None:
    documents, _ = load_corpus(kb_dir)
    lines = ["| id | title | topic | type | formats |", "|----|-------|-------|------|---------|"]
    for doc in documents:
        formats = "md, pdf" if doc.has_pdf else "md"
        lines.append(f"| {doc.id} | {doc.title} | {doc.topic} | {doc.type} | {formats} |")
    (kb_dir / "INDEX.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_review(kb_dir: Path, status: str = "reviewed") -> None:
    ids = sorted(path.stem for path in kb_dir.glob("*.md") if path.name not in CONTROL_FILES)
    lines = ["| id | reviewer | date | status |", "|----|----------|------|--------|"]
    for doc_id in ids:
        who, when = ("Revisor de prueba", "2026-10-28") if status == "reviewed" else ("", "")
        lines.append(f"| {doc_id} | {who} | {when} | {status} |")
    (kb_dir / "REVIEW.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def make_corpus(root: Path, per_topic: int = 4, pdf: bool = False, reviewed: bool = True) -> Path:
    """Writes `per_topic` documents per topic (20 in total by default), plus index and review log.

    With `pdf=True` the first document of every topic also gets a PDF (needs kb_tools.pdf).
    """
    kb_dir = root / "kb"
    kb_dir.mkdir(parents=True, exist_ok=True)
    for topic in TOPICS:
        for number in range(1, per_topic + 1):
            doc_type = "faq" if number == per_topic else "policy"
            title = f"{TITLES[topic]} {number}"
            (kb_dir / f"{topic}-{number:02d}-doc-{number}.md").write_text(
                document_text(title, topic, doc_type), encoding="utf-8"
            )
    if pdf:
        from kb_tools.pdf import write_pdf

        documents, _ = load_corpus(kb_dir)
        seen: set[str] = set()
        for doc in documents:
            if doc.topic not in seen:
                seen.add(doc.topic)
                write_pdf(doc, doc.pdf_path)
    write_index(kb_dir)
    write_review(kb_dir, "reviewed" if reviewed else "pending")
    return kb_dir
