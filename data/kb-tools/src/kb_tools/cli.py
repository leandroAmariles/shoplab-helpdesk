"""Command line of the knowledge-base tools."""

from pathlib import Path
from typing import Annotated

import typer

from kb_tools.constants import MAX_DOCS, MIN_DOCS, MIN_PER_TOPIC, PDF_DOCUMENT_IDS
from kb_tools.document import load_corpus
from kb_tools.pdf import render_pdf, write_pdf
from kb_tools.validate import Options, validate

app = typer.Typer(help="Builds the PDFs and validates the knowledge-base corpus.", no_args_is_help=True)

DEFAULT_KB_DIR = Path(__file__).resolve().parents[3] / "kb"
KbDirOption = Annotated[Path, typer.Option("--kb-dir", help="Directory of the corpus.")]


@app.callback()
def main() -> None:
    """Tools for the corpus in data/kb."""


@app.command("build-pdf")
def build_pdf_command(
    kb_dir: KbDirOption = DEFAULT_KB_DIR,
    only: Annotated[list[str] | None, typer.Option("--only", help="Document id to build (repeatable).")] = None,
    check: Annotated[bool, typer.Option(help="Do not write; fail if a PDF is not up to date.")] = False,
) -> None:
    documents, _ = load_corpus(kb_dir)
    by_id = {document.id: document for document in documents}
    selected = only or list(PDF_DOCUMENT_IDS)
    unknown = [doc_id for doc_id in selected if doc_id not in by_id]
    if unknown:
        typer.echo(f"No existe el documento: {', '.join(unknown)}")
        raise typer.Exit(code=2)
    outdated = 0
    for doc_id in selected:
        document = by_id[doc_id]
        if check:
            current = document.pdf_path.exists() and document.pdf_path.read_bytes() == render_pdf(document)
            outdated += 0 if current else 1
            typer.echo(f"{'unchanged' if current else 'outdated'} {document.pdf_path.name}")
        else:
            changed = write_pdf(document, document.pdf_path)
            typer.echo(f"{'ok' if changed else 'unchanged'} {document.pdf_path.name}")
    typer.echo(f"{len(selected)} PDF, {outdated} desactualizados" if check else f"{len(selected)} PDF")
    raise typer.Exit(code=1 if outdated else 0)


@app.command("validate")
def validate_command(
    kb_dir: KbDirOption = DEFAULT_KB_DIR,
    require_reviewed: Annotated[bool, typer.Option(help="Require every document to be reviewed.")] = False,
    min_docs: Annotated[int, typer.Option(help="Minimum number of documents.")] = MIN_DOCS,
    max_docs: Annotated[int, typer.Option(help="Maximum number of documents.")] = MAX_DOCS,
    min_per_topic: Annotated[int, typer.Option(help="Minimum number of documents per topic.")] = MIN_PER_TOPIC,
) -> None:
    options = Options(min_docs, max_docs, min_per_topic, require_reviewed)
    issues = validate(kb_dir, options)
    for issue in issues:
        typer.echo(issue.format())
    documents, _ = load_corpus(kb_dir)
    pdfs = sum(1 for document in documents if document.has_pdf)
    typer.echo(f"{len(documents)} documentos, {pdfs} PDF, {len(issues)} errores")
    raise typer.Exit(code=1 if issues else 0)
