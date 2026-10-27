from olist_loader.cli import run_pipeline

TABLES = (
    "orders.customers", "orders.orders", "orders.order_items", "orders.order_payments", "orders.order_reviews",
    "catalog.product_categories", "catalog.products", "catalog.sellers",
)


def counts(conn) -> dict[str, int]:
    return {table: conn.execute(f"SELECT count(*) FROM {table}").fetchone()[0] for table in TABLES}


def test_running_twice_leaves_the_same_counts_and_no_duplicates(conn, settings, fixtures_dir):
    run_pipeline(settings, fixtures_dir, False, False)
    first = counts(conn)

    run_pipeline(settings, fixtures_dir, False, False)

    assert counts(conn) == first
    assert conn.execute("SELECT count(*) FROM orders.order_reviews WHERE review_id = 'rDup'").fetchone()[0] == 2
    assert conn.execute("SELECT count(*) FROM orders.order_items WHERE order_id = 'o01'").fetchone()[0] == 2


def test_the_second_run_does_not_move_the_review_surrogate_keys_out_of_range(conn, settings, fixtures_dir):
    run_pipeline(settings, fixtures_dir, False, False)
    run_pipeline(settings, fixtures_dir, False, False)

    ids = [row[0] for row in conn.execute("SELECT id FROM orders.order_reviews ORDER BY id")]

    assert ids == [1, 2, 3, 4]


def test_rows_that_were_not_in_the_dataset_do_not_survive_a_load(conn, settings, fixtures_dir):
    conn.execute("INSERT INTO orders.customers (customer_id, customer_unique_id) VALUES ('stale', 'stale-u')")
    conn.execute("INSERT INTO catalog.sellers (seller_id) VALUES ('stale-seller')")

    run_pipeline(settings, fixtures_dir, False, False)

    assert conn.execute("SELECT count(*) FROM orders.customers WHERE customer_id = 'stale'").fetchone()[0] == 0
    assert conn.execute("SELECT count(*) FROM catalog.sellers WHERE seller_id = 'stale-seller'").fetchone()[0] == 0
    assert counts(conn)["orders.customers"] == 3 and counts(conn)["catalog.sellers"] == 2
