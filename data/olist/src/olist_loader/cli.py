"""Command line of the loader (specs/LAB-17 contracts/loader-cli.md)."""

import time
from pathlib import Path
from typing import Annotated

import httpx
import psycopg
import typer

from olist_loader.config import DEFAULT_DATA_DIR, Settings
from olist_loader.download import obtain_dataset
from olist_loader.errors import DatabaseNotReadyError, LoaderError
from olist_loader.load import load_all
from olist_loader.preflight import check_ready
from olist_loader.report import render
from olist_loader.transform import transform

app = typer.Typer(add_completion=False, no_args_is_help=True, help="Loads the Olist dataset into orders and catalog.")


def run_pipeline(
    settings: Settings,
    source: Path | None,
    dry_run: bool,
    skip_download: bool,
    client: httpx.Client | None = None,
) -> str:
    """Obtains the dataset, checks the database, transforms, loads and returns the summary.

    A dry run never connects to the database: it only validates and transforms the files.
    """
    started = time.monotonic()
    folder = obtain_dataset(settings, source, skip_download, client=client)
    if dry_run:
        dataset = transform(folder)
    else:
        try:
            connection = psycopg.connect(**settings.connect_kwargs(), autocommit=True)
        except psycopg.OperationalError as error:
            raise DatabaseNotReadyError(
                "No se puede conectar con la base de datos (revisa POSTGRES_URL, POSTGRES_USER y POSTGRES_PASSWORD "
                "y que la infraestructura local esté levantada)."
            ) from error
        with connection:
            check_ready(connection)
            dataset = transform(folder)
            load_all(connection, dataset)
    return render(dataset, time.monotonic() - started, dry_run=dry_run)


@app.callback()
def main() -> None:
    """Keeps `load` as an explicit sub-command, as the contract documents it."""


@app.command()
def load(
    source: Annotated[
        Path | None, typer.Option("--source", help="ZIP or folder with the CSV files; does not use the network.")
    ] = None,
    data_dir: Annotated[
        Path, typer.Option("--data-dir", help="Cache folder for the archive and the extracted CSV files.")
    ] = DEFAULT_DATA_DIR,
    dry_run: Annotated[bool, typer.Option("--dry-run", help="Validate and report without writing.")] = False,
    skip_download: Annotated[
        bool, typer.Option("--skip-download", help="Fail if the CSV files are not in --data-dir.")
    ] = False,
) -> None:
    """Downloads (or reuses) the dataset and loads it into the orders and catalog schemas."""
    settings = Settings.from_env(data_dir=data_dir)
    try:
        typer.echo(run_pipeline(settings, source, dry_run, skip_download))
    except LoaderError as error:
        typer.echo(f"Error: {error}", err=True)
        raise typer.Exit(code=error.exit_code) from error
