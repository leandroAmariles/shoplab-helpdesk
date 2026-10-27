import psycopg
import pytest

from olist_loader.errors import DatabaseNotReadyError
from olist_loader.preflight import check_ready

HINT = "arranca el backend para aplicar las migraciones"


def connect(settings) -> psycopg.Connection:
    return psycopg.connect(**settings.connect_kwargs(), autocommit=True)


def table_count(connection: psycopg.Connection) -> int:
    return connection.execute(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('orders', 'catalog')"
    ).fetchone()[0]


def test_a_complete_database_passes(conn):
    check_ready(conn)


def test_a_database_without_schemas_asks_to_start_the_backend(new_database):
    with connect(new_database(migrate=False)) as connection:
        with pytest.raises(DatabaseNotReadyError, match=HINT) as error:
            check_ready(connection)

        assert "orders" in str(error.value) and "catalog" in str(error.value)
        assert table_count(connection) == 0


def test_schemas_without_tables_ask_to_start_the_backend(new_database):
    with connect(new_database(migrate=False)) as connection:
        connection.execute("CREATE SCHEMA orders")
        connection.execute("CREATE SCHEMA catalog")

        with pytest.raises(DatabaseNotReadyError, match=HINT):
            check_ready(connection)

        assert table_count(connection) == 0


def test_a_missing_table_is_named(new_database):
    with connect(new_database()) as connection:
        connection.execute("DROP TABLE catalog.sellers")

        with pytest.raises(DatabaseNotReadyError, match=HINT) as error:
            check_ready(connection)

        assert "sellers" in str(error.value)


def test_a_table_without_its_primary_key_is_reported(new_database):
    with connect(new_database()) as connection:
        connection.execute("ALTER TABLE catalog.sellers DROP CONSTRAINT sellers_pkey")

        with pytest.raises(DatabaseNotReadyError, match="sellers"):
            check_ready(connection)


def test_a_failed_migration_in_the_history_is_reported(new_database):
    with connect(new_database()) as connection:
        connection.execute(
            "CREATE TABLE orders.flyway_schema_history (installed_rank int, version text, success boolean)"
        )
        connection.execute("INSERT INTO orders.flyway_schema_history VALUES (1, '2', false)")

        with pytest.raises(DatabaseNotReadyError, match="orders") as error:
            check_ready(connection)

        assert "fallid" in str(error.value)


def test_a_successful_history_does_not_get_in_the_way(new_database):
    with connect(new_database()) as connection:
        connection.execute(
            "CREATE TABLE catalog.flyway_schema_history (installed_rank int, version text, success boolean)"
        )
        connection.execute("INSERT INTO catalog.flyway_schema_history VALUES (1, '2', true)")

        check_ready(connection)
