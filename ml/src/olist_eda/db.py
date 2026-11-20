"""The only module that talks to Postgres. Everything else works on DataFrames.

The connection is read-only at the session level: an accidental INSERT, UPDATE or DDL fails in the database, so the
analysis cannot modify the orders and catalog schemas (FR-025).
"""

from collections.abc import Callable
from typing import Any
from urllib.parse import urlparse

import pandas as pd
import psycopg

from olist_eda.config import AnalysisSettings
from olist_eda.errors import PreflightError

CONNECT_TIMEOUT_SECONDS = 5
READ_ONLY_OPTIONS = "-c default_transaction_read_only=on"

# Eight tables of LAB-17, in the order the notebook presents them: (schema, table).
EXPECTED_TABLES: tuple[tuple[str, str], ...] = (
    ("orders", "customers"),
    ("orders", "orders"),
    ("orders", "order_items"),
    ("orders", "order_payments"),
    ("orders", "order_reviews"),
    ("catalog", "product_categories"),
    ("catalog", "products"),
    ("catalog", "sellers"),
)

# Postgres type OIDs, as psycopg reports them in cursor.description.
OID_INT8 = 20
OID_INT2 = 21
OID_INT4 = 23
OID_NUMERIC = 1700
OID_TIMESTAMP = 1114
OID_TIMESTAMPTZ = 1184
_INTEGER_OIDS = frozenset({OID_INT2, OID_INT4, OID_INT8})
_TIMESTAMP_OIDS = frozenset({OID_TIMESTAMP, OID_TIMESTAMPTZ})

Connector = Callable[[AnalysisSettings], Any]


def qualified(schema: str, table: str) -> str:
    return f"{schema}.{table}"


def connect(settings: AnalysisSettings) -> psycopg.Connection:
    """Opens a read-only connection; without a timeout an unreachable host hangs the notebook for minutes."""
    return psycopg.connect(
        conninfo=settings.postgres_url,
        user=settings.postgres_user,
        password=settings.postgres_password,
        connect_timeout=CONNECT_TIMEOUT_SECONDS,
        options=READ_ONLY_OPTIONS,
    )


def _location(settings: AnalysisSettings) -> str:
    parsed = urlparse(settings.postgres_url)
    return f"{parsed.hostname or 'localhost'}:{parsed.port or 5432}"


def preflight(settings: AnalysisSettings, *, connector: Connector = connect) -> None:
    """Stops, before computing anything, when the database cannot be analysed; each message says how to fix it."""
    try:
        connection = connector(settings)
    except psycopg.Error as error:
        reason = str(error).strip().splitlines()[0] if str(error).strip() else type(error).__name__
        if "authentication failed" in reason:
            raise PreflightError(
                f"La base en {_location(settings)} está arriba pero rechazó el usuario o la contraseña ({reason}). "
                "Revisa POSTGRES_USER y POSTGRES_PASSWORD: deben ser los de la base local."
            ) from error
        raise PreflightError(
            f"No se pudo conectar a {_location(settings)} ({reason}). "
            "Arranca la base local: docker compose -f infra/docker-compose.yml up -d --wait."
        ) from error
    try:
        _check_tables(connection)
    finally:
        connection.close()


def _check_tables(connection: Any) -> None:
    schemas = sorted({schema for schema, _ in EXPECTED_TABLES})
    present = {
        (schema, table)
        for schema, table in connection.execute(
            "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema = ANY(%s)",
            (schemas,),
        ).fetchall()
    }
    problems = []
    for schema in schemas:
        missing = [table for expected, table in EXPECTED_TABLES if expected == schema and (schema, table) not in present]
        if missing:
            problems.append(f"Faltan tablas en {schema}: {', '.join(missing)}")
    if problems:
        raise PreflightError(
            "; ".join(problems) + ". Arranca el backend para aplicar las migraciones."
        )
    for schema, table in EXPECTED_TABLES:
        has_rows = connection.execute(f'SELECT EXISTS (SELECT 1 FROM "{schema}"."{table}")').fetchone()[0]
        if not has_rows:
            raise PreflightError(
                f"La tabla {qualified(schema, table)} no tiene filas. "
                "Carga Olist: cd data/olist && uv run olist-loader load."
            )


def _to_frame(rows: list[tuple], description: list[Any]) -> pd.DataFrame:
    """Builds the DataFrame from the fetched tuples and converts numeric, integer and timestamp columns."""
    names = [column.name for column in description]
    frame = pd.DataFrame(rows, columns=names)
    for column in description:
        name, code = column.name, column.type_code
        if code == OID_NUMERIC:
            frame[name] = frame[name].astype("float64")
        elif code in _INTEGER_OIDS:
            frame[name] = pd.array(frame[name], dtype="Int64")
        elif code in _TIMESTAMP_OIDS:
            frame[name] = pd.to_datetime(frame[name])
    return frame


def load_tables(connection: Any) -> dict[str, pd.DataFrame]:
    """Reads the eight tables with a plain SELECT each; keys are ``schema.table``."""
    tables: dict[str, pd.DataFrame] = {}
    for schema, table in EXPECTED_TABLES:
        cursor = connection.execute(f'SELECT * FROM "{schema}"."{table}"')
        tables[qualified(schema, table)] = _to_frame(cursor.fetchall(), cursor.description)
    return tables
