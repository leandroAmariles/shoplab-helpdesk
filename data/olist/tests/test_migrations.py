"""The harness applies the real migrations of the backend, so the DDL under test is the production one."""

EXPECTED = {
    "orders": {"customers", "orders", "order_items", "order_payments", "order_reviews"},
    "catalog": {"product_categories", "products", "sellers"},
}


def test_every_table_of_each_schema_exists(conn):
    for schema, tables in EXPECTED.items():
        found = {
            row[0]
            for row in conn.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = %s", (schema,)
            ).fetchall()
        }
        assert tables <= found


def test_the_tables_start_empty(conn):
    assert conn.execute("SELECT count(*) FROM orders.orders").fetchone()[0] == 0
    assert conn.execute("SELECT count(*) FROM catalog.products").fetchone()[0] == 0
