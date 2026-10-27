from decimal import Decimal

import psycopg
import pytest

from olist_loader.errors import LoadError
from olist_loader.load import load_all
from olist_loader.transform import stock_for, transform


@pytest.fixture
def loaded(conn, fixtures_dir):
    load_all(conn, transform(fixtures_dir))
    return conn


def count(conn, qualified_table: str) -> int:
    return conn.execute(f"SELECT count(*) FROM {qualified_table}").fetchone()[0]


def test_every_table_receives_the_rows_that_were_kept(loaded):
    assert {
        "orders.customers": count(loaded, "orders.customers"),
        "orders.orders": count(loaded, "orders.orders"),
        "orders.order_items": count(loaded, "orders.order_items"),
        "orders.order_payments": count(loaded, "orders.order_payments"),
        "orders.order_reviews": count(loaded, "orders.order_reviews"),
        "catalog.product_categories": count(loaded, "catalog.product_categories"),
        "catalog.products": count(loaded, "catalog.products"),
        "catalog.sellers": count(loaded, "catalog.sellers"),
    } == {
        "orders.customers": 3,
        "orders.orders": 3,
        "orders.order_items": 5,
        "orders.order_payments": 3,
        "orders.order_reviews": 4,
        "catalog.product_categories": 3,
        "catalog.products": 3,
        "catalog.sellers": 2,
    }


def test_an_order_is_rebuilt_with_queries_on_orders_only(loaded):
    customer = loaded.execute(
        "SELECT c.city FROM orders.orders o JOIN orders.customers c USING (customer_id) WHERE o.order_id = 'o01'"
    ).fetchone()
    items = loaded.execute(
        "SELECT item_number, price FROM orders.order_items WHERE order_id = 'o01' ORDER BY item_number"
    ).fetchall()
    payments = loaded.execute(
        "SELECT payment_type FROM orders.order_payments WHERE order_id = 'o01' ORDER BY payment_sequence"
    ).fetchall()
    reviews = loaded.execute(
        "SELECT review_id FROM orders.order_reviews WHERE order_id = 'o01' ORDER BY review_id"
    ).fetchall()

    assert customer == ("city one",)
    assert items == [(1, Decimal("10.00")), (2, Decimal("20.00"))]
    assert payments == [("credit_card",), ("voucher",)]
    assert reviews == [("rA",), ("rDup",)]


def test_a_product_is_read_with_queries_on_catalog_only(loaded):
    category, name_en, price, stock = loaded.execute(
        "SELECT p.category_name, c.name_en, p.reference_price, p.stock "
        "FROM catalog.products p JOIN catalog.product_categories c ON c.name = p.category_name "
        "WHERE p.product_id = 'p01'"
    ).fetchone()

    assert (category, name_en, price, stock) == ("beleza_saude", "health_beauty", Decimal("11.00"), stock_for("p01"))


def test_a_review_id_repeated_on_two_orders_is_stored_twice(loaded):
    assert count(loaded, "orders.order_reviews WHERE review_id = 'rDup'") == 2


def test_leading_zeros_of_the_zip_codes_survive(loaded):
    assert loaded.execute("SELECT zip_code_prefix FROM orders.customers WHERE customer_id = 'c01'").fetchone() == (
        "01310",
    )


def test_items_pointing_at_missing_products_are_stored(loaded):
    rows = loaded.execute(
        "SELECT product_id, seller_id FROM orders.order_items WHERE product_id = 'p99' OR seller_id = 's99'"
    ).fetchall()

    assert sorted(rows) == [("p02", "s99"), ("p99", "s01")]


def test_a_database_failure_is_reported_as_a_load_error(new_database, fixtures_dir):
    dataset = transform(fixtures_dir)
    with psycopg.connect(**new_database().connect_kwargs(), autocommit=True) as own:
        own.execute("DROP TABLE orders.order_payments CASCADE")

        with pytest.raises(LoadError, match="orders"):
            load_all(own, dataset)
