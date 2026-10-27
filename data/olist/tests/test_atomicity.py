import pytest

from olist_loader.errors import LoadError
from olist_loader.load import load_all
from olist_loader.transform import transform

from .test_idempotence import counts


def with_a_bad_payment(dataset):
    """The payment row violates the CHECK on installments, so the copy fails after other tables were copied."""
    payments = next(table for table in dataset.schemas["orders"] if table.name == "order_payments")
    order_id, sequence, payment_type, _, value = payments.rows[-1]
    payments.rows[-1] = (order_id, sequence, payment_type, -1, value)
    return dataset


def test_a_failure_in_the_middle_leaves_the_schema_as_it_was(conn, fixtures_dir):
    load_all(conn, transform(fixtures_dir))
    before = counts(conn)

    with pytest.raises(LoadError, match="orders"):
        load_all(conn, with_a_bad_payment(transform(fixtures_dir)))

    assert counts(conn) == before


def test_a_failure_on_an_empty_schema_leaves_it_empty(conn, fixtures_dir):
    with pytest.raises(LoadError):
        load_all(conn, with_a_bad_payment(transform(fixtures_dir)))

    assert counts(conn)["orders.customers"] == 0
    assert counts(conn)["orders.order_items"] == 0


def test_running_again_with_good_data_completes_the_whole_set(conn, fixtures_dir):
    with pytest.raises(LoadError):
        load_all(conn, with_a_bad_payment(transform(fixtures_dir)))

    load_all(conn, transform(fixtures_dir))

    assert counts(conn) == {
        "orders.customers": 3, "orders.orders": 3, "orders.order_items": 5, "orders.order_payments": 3,
        "orders.order_reviews": 4, "catalog.product_categories": 3, "catalog.products": 3, "catalog.sellers": 2,
    }


def test_the_error_message_carries_no_row_value(conn, fixtures_dir):
    with pytest.raises(LoadError) as error:
        load_all(conn, with_a_bad_payment(transform(fixtures_dir)))

    assert "-1" not in str(error.value) and "Failing row" not in str(error.value)
