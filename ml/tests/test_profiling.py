import re
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from olist_eda import profiling
from olist_eda.schema import TABLES

SOURCE_DIR = Path(profiling.__file__).parent


def profile(tables, name):
    return profiling.profile_table(TABLES[name], tables[name])


def column(table_profile, name):
    return next(item for item in table_profile.columns if item.name == name)


def test_the_eight_tables_are_declared_with_their_natural_keys():
    assert list(TABLES) == [
        "orders.customers",
        "orders.orders",
        "orders.order_items",
        "orders.order_payments",
        "orders.order_reviews",
        "catalog.product_categories",
        "catalog.products",
        "catalog.sellers",
    ]
    assert TABLES["orders.order_items"].natural_key == ("order_id", "item_number")
    assert TABLES["orders.order_payments"].natural_key == ("order_id", "payment_sequence")
    assert TABLES["orders.order_reviews"].natural_key == ("review_id",)
    assert TABLES["orders.order_reviews"].surrogate_columns == ("id",)


def test_profile_every_table_gives_one_profile_per_table(tables):
    profiles = profiling.profile_tables(tables)

    assert list(profiles) == list(TABLES)
    assert profiles["orders.orders"].rows == 12
    assert profiles["orders.order_items"].rows == 12
    assert profiles["catalog.sellers"].rows == 2


def test_null_count_and_percentage_per_column(tables):
    orders = profile(tables, "orders.orders")

    delivered = column(orders, "delivered_to_customer_at")
    assert delivered.null_count == 5
    assert delivered.null_pct == pytest.approx(5 / 12 * 100)
    assert column(orders, "order_id").null_count == 0
    assert column(orders, "order_id").null_pct == 0.0


def test_a_delivered_order_without_delivery_date_is_an_unexpected_null_but_a_canceled_one_is_not(tables):
    orders = profile(tables, "orders.orders")

    # nulls: o4 (delivered), o5 (canceled), o6 (shipped), o10 (unavailable), o12 (created); only o4 is unexpected
    assert column(orders, "delivered_to_customer_at").null_count == 5
    assert column(orders, "delivered_to_customer_at").unexpected_null_count == 1


def test_missing_approval_is_expected_only_for_created_and_canceled_orders(tables):
    assert column(profile(tables, "orders.orders"), "approved_at").unexpected_null_count == 0

    tables["orders.orders"].loc[tables["orders.orders"]["order_id"] == "o6", "approved_at"] = pd.NaT  # a shipped order

    assert column(profile(tables, "orders.orders"), "approved_at").unexpected_null_count == 1


def test_a_shipped_order_without_carrier_date_is_unexpected(tables):
    assert column(profile(tables, "orders.orders"), "delivered_to_carrier_at").unexpected_null_count == 0

    mask = tables["orders.orders"]["order_id"] == "o6"
    tables["orders.orders"].loc[mask, "delivered_to_carrier_at"] = pd.NaT

    assert column(profile(tables, "orders.orders"), "delivered_to_carrier_at").unexpected_null_count == 1


def test_optional_review_columns_are_always_expected(tables):
    reviews = profile(tables, "orders.order_reviews")

    assert column(reviews, "comment_title").null_count == 8
    assert column(reviews, "comment_title").unexpected_null_count == 0
    assert column(reviews, "answered_at").null_count == 6
    assert column(reviews, "answered_at").unexpected_null_count == 0


def test_a_product_without_category_or_attributes_is_unexpected(tables):
    products = profile(tables, "catalog.products")

    assert column(products, "category_name").unexpected_null_count == 1
    assert column(products, "name_length").unexpected_null_count == 1


def test_a_column_without_a_rule_treats_every_null_as_unexpected(tables):
    tables["orders.customers"].loc[0, "city"] = None

    assert column(profile(tables, "orders.customers"), "city").unexpected_null_count == 1


def test_a_missing_translation_is_an_expected_null_of_the_source(tables):
    categories = profile(tables, "catalog.product_categories")

    assert column(categories, "name_en").null_count == 1
    assert column(categories, "name_en").unexpected_null_count == 0


def test_duplicates_by_natural_key_and_by_full_row_excluding_the_surrogate(tables):
    reviews = profile(tables, "orders.order_reviews")

    # review_id repeats for 'rdup' (two different orders) and 'r9' (identical rows except id)
    assert reviews.key_duplicates == 2
    assert reviews.row_duplicates == 1


def test_tables_with_a_primary_key_have_no_key_duplicates(tables):
    profiles = profiling.profile_tables(tables)

    for name in ("orders.customers", "orders.orders", "orders.order_items", "orders.order_payments",
                 "catalog.products", "catalog.sellers", "catalog.product_categories"):
        assert profiles[name].key_duplicates == 0, name
        assert profiles[name].row_duplicates == 0, name


def test_repetitions_that_are_not_defects_are_reported_as_information(tables):
    info = profiling.informative_repetitions(tables)

    assert info["persons_with_several_customer_ids"] == 1  # u3 owns c3 and c4
    assert info["orders_with_several_reviews"] == 2  # o3 (two reviews) and o9 (the identical pair)


def test_dtype_of_each_column_is_reported(tables):
    items = profile(tables, "orders.order_items")

    assert column(items, "price").dtype == "float64"
    assert column(items, "item_number").dtype == "Int64"


def test_min_max_and_percentiles_for_numeric_columns(tables):
    price = column(profile(tables, "orders.order_items"), "price")

    assert price.min == 0.0
    assert price.max == 100.0
    assert price.p50 == 35.0
    assert price.p25 == pytest.approx(np.percentile(tables["orders.order_items"]["price"], 25))
    assert price.p01 <= price.p25 <= price.p50 <= price.p75 <= price.p99


def test_min_max_and_percentiles_for_date_columns(tables):
    purchased = column(profile(tables, "orders.orders"), "purchased_at")

    assert purchased.min == pd.Timestamp("2017-01-01 10:00")
    assert purchased.max == pd.Timestamp("2017-12-01 10:00")
    assert purchased.p50 is not None


def test_text_columns_have_no_range(tables):
    status = column(profile(tables, "orders.orders"), "status")

    assert status.min is None
    assert status.max is None
    assert status.p50 is None


def test_values_outside_what_is_plausible_are_counted(tables):
    items = profile(tables, "orders.order_items")
    products = profile(tables, "catalog.products")

    assert column(items, "price").implausible_count == 1  # o8 costs 0
    assert column(items, "freight_value").implausible_count == 0
    assert column(products, "weight_g").implausible_count == 1  # p3 weighs 0
    assert column(products, "length_cm").implausible_count == 0


def test_a_negative_freight_and_a_score_out_of_range_are_implausible(tables):
    tables["orders.order_items"].loc[0, "freight_value"] = -1.0
    tables["orders.order_reviews"].loc[0, "score"] = 7

    assert column(profile(tables, "orders.order_items"), "freight_value").implausible_count == 1
    assert column(profile(tables, "orders.order_reviews"), "score").implausible_count == 1


def test_dates_outside_the_window_of_the_dataset_are_implausible(tables):
    assert column(profile(tables, "orders.orders"), "purchased_at").implausible_count == 0

    tables["orders.orders"].loc[0, "purchased_at"] = pd.Timestamp("2020-04-01")
    tables["orders.orders"].loc[1, "purchased_at"] = pd.Timestamp("2015-12-31")

    assert column(profile(tables, "orders.orders"), "purchased_at").implausible_count == 2


def test_an_empty_table_does_not_divide_by_zero(tables):
    empty = tables["orders.orders"].iloc[0:0]

    result = profiling.profile_table(TABLES["orders.orders"], empty)

    assert result.rows == 0
    assert result.key_duplicates == 0
    assert all(item.null_pct == 0.0 for item in result.columns)
    assert all(item.min is None for item in result.columns)


def test_a_column_that_is_entirely_null_has_no_range(tables):
    tables["orders.order_items"]["price"] = np.nan

    price = column(profile(tables, "orders.order_items"), "price")

    assert price.null_pct == 100.0
    assert price.min is None
    assert price.p50 is None
    assert price.implausible_count == 0


def test_the_profile_can_be_shown_as_a_table(tables):
    frame = profile(tables, "orders.order_items").to_frame()

    assert list(frame["column"]) == list(tables["orders.order_items"].columns)
    for expected in ("dtype", "null_count", "null_pct", "unexpected_null_count", "min", "max", "p50",
                     "implausible_count"):
        assert expected in frame.columns


@pytest.mark.parametrize("module", ["profiling.py", "integrity.py"])
def test_no_calculation_walks_the_rows_one_by_one(module):
    source = (SOURCE_DIR / module).read_text(encoding="utf-8")

    assert not re.search(r"iterrows|itertuples|\.apply\([^)]*axis\s*=\s*1|range\(len\(", source), module
    assert not re.search(r"for\s+\w+(\s*,\s*\w+)*\s+in\s+zip\(\s*\w+\[", source), module
