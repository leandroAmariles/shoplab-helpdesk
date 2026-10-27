from typer.testing import CliRunner

from olist_loader.cli import app, run_pipeline

CROSS_SCHEMA_FOREIGN_KEYS = """
    SELECT c.conname
    FROM pg_constraint c
    JOIN pg_class srcc ON srcc.oid = c.conrelid
    JOIN pg_namespace src ON src.oid = srcc.relnamespace
    JOIN pg_class dstc ON dstc.oid = c.confrelid
    JOIN pg_namespace dst ON dst.oid = dstc.relnamespace
    WHERE c.contype = 'f' AND src.nspname <> dst.nspname
"""


def test_items_of_missing_products_and_sellers_are_loaded_and_counted(conn, settings, fixtures_dir):
    summary = run_pipeline(settings, fixtures_dir, False, False)

    assert "Referencias huérfanas entre esquemas: 2 (order_items.product_id: 1, order_items.seller_id: 1)" in summary
    orphans = conn.execute(
        "SELECT order_id, item_number FROM orders.order_items WHERE product_id = 'p99' OR seller_id = 's99' "
        "ORDER BY order_id, item_number"
    ).fetchall()
    assert orphans == [("o02", 2), ("o03", 1)]


def test_the_command_still_succeeds_with_orphans(conn, settings, fixtures_dir, monkeypatch):
    monkeypatch.setenv("POSTGRES_URL", settings.postgres_url)
    monkeypatch.setenv("POSTGRES_USER", settings.postgres_user)
    monkeypatch.setenv("POSTGRES_PASSWORD", settings.postgres_password)

    result = CliRunner().invoke(app, ["load", "--source", str(fixtures_dir)])

    assert result.exit_code == 0, result.output
    assert "Referencias huérfanas entre esquemas: 2" in result.output


def test_no_foreign_key_crosses_the_schemas_after_loading(conn, settings, fixtures_dir):
    run_pipeline(settings, fixtures_dir, False, False)

    assert conn.execute(CROSS_SCHEMA_FOREIGN_KEYS).fetchall() == []
