"""Shared fixtures: a disposable Postgres with the real migrations of orders and catalog applied."""

import dataclasses
import re
import uuid
from collections.abc import Callable, Iterator
from pathlib import Path

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

from olist_loader.config import Settings

REPO_ROOT = Path(__file__).resolve().parents[3]
MIGRATIONS = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
POSTGRES_IMAGE = "pgvector/pgvector:0.8.7-pg16"  # same image as infra/docker-compose.yml
SCHEMAS = ("orders", "catalog")


def migration_scripts(module: str) -> list[Path]:
    """The V<n>__*.sql scripts of a module in version order, as Flyway would apply them."""
    scripts = [path for path in (MIGRATIONS / module).glob("V*__*.sql") if re.match(r"V\d+__", path.name)]
    return sorted(scripts, key=lambda path: int(re.match(r"V(\d+)__", path.name).group(1)))


def apply_migrations(conn: psycopg.Connection, schemas: tuple[str, ...] = SCHEMAS) -> None:
    """Creates each schema and runs the module's scripts inside it (the default schema Flyway would set)."""
    for schema in schemas:
        conn.execute(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')
        conn.execute(f'SET search_path TO "{schema}"')
        for script in migration_scripts(schema):
            conn.execute(script.read_text(encoding="utf-8"))
    conn.execute("SET search_path TO public")
    conn.commit()


@pytest.fixture(scope="session")
def fixtures_dir() -> Path:
    return FIXTURES


@pytest.fixture(scope="session")
def _container() -> Iterator[PostgresContainer]:
    with PostgresContainer(POSTGRES_IMAGE, driver=None) as container:
        yield container


@pytest.fixture(scope="session")
def settings(_container: PostgresContainer) -> Settings:
    host = _container.get_container_host_ip()
    port = _container.get_exposed_port(5432)
    return Settings(
        postgres_url=f"postgresql://{host}:{port}/{_container.dbname}",
        postgres_user=_container.username,
        postgres_password=_container.password,
    )


@pytest.fixture(scope="session")
def _migrated(settings: Settings) -> None:
    with psycopg.connect(**settings.connect_kwargs()) as conn:
        apply_migrations(conn)


@pytest.fixture
def conn(settings: Settings, _migrated: None) -> Iterator[psycopg.Connection]:
    """A connection to a database with both schemas migrated and every table empty."""
    with psycopg.connect(**settings.connect_kwargs(), autocommit=True) as connection:
        tables = connection.execute(
            "SELECT format('%%I.%%I', table_schema, table_name) FROM information_schema.tables "
            "WHERE table_schema = ANY(%s) AND table_name <> 'flyway_schema_history'",
            (list(SCHEMAS),),
        ).fetchall()
        if tables:
            connection.execute("TRUNCATE " + ", ".join(row[0] for row in tables) + " RESTART IDENTITY CASCADE")
        yield connection


@pytest.fixture
def new_database(settings: Settings) -> Callable[..., Settings]:
    """Creates an extra database in the same server; `migrate=False` leaves it with no schemas at all."""

    def create(migrate: bool = True) -> Settings:
        name = f"olist_{uuid.uuid4().hex[:12]}"
        with psycopg.connect(**settings.connect_kwargs(), autocommit=True) as admin:
            admin.execute(f'CREATE DATABASE "{name}"')
        fresh = dataclasses.replace(settings, postgres_url=settings.postgres_url.rsplit("/", 1)[0] + "/" + name)
        if migrate:
            with psycopg.connect(**fresh.connect_kwargs()) as connection:
                apply_migrations(connection)
        return fresh

    return create
