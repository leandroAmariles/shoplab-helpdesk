"""Writes the transformed rows: one transaction per schema, emptying its tables first and then copying the rows in.

A failure rolls the schema back to what it was, so a run never leaves half a dataset; running again gives the same
final state. The two schemas are loaded independently and never queried together.
"""

import psycopg

from olist_loader.errors import LoadError
from olist_loader.transform import TableData, TransformedDataset


def load_all(conn: psycopg.Connection, dataset: TransformedDataset) -> None:
    for schema, tables in dataset.schemas.items():
        _load_schema(conn, schema, tables)


def _load_schema(conn: psycopg.Connection, schema: str, tables: list[TableData]) -> None:
    try:
        with conn.transaction():
            # A single statement with every table of the schema, so the foreign keys between them are no obstacle.
            conn.execute("TRUNCATE " + ", ".join(f'"{schema}"."{table.name}"' for table in tables) + " RESTART IDENTITY")
            for table in tables:
                columns = ", ".join(f'"{column}"' for column in table.columns)
                with conn.cursor() as cursor:
                    with cursor.copy(f'COPY "{schema}"."{table.name}" ({columns}) FROM STDIN') as copy:
                        for row in table.rows:
                            copy.write_row(row)
    except psycopg.Error as error:
        # Only the primary message: the detail of a failed row would carry the values of that row.
        reason = getattr(error.diag, "message_primary", None) or error.__class__.__name__
        raise LoadError(f"Falló la carga del esquema {schema} y se revirtió: {reason}") from error
