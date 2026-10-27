"""Validation rules of the corpus (FR-013); codes are documented in contracts/kb-tools-cli.md."""

import re
from collections import Counter
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from pypdf.errors import PyPdfError

from kb_tools.constants import (
    ALLOWED_EMAIL_DOMAIN,
    DOC_TYPES,
    FILENAME_PATTERN,
    FRONT_MATTER_FIELDS,
    LANGUAGE,
    MAX_DOCS,
    MIN_DOCS,
    MIN_PER_TOPIC,
    TOPICS,
)
from kb_tools.document import Document, load_corpus, read_table
from kb_tools.pdf import markdown_signature, pdf_signature

EMAIL_PATTERN = re.compile(r"[\w.+-]+@([\w-]+(?:\.[\w-]+)+)")
PHONE_PATTERN = re.compile(r"(?<![\w$,.])\+?\(?\d[\d\s().-]{7,}\d")
CPF_PATTERN = re.compile(r"\d{3}\.\d{3}\.\d{3}-\d{2}")
AMOUNT_PATTERN = re.compile(r"R\$\s*[\d.,]+")
MIN_PHONE_DIGITS = 9
REVIEW_STATUSES = ("pending", "reviewed")


@dataclass(frozen=True)
class Issue:
    code: str
    path: Path
    message: str

    def format(self) -> str:
        return f"{self.code} {self.path.as_posix()}: {self.message}"


@dataclass(frozen=True)
class Options:
    min_docs: int = MIN_DOCS
    max_docs: int = MAX_DOCS
    min_per_topic: int = MIN_PER_TOPIC
    require_reviewed: bool = False


def validate(kb_dir: Path, options: Options | None = None) -> list[Issue]:
    options = options or Options()
    documents, read_errors = load_corpus(kb_dir)
    unreadable = {error.path for error in read_errors}
    issues = [Issue("KB005", error.path, error.message) for error in read_errors]
    issues += check_counts(kb_dir, documents, options)
    issues += check_names(documents)
    issues += check_front_matter([doc for doc in documents if doc.path not in unreadable])
    issues += check_characters(documents)
    issues += check_pdfs(kb_dir, documents)
    issues += check_personal_data(documents)
    issues += check_review(kb_dir, documents, options)
    issues += check_index(kb_dir, documents)
    return issues


def check_counts(kb_dir: Path, documents: list[Document], options: Options) -> list[Issue]:
    issues: list[Issue] = []
    total = len({doc.id for doc in documents})
    if not options.min_docs <= total <= options.max_docs:
        issues.append(
            Issue("KB001", kb_dir, f"hay {total} documentos; debe haber entre {options.min_docs} y {options.max_docs}")
        )
    per_topic = Counter(doc.id.split("-")[0] for doc in documents)
    for topic in TOPICS:
        if per_topic[topic] < options.min_per_topic:
            issues.append(
                Issue(
                    "KB002",
                    kb_dir,
                    f"el tema '{topic}' tiene {per_topic[topic]} documentos; el mínimo es {options.min_per_topic}",
                )
            )
    return issues


def check_names(documents: list[Document]) -> list[Issue]:
    issues: list[Issue] = []
    for doc in documents:
        if not re.match(FILENAME_PATTERN, doc.id):
            issues.append(
                Issue("KB004", doc.path, "nombre inválido; usa '<tema>-NN-<slug>.md' (tema en inglés, slug kebab-case)")
            )
        elif doc.topic in TOPICS and doc.topic != doc.id.split("-")[0]:
            issues.append(
                Issue("KB004", doc.path, f"el tema '{doc.topic}' del front matter no coincide con el prefijo del nombre")
            )
    return issues


def check_front_matter(documents: list[Document]) -> list[Issue]:
    issues: list[Issue] = []
    for doc in documents:
        missing = [name for name in FRONT_MATTER_FIELDS if not doc.meta_text(name)]
        if missing:
            issues.append(Issue("KB005", doc.path, f"faltan campos del front matter: {', '.join(missing)}"))
            continue
        if doc.topic not in TOPICS:
            issues.append(Issue("KB005", doc.path, f"tema '{doc.topic}' desconocido; usa uno de {', '.join(TOPICS)}"))
        if doc.type not in DOC_TYPES:
            issues.append(Issue("KB005", doc.path, f"tipo '{doc.type}' desconocido; usa 'policy' o 'faq'"))
        if doc.meta_text("language") != LANGUAGE:
            issues.append(Issue("KB005", doc.path, f"el idioma debe ser '{LANGUAGE}'"))
    titles = Counter(doc.title for doc in documents if doc.title)
    for doc in documents:
        if doc.title and titles[doc.title] > 1:
            issues.append(Issue("KB005", doc.path, f"título duplicado: '{doc.title}'"))
    return issues


def check_characters(documents: list[Document]) -> list[Issue]:
    issues: list[Issue] = []
    for doc in documents:
        for number, line in enumerate(doc.raw.splitlines(), start=1):
            bad = sorted({char for char in line if ord(char) > 255})
            if bad:
                shown = ", ".join(f"U+{ord(char):04X}" for char in bad)
                issues.append(
                    Issue("KB010", doc.path, f"línea {number}: caracteres fuera de latin-1 ({shown}); usa ASCII")
                )
    return issues


def check_pdfs(kb_dir: Path, documents: list[Document]) -> list[Issue]:
    issues: list[Issue] = []
    topics_with_pdf = {doc.id.split("-")[0] for doc in documents if doc.has_pdf}
    for topic in TOPICS:
        if topic not in topics_with_pdf:
            issues.append(Issue("KB003", kb_dir, f"el tema '{topic}' no tiene ningún PDF; genera uno con 'build-pdf'"))
    for doc in documents:
        if doc.has_pdf:
            issues += check_pdf(doc)
    return issues


def check_pdf(doc: Document) -> list[Issue]:
    try:
        actual = pdf_signature(doc.pdf_path.read_bytes())
    except (PyPdfError, ValueError, OSError) as error:
        return [Issue("KB008", doc.pdf_path, f"el PDF no se puede leer ({error}); regéneralo con 'build-pdf'")]
    if not actual:
        return [Issue("KB008", doc.pdf_path, "el PDF no tiene texto extraíble; regéneralo con 'build-pdf'")]
    expected = markdown_signature(doc)
    if actual != expected:
        differing = sorted(((expected - actual) + (actual - expected)).keys())
        return [
            Issue(
                "KB008",
                doc.pdf_path,
                f"el texto del PDF difiere del Markdown (difieren: {', '.join(differing)}); regéneralo con 'build-pdf'",
            )
        ]
    return []


def check_personal_data(documents: list[Document]) -> list[Issue]:
    issues: list[Issue] = []
    for doc in documents:
        for number, line in enumerate(doc.raw.splitlines(), start=1):
            where = f"línea {number}"
            for match in EMAIL_PATTERN.finditer(line):
                if match.group(1).lower() != ALLOWED_EMAIL_DOMAIN:
                    issues.append(Issue("KB009", doc.path, f"{where}: correo fuera de @{ALLOWED_EMAIL_DOMAIN}"))
            without_amounts = AMOUNT_PATTERN.sub(" ", line)
            if CPF_PATTERN.search(without_amounts):
                issues.append(Issue("KB009", doc.path, f"{where}: documento de identidad (CPF)"))
            elif any(
                sum(char.isdigit() for char in match.group()) >= MIN_PHONE_DIGITS
                for match in PHONE_PATTERN.finditer(without_amounts)
            ):
                issues.append(Issue("KB009", doc.path, f"{where}: número de teléfono; no publiques teléfonos"))
    return issues


def check_review(kb_dir: Path, documents: list[Document], options: Options) -> list[Issue]:
    review_path = kb_dir / "REVIEW.md"
    rows = read_table(review_path)
    if rows is None:
        return [Issue("KB007", review_path, "no existe REVIEW.md; crea la tabla de revisión")]
    issues: list[Issue] = []
    by_id = {row.get("id", ""): row for row in rows}
    doc_ids = {doc.id for doc in documents}
    for doc_id in sorted(doc_ids - by_id.keys()):
        issues.append(Issue("KB007", review_path, f"falta el documento '{doc_id}' (añádelo a la tabla)"))
    for doc_id in sorted(by_id.keys() - doc_ids):
        issues.append(Issue("KB007", review_path, f"'{doc_id}' no existe en el directorio (quítalo de la tabla)"))
    for doc_id in sorted(doc_ids & by_id.keys()):
        row = by_id[doc_id]
        status = row.get("status", "")
        if status not in REVIEW_STATUSES:
            issues.append(Issue("KB007", review_path, f"'{doc_id}': estado '{status}' desconocido (pending o reviewed)"))
        elif status == "reviewed":
            issues += check_reviewed_row(review_path, doc_id, row)
        elif options.require_reviewed:
            issues.append(Issue("KB011", review_path, f"'{doc_id}' sigue pendiente de revisión manual"))
    return issues


def check_reviewed_row(review_path: Path, doc_id: str, row: dict[str, str]) -> list[Issue]:
    issues: list[Issue] = []
    if not row.get("reviewer", ""):
        issues.append(Issue("KB007", review_path, f"'{doc_id}' está revisado pero no indica el revisor"))
    try:
        date.fromisoformat(row.get("date", ""))
    except ValueError:
        issues.append(Issue("KB007", review_path, f"'{doc_id}' está revisado pero su fecha no es YYYY-MM-DD"))
    return issues


def check_index(kb_dir: Path, documents: list[Document]) -> list[Issue]:
    index_path = kb_dir / "INDEX.md"
    rows = read_table(index_path)
    if rows is None:
        return [Issue("KB006", index_path, "no existe INDEX.md; crea la tabla del corpus")]
    issues: list[Issue] = []
    by_id = {row.get("id", ""): row for row in rows}
    docs = {doc.id: doc for doc in documents}
    for doc_id in sorted(docs.keys() - by_id.keys()):
        issues.append(Issue("KB006", index_path, f"falta el documento '{doc_id}' (añádelo a la tabla)"))
    for doc_id in sorted(by_id.keys() - docs.keys()):
        issues.append(Issue("KB006", index_path, f"'{doc_id}' no existe en el directorio (quítalo de la tabla)"))
    for doc_id in sorted(docs.keys() & by_id.keys()):
        doc, row = docs[doc_id], by_id[doc_id]
        expected = {"title": doc.title, "topic": doc.topic, "type": doc.type, "formats": formats_of(doc)}
        for column, value in expected.items():
            if row.get(column, "") != value:
                issues.append(
                    Issue(
                        "KB006",
                        index_path,
                        f"'{doc_id}': {column} es '{row.get(column, '')}' y debería ser '{value}'",
                    )
                )
    return issues


def formats_of(doc: Document) -> str:
    return "md, pdf" if doc.has_pdf else "md"
