"""db.py against a fake psycopg connection: no Postgres is needed."""

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

import pandas as pd
import psycopg
import pytest

from olist_eda import db
from olist_eda.config import AnalysisSettings
from olist_eda.errors import PreflightError

ALL_TABLES = [
    ("orders", "customers"),
    ("orders", "orders"),
    ("orders", "order_items"),
    ("orders", "order_payments"),
    ("orders", "order_reviews"),
    ("catalog", "product_categories"),
    ("catalog", "products"),
    ("catalog", "sellers"),
]


@dataclass
class Column:
    name: str
    type_code: int


class FakeCursor:
    def __init__(self, rows, columns=()):
        self._rows = rows
        self.description = list(columns)

    def fetchall(self):
        return self._rows

    def fetchone(self):
        return self._rows[0]


class FakeConnection:
    """Answers the three kinds of query db.py makes; records every statement it receives."""

    def __init__(self, existing=None, empty=(), data=None):
        self.existing = ALL_TABLES if existing is None else existing
        self.empty = set(empty)
        self.data = data or {}
        self.statements: list[str] = []
        self.closed = False

    def execute(self, sql, params=None):
        self.statements.append(sql)
        if "information_schema.tables" in sql:
            return FakeCursor([(schema, table) for schema, table in self.existing])
        if sql.startswith("SELECT EXISTS"):
            table = sql.split("FROM", 1)[1].split(")", 1)[0].strip()
            key = tuple(part.strip('"') for part in table.split("."))
            return FakeCursor([(key not in self.empty,)])
        if sql.startswith("SELECT * FROM"):
            key = tuple(part.strip('"') for part in sql.removeprefix("SELECT * FROM ").split("."))
            rows, columns = self.data[key]
            return FakeCursor(rows, columns)
        raise AssertionError(f"unexpected statement: {sql}")

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def test_connect_is_read_only_with_a_short_timeout(monkeypatch):
    captured = {}
    monkeypatch.setattr(psycopg, "connect", lambda **kwargs: captured.update(kwargs) or "connection")
    settings = AnalysisSettings(postgres_url="postgresql://localhost:5432/shoplab", postgres_password="pw")

    assert db.connect(settings) == "connection"

    assert captured["options"] == "-c default_transaction_read_only=on"
    assert captured["connect_timeout"] == 5
    assert captured["conninfo"] == "postgresql://localhost:5432/shoplab"
    assert captured["user"] == "shoplab"
    assert captured["password"] == "pw"


def test_preflight_passes_when_everything_is_there():
    connection = FakeConnection()

    db.preflight(AnalysisSettings(), connector=lambda settings: connection)

    assert connection.closed


def test_preflight_reports_an_unreachable_database_and_how_to_start_it():
    def refuse(settings):
        raise psycopg.OperationalError("connection refused")

    with pytest.raises(PreflightError) as error:
        db.preflight(AnalysisSettings(postgres_password="top-secret"), connector=refuse)

    message = str(error.value)
    assert "localhost:5432" in message
    assert "docker compose -f infra/docker-compose.yml up -d --wait" in message
    assert "top-secret" not in message


def test_preflight_tells_a_rejected_login_apart_from_an_unreachable_server():
    def reject(settings):
        raise psycopg.OperationalError('FATAL:  password authentication failed for user "shoplab"')

    with pytest.raises(PreflightError) as error:
        db.preflight(AnalysisSettings(postgres_password="top-secret"), connector=reject)

    message = str(error.value)
    assert "POSTGRES_USER" in message
    assert "POSTGRES_PASSWORD" in message
    assert "Arranca la base local" not in message
    assert "top-secret" not in message


def test_preflight_reports_missing_tables_and_how_to_create_them():
    connection = FakeConnection(existing=[t for t in ALL_TABLES if t != ("catalog", "sellers")])

    with pytest.raises(PreflightError) as error:
        db.preflight(AnalysisSettings(), connector=lambda settings: connection)

    message = str(error.value)
    assert "catalog" in message
    assert "sellers" in message
    assert "Arranca el backend para aplicar las migraciones" in message
    assert connection.closed


def test_preflight_reports_a_missing_schema():
    connection = FakeConnection(existing=[t for t in ALL_TABLES if t[0] != "catalog"])

    with pytest.raises(PreflightError, match="catalog"):
        db.preflight(AnalysisSettings(), connector=lambda settings: connection)


def test_preflight_reports_an_empty_table_and_how_to_load_olist():
    connection = FakeConnection(empty=[("orders", "order_payments")])

    with pytest.raises(PreflightError) as error:
        db.preflight(AnalysisSettings(), connector=lambda settings: connection)

    message = str(error.value)
    assert "orders.order_payments" in message
    assert "no tiene filas" in message
    assert "olist-loader load" in message


def test_preflight_stops_at_the_first_failure_without_counting_rows():
    connection = FakeConnection(existing=[("orders", "orders")], empty=[("orders", "orders")])

    with pytest.raises(PreflightError, match="Faltan tablas"):
        db.preflight(AnalysisSettings(), connector=lambda settings: connection)

    assert not any(statement.startswith("SELECT EXISTS") for statement in connection.statements)


def _data_for_every_table():
    # numeric 1700, int2 21, int4 23, int8 20, timestamp 1114, text 25
    columns = [
        Column("name", 25),
        Column("price", db.OID_NUMERIC),
        Column("score", db.OID_INT2),
        Column("quantity", db.OID_INT4),
        Column("created_at", db.OID_TIMESTAMP),
    ]
    rows = [
        ("a", Decimal("10.50"), 5, 1, datetime(2017, 1, 1, 10, 0)),
        ("b", None, None, None, None),
    ]
    return {key: (rows, columns) for key in ALL_TABLES}


def test_load_tables_returns_the_eight_qualified_tables_with_converted_types():
    connection = FakeConnection(data=_data_for_every_table())

    tables = db.load_tables(connection)

    assert list(tables) == [f"{schema}.{table}" for schema, table in ALL_TABLES]
    frame = tables["orders.orders"]
    assert list(frame.columns) == ["name", "price", "score", "quantity", "created_at"]
    assert frame["price"].dtype == "float64"
    assert frame["price"].iloc[0] == 10.5
    assert pd.isna(frame["price"].iloc[1])
    assert str(frame["score"].dtype) == "Int64"
    assert str(frame["quantity"].dtype) == "Int64"
    assert frame["score"].isna().tolist() == [False, True]
    assert pd.api.types.is_datetime64_any_dtype(frame["created_at"])
    assert pd.isna(frame["created_at"].iloc[1])


def test_load_tables_only_reads():
    connection = FakeConnection(data=_data_for_every_table())

    db.load_tables(connection)

    assert connection.statements
    assert all(statement.startswith("SELECT") for statement in connection.statements)
