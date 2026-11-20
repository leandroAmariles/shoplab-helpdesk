"""Shared fixtures: the eight Olist tables in memory, with the anomalies the analysis must detect.

The frames use the types ``olist_eda.db.load_tables`` produces: ``float64`` for numeric, datetimes for timestamps and
``Int64`` for integers. Every anomaly is deliberate and listed here so tests can refer to it by order or product id.

orders.orders (12 orders; all timestamps are at 10:00 so day differences are exact)
    o1   delivered, 2 days early (delay -2)                items: p1 (100) + p2 (50)  -> two categories, primary cat_a
    o2   delivered, 2 days late (delay +2)                 items: p3 (80) + p4 (80)   -> tie of price, primary cat_b
    o3   delivered, 15 days late (delay +15)               item:  p1; two reviews (1 and 2) -> mean 1.5
    o4   ``delivered`` WITHOUT delivery date               item:  p5 (product without category)
    o5   canceled, no approval/carrier/delivery (nulls expected by the business); payment ``not_defined``
    o6   shipped, no delivery date                         item:  p1 with ``s_missing`` (seller absent from catalog)
    o7   delivered BEFORE purchase (incoherent)            item:  ``p_missing`` (product absent from catalog)
    o8   delivered exactly on the estimated date (delay 0) item:  p3 with price 0 (implausible)
    o9   estimated date BEFORE purchase (incoherent)       item:  p4; review ``r9`` twice (identical but for ``id``)
    o10  unavailable, no carrier/delivery
    o11  ``shipped`` WITH a delivery date (5 days early)   item:  p2; review id ``rdup`` (also used by o8)
    o12  created, no approval (nulls expected)

Analysed population (non-null delivery date and coherent dates): o1, o2, o3, o8, o11 with delays -2, 2, 15, 0, -5.
"""

import pandas as pd
import pytest


def _ts(value: str | None) -> pd.Timestamp:
    return pd.NaT if value is None else pd.Timestamp(value)


def _frame(columns: list[str], rows: list[tuple], *, ints=(), floats=(), times=()) -> pd.DataFrame:
    frame = pd.DataFrame(rows, columns=columns)
    for name in ints:
        frame[name] = pd.array(frame[name], dtype="Int64")
    for name in floats:
        frame[name] = frame[name].astype("float64")
    for name in times:
        frame[name] = pd.to_datetime(frame[name])
    return frame


def build_tables() -> dict[str, pd.DataFrame]:
    customers = _frame(
        ["customer_id", "customer_unique_id", "zip_code_prefix", "city", "state"],
        [
            ("c1", "u1", "01000", "sao paulo", "SP"),
            ("c2", "u2", "01001", "sao paulo", "SP"),
            ("c3", "u3", "20000", "rio", "RJ"),
            ("c4", "u3", "20000", "rio", "RJ"),  # same person as c3: informative repetition, not a defect
            ("c5", "u5", "30000", "belo horizonte", "MG"),
            ("c6", "u6", "30001", "belo horizonte", "MG"),
            ("c7", "u7", "01002", "sao paulo", "SP"),
        ],
    )
    order_columns = [
        "order_id",
        "customer_id",
        "status",
        "purchased_at",
        "approved_at",
        "delivered_to_carrier_at",
        "delivered_to_customer_at",
        "estimated_delivery_at",
    ]
    orders = _frame(
        order_columns,
        [
            ("o1", "c1", "delivered", _ts("2017-01-01 10:00"), _ts("2017-01-01 11:00"), _ts("2017-01-02 10:00"),
             _ts("2017-01-08 10:00"), _ts("2017-01-10 10:00")),
            ("o2", "c2", "delivered", _ts("2017-02-01 10:00"), _ts("2017-02-01 11:00"), _ts("2017-02-02 10:00"),
             _ts("2017-02-12 10:00"), _ts("2017-02-10 10:00")),
            ("o3", "c3", "delivered", _ts("2017-03-01 10:00"), _ts("2017-03-01 11:00"), _ts("2017-03-02 10:00"),
             _ts("2017-03-25 10:00"), _ts("2017-03-10 10:00")),
            ("o4", "c4", "delivered", _ts("2017-04-01 10:00"), _ts("2017-04-01 11:00"), _ts("2017-04-02 10:00"),
             None, _ts("2017-04-10 10:00")),
            ("o5", "c5", "canceled", _ts("2017-05-01 10:00"), None, None, None, _ts("2017-05-10 10:00")),
            ("o6", "c6", "shipped", _ts("2017-06-01 10:00"), _ts("2017-06-01 11:00"), _ts("2017-06-02 10:00"),
             None, _ts("2017-06-10 10:00")),
            ("o7", "c7", "delivered", _ts("2017-07-10 10:00"), _ts("2017-07-10 11:00"), _ts("2017-07-11 10:00"),
             _ts("2017-07-05 10:00"), _ts("2017-07-20 10:00")),
            ("o8", "c1", "delivered", _ts("2017-08-01 10:00"), _ts("2017-08-01 11:00"), _ts("2017-08-02 10:00"),
             _ts("2017-08-10 10:00"), _ts("2017-08-10 10:00")),
            ("o9", "c2", "delivered", _ts("2017-09-01 10:00"), _ts("2017-09-01 11:00"), _ts("2017-09-02 10:00"),
             _ts("2017-09-05 10:00"), _ts("2017-08-30 10:00")),
            ("o10", "c3", "unavailable", _ts("2017-10-01 10:00"), _ts("2017-10-01 11:00"), None, None,
             _ts("2017-10-15 10:00")),
            ("o11", "c4", "shipped", _ts("2017-11-01 10:00"), _ts("2017-11-01 11:00"), _ts("2017-11-02 10:00"),
             _ts("2017-11-05 10:00"), _ts("2017-11-10 10:00")),
            ("o12", "c5", "created", _ts("2017-12-01 10:00"), None, None, None, _ts("2017-12-20 10:00")),
        ],
        times=order_columns[3:],
    )
    order_items = _frame(
        ["order_id", "item_number", "product_id", "seller_id", "shipping_limit_at", "price", "freight_value"],
        [
            ("o1", 1, "p1", "s1", _ts("2017-01-03"), 100.0, 10.0),
            ("o1", 2, "p2", "s1", _ts("2017-01-03"), 50.0, 5.0),
            ("o2", 1, "p3", "s1", _ts("2017-02-03"), 80.0, 8.0),
            ("o2", 2, "p4", "s2", _ts("2017-02-03"), 80.0, 8.0),
            ("o3", 1, "p1", "s1", _ts("2017-03-03"), 30.0, 3.0),
            ("o4", 1, "p5", "s2", _ts("2017-04-03"), 20.0, 2.0),
            ("o5", 1, "p2", "s1", _ts("2017-05-03"), 40.0, 4.0),
            ("o6", 1, "p1", "s_missing", _ts("2017-06-03"), 60.0, 6.0),
            ("o7", 1, "p_missing", "s1", _ts("2017-07-03"), 10.0, 1.0),
            ("o8", 1, "p3", "s1", _ts("2017-08-03"), 0.0, 0.0),
            ("o9", 1, "p4", "s2", _ts("2017-09-03"), 15.0, 1.5),
            ("o11", 1, "p2", "s1", _ts("2017-11-03"), 25.0, 2.5),
        ],
        ints=["item_number"],
        floats=["price", "freight_value"],
        times=["shipping_limit_at"],
    )
    order_payments = _frame(
        ["order_id", "payment_sequence", "payment_type", "installments", "payment_value"],
        [
            ("o1", 1, "credit_card", 3, 150.0),
            ("o2", 1, "credit_card", 1, 100.0),
            ("o2", 2, "voucher", 1, 60.0),
            ("o3", 1, "boleto", 1, 33.0),
            ("o4", 1, "credit_card", 2, 22.0),
            ("o5", 1, "not_defined", 1, 0.0),  # a payment type no policy mentions
            ("o6", 1, "debit_card", 1, 66.0),
            ("o7", 1, "credit_card", 1, 11.0),
            ("o8", 1, "credit_card", 1, 0.0),
            ("o9", 1, "boleto", 1, 16.5),
            ("o10", 1, "voucher", 1, 12.0),
            ("o11", 1, "credit_card", 4, 27.5),
            ("o12", 1, "boleto", 1, 9.0),
        ],
        ints=["payment_sequence", "installments"],
        floats=["payment_value"],
    )
    order_reviews = _frame(
        [
            "id",
            "review_id",
            "order_id",
            "score",
            "comment_title",
            "comment_message",
            "created_at",
            "answered_at",
        ],
        [
            (1, "r1", "o1", 5, None, None, _ts("2017-01-12"), _ts("2017-01-13")),
            (2, "r2", "o2", 4, None, "Bien", _ts("2017-02-15"), None),
            (3, "r3", "o3", 1, "Mal", "Llegó tarde", _ts("2017-03-28"), _ts("2017-03-29")),
            (4, "r3b", "o3", 2, None, None, _ts("2017-03-29"), None),
            (5, "rdup", "o8", 4, None, None, _ts("2017-08-12"), None),
            (6, "rdup", "o11", 3, None, None, _ts("2017-11-08"), None),
            (7, "r9", "o9", 3, None, None, _ts("2017-09-08"), None),
            (8, "r9", "o9", 3, None, None, _ts("2017-09-08"), None),  # identical to id 7 except for id
            (9, "r4", "o4", 5, None, None, _ts("2017-04-20"), _ts("2017-04-21")),
        ],
        ints=["id", "score"],
        times=["created_at", "answered_at"],
    )
    product_categories = _frame(
        ["name", "name_en"],
        [("cat_a", "category a"), ("cat_b", "category b"), ("cat_c", None)],  # cat_c has no translation
    )
    products = _frame(
        [
            "product_id",
            "category_name",
            "name_length",
            "description_length",
            "photos_count",
            "weight_g",
            "length_cm",
            "height_cm",
            "width_cm",
            "reference_price",
            "stock",
        ],
        [
            ("p1", "cat_a", 40, 300, 2, 500, 20, 10, 15, 100.0, 7),
            ("p2", "cat_b", 35, 250, 1, 300, 15, 8, 10, 50.0, 3),
            ("p3", "cat_b", 30, 200, 1, 0, 12, 6, 9, 80.0, 5),  # weight 0: implausible
            ("p4", "cat_c", 28, 180, 3, 700, 25, 12, 18, 80.0, 2),
            ("p5", None, None, None, None, 400, 18, 9, 12, 20.0, 1),  # no category and no descriptive attributes
        ],
        ints=["name_length", "description_length", "photos_count", "weight_g", "length_cm", "height_cm", "width_cm",
              "stock"],
        floats=["reference_price"],
    )
    sellers = _frame(
        ["seller_id", "zip_code_prefix", "city", "state"],
        [("s1", "01000", "sao paulo", "SP"), ("s2", "20000", "rio", "RJ")],
    )
    return {
        "orders.customers": customers,
        "orders.orders": orders,
        "orders.order_items": order_items,
        "orders.order_payments": order_payments,
        "orders.order_reviews": order_reviews,
        "catalog.product_categories": product_categories,
        "catalog.products": products,
        "catalog.sellers": sellers,
    }


@pytest.fixture
def tables() -> dict[str, pd.DataFrame]:
    """A fresh copy per test: tests may mutate the frames."""
    return build_tables()
