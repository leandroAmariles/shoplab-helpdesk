"""Checks, before touching any data, that the backend already created the schemas and tables this loader fills.

The loader never runs DDL: the tables belong to the modules and arrive with their Flyway migrations.
"""

import psycopg

from olist_loader.errors import DatabaseNotReadyError

EXPECTED_TABLES: dict[str, tuple[str, ...]] = {
    "orders": ("customers", "orders", "order_items", "order_payments", "order_reviews"),
    "catalog": ("product_categories", "products", "sellers"),
}
HISTORY_TABLE = "flyway_schema_history"


def check_ready(conn: psycopg.Connection) -> None:
    problems: list[str] = []
    existing_schemas = {
        row[0]
        for row in conn.execute(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ANY(%s)",
            (list(EXPECTED_TABLES),),
        )
    }
    for schema, tables in EXPECTED_TABLES.items():
        if schema not in existing_schemas:
            problems.append(f"falta el esquema {schema}")
            continue
        present = {
            row[0]
            for row in conn.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = %s", (schema,)
            )
        }
        with_key = {
            row[0]
            for row in conn.execute(
                "SELECT table_name FROM information_schema.table_constraints "
                "WHERE table_schema = %s AND constraint_type = 'PRIMARY KEY'",
                (schema,),
            )
        }
        missing = [table for table in tables if table not in present]
        keyless = [table for table in tables if table in present and table not in with_key]
        if missing:
            problems.append(f"faltan tablas en {schema}: {', '.join(missing)}")
        if keyless:
            problems.append(f"tablas sin clave primaria en {schema}: {', '.join(keyless)}")
        if HISTORY_TABLE in present:
            failed = conn.execute(f'SELECT count(*) FROM "{schema}"."{HISTORY_TABLE}" WHERE NOT success').fetchone()[0]
            if failed:
                problems.append(f"el historial de migraciones de {schema} tiene {failed} migración(es) fallida(s)")
    if problems:
        raise DatabaseNotReadyError(
            "La base de datos no está lista: "
            + "; ".join(problems)
            + ". Primero arranca el backend para aplicar las migraciones."
        )
