import shutil
from decimal import Decimal
from pathlib import Path

import pytest

from olist_loader import transform as t
from olist_loader.errors import LoadError


def table(dataset: t.TransformedDataset, schema: str, name: str) -> t.TableData:
    return next(candidate for candidate in dataset.schemas[schema] if candidate.name == name)


def as_dicts(data: t.TableData) -> list[dict]:
    return [dict(zip(data.columns, row, strict=True)) for row in data.rows]


def by_key(data: t.TableData, key: str) -> dict:
    return {row[key]: row for row in as_dicts(data)}


@pytest.fixture
def dataset(fixtures_dir) -> t.TransformedDataset:
    return t.transform(fixtures_dir)


@pytest.fixture
def editable(tmp_path, fixtures_dir) -> Path:
    folder = tmp_path / "csv"
    shutil.copytree(fixtures_dir, folder)
    return folder


def append(folder: Path, name: str, line: str) -> None:
    with (folder / name).open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


# -- synthetic stock ------------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("product_id", "expected"), [("p01", 25), ("p02", 68), ("p03", 19), ("00066f42aeeb9f3007548bb9d3f33c38", 197)]
)
def test_stock_matches_the_documented_formula(product_id, expected):
    assert t.stock_for(product_id) == expected


def test_a_product_whose_hash_is_a_multiple_of_ten_is_out_of_stock():
    assert t.stock_for("out-3") == 0


def test_stock_is_the_same_on_every_call():
    ids = [f"product-{index}" for index in range(200)]

    assert [t.stock_for(i) for i in ids] == [t.stock_for(i) for i in ids]


def test_about_a_tenth_of_the_products_are_out_of_stock_and_the_rest_stay_in_range():
    stocks = [t.stock_for(f"product-{index}") for index in range(10_000)]

    assert 0.05 <= stocks.count(0) / len(stocks) <= 0.15
    assert min(stocks) == 0 and max(stocks) <= 200
    assert all(stock >= 1 for stock in stocks if stock != 0)


# -- reference price ------------------------------------------------------------------------------------------------


def test_the_reference_price_is_the_median_rounded_to_cents():
    assert t.median_price([Decimal("10.00"), Decimal("12.00")]) == Decimal("11.00")
    assert t.median_price([Decimal("1.00"), Decimal("2.00"), Decimal("10.00")]) == Decimal("2.00")
    assert t.median_price([Decimal("0.10"), Decimal("0.15")]) == Decimal("0.13")


def test_a_product_without_sales_has_no_reference_price():
    assert t.median_price([]) is None


# -- shape of the transformed dataset -------------------------------------------------------------------------------


def test_each_schema_lists_its_tables_in_load_order(dataset):
    assert [x.name for x in dataset.schemas["catalog"]] == ["product_categories", "products", "sellers"]
    assert [x.name for x in dataset.schemas["orders"]] == [
        "customers", "orders", "order_items", "order_payments", "order_reviews"]


def test_the_rows_that_are_kept_and_the_ones_that_are_not(dataset):
    counts = {
        (schema, x.name): (x.read, len(x.rows), sum(x.discarded.values()))
        for schema, tables in dataset.schemas.items()
        for x in tables
    }

    assert counts == {
        ("catalog", "product_categories"): (2, 3, 0),
        ("catalog", "products"): (3, 3, 0),
        ("catalog", "sellers"): (2, 2, 0),
        ("orders", "customers"): (3, 3, 0),
        ("orders", "orders"): (3, 3, 0),
        ("orders", "order_items"): (6, 5, 1),
        ("orders", "order_payments"): (4, 3, 1),
        ("orders", "order_reviews"): (5, 4, 1),
    }


def test_orphans_of_a_child_are_discarded_with_their_reason(dataset):
    for name in ("order_items", "order_payments", "order_reviews"):
        assert table(dataset, "orders", name).discarded == {t.REASON_UNKNOWN_ORDER: 1}


def test_empty_values_become_null_where_the_column_allows_it(dataset):
    orders = by_key(table(dataset, "orders", "orders"), "order_id")
    products = by_key(table(dataset, "catalog", "products"), "product_id")
    reviews = by_key(table(dataset, "orders", "order_reviews"), "review_id")

    assert orders["o02"]["delivered_to_customer_at"] is None
    assert orders["o03"]["approved_at"] is None and orders["o03"]["delivered_to_carrier_at"] is None
    assert orders["o01"]["purchased_at"].year == 2017
    assert products["p03"]["category_name"] is None
    assert products["p03"]["weight_g"] is None and products["p03"]["photos_count"] is None
    assert reviews["rB"]["comment_title"] is None and reviews["rB"]["comment_message"] is None


def test_zip_code_prefixes_keep_their_leading_zeros(dataset):
    assert by_key(table(dataset, "orders", "customers"), "customer_id")["c01"]["zip_code_prefix"] == "01310"
    assert by_key(table(dataset, "catalog", "sellers"), "seller_id")["s01"]["zip_code_prefix"] == "04000"


def test_the_misspelled_source_columns_are_renamed(dataset):
    products = table(dataset, "catalog", "products")

    assert "name_length" in products.columns and "description_length" in products.columns
    assert not any("lenght" in column for column in products.columns)
    assert by_key(products, "product_id")["p01"]["name_length"] == 40


def test_whole_numbers_written_as_decimals_are_accepted(editable):
    (editable / "olist_products_dataset.csv").write_text(
        "product_id,product_category_name,product_name_lenght,product_description_lenght,product_photos_qty,"
        "product_weight_g,product_length_cm,product_height_cm,product_width_cm\n"
        "p01,beleza_saude,40.0,287.0,1.0,225.0,16.0,10.0,14.0\n",
        encoding="utf-8",
    )

    products = by_key(table(t.transform(editable), "catalog", "products"), "product_id")

    assert products["p01"]["name_length"] == 40 and products["p01"]["weight_g"] == 225


def test_a_fractional_value_in_a_whole_number_column_stops_the_load(editable):
    (editable / "olist_products_dataset.csv").write_text(
        "product_id,product_category_name,product_name_lenght,product_description_lenght,product_photos_qty,"
        "product_weight_g,product_length_cm,product_height_cm,product_width_cm\n"
        "p01,beleza_saude,40,287,1,225.5,16,10,14\n",
        encoding="utf-8",
    )

    with pytest.raises(LoadError, match="product_weight_g"):
        t.transform(editable)


def test_a_category_without_translation_is_kept_with_a_null_english_name(dataset):
    categories = by_key(table(dataset, "catalog", "product_categories"), "name")

    assert set(categories) == {"beleza_saude", "esporte_lazer", "pc_gamer"}
    assert categories["pc_gamer"]["name_en"] is None
    assert categories["beleza_saude"]["name_en"] == "health_beauty"
    assert table(dataset, "catalog", "product_categories").derived == 1


def test_a_review_id_repeated_on_two_orders_is_kept_on_both(dataset):
    reviews = [row for row in as_dicts(table(dataset, "orders", "order_reviews")) if row["review_id"] == "rDup"]

    assert sorted(row["order_id"] for row in reviews) == ["o01", "o02"]


def test_products_carry_the_reference_price_and_the_synthetic_stock(dataset):
    products = by_key(table(dataset, "catalog", "products"), "product_id")

    assert products["p01"]["reference_price"] == Decimal("11.00")
    assert products["p02"]["reference_price"] == Decimal("25.00")
    assert products["p03"]["reference_price"] is None
    assert {pid: row["stock"] for pid, row in products.items()} == {pid: t.stock_for(pid) for pid in products}


def test_items_whose_product_or_seller_is_not_in_the_catalog_are_kept_and_counted(dataset):
    items = table(dataset, "orders", "order_items")

    assert dataset.orphans == t.Orphans(product=1, seller=1)
    assert {row["product_id"] for row in as_dicts(items)} >= {"p99"}
    assert {row["seller_id"] for row in as_dicts(items)} >= {"s99"}


# -- invalid rows ---------------------------------------------------------------------------------------------------


def test_a_row_without_a_required_value_is_discarded_and_counted(editable):
    append(editable, "olist_customers_dataset.csv", "c04,,12345,city four,SP")

    customers = table(t.transform(editable), "orders", "customers")

    assert (customers.read, len(customers.rows)) == (4, 3)
    assert customers.discarded == {t.reason_missing("customer_unique_id"): 1}


def test_a_negative_amount_is_discarded_and_counted(editable):
    append(editable, "olist_order_items_dataset.csv", "o01,3,p01,s01,2017-10-06 11:07:15,-1.00,1.00")

    items = table(t.transform(editable), "orders", "order_items")

    assert len(items.rows) == 5
    assert items.discarded[t.reason_negative("price")] == 1


def test_a_review_score_outside_one_to_five_is_discarded(editable):
    append(editable, "olist_order_reviews_dataset.csv", "rBad,o01,6,,,2017-10-11 00:00:00,2017-10-12 03:43:48")

    reviews = table(t.transform(editable), "orders", "order_reviews")

    assert len(reviews.rows) == 4
    assert reviews.discarded[t.REASON_SCORE_RANGE] == 1


def test_a_repeated_primary_key_is_discarded_once(editable):
    append(editable, "olist_customers_dataset.csv", "c01,u01,01310,city one,SP")
    append(editable, "olist_order_items_dataset.csv", "o01,1,p01,s01,2017-10-06 11:07:15,10.00,2.50")

    dataset = t.transform(editable)

    assert table(dataset, "orders", "customers").discarded == {t.REASON_DUPLICATE_KEY: 1}
    assert table(dataset, "orders", "order_items").discarded[t.REASON_DUPLICATE_KEY] == 1


def test_an_order_of_an_unknown_customer_is_discarded_with_its_children(editable):
    append(editable, "olist_orders_dataset.csv", "o04,c99,delivered,2018-01-01 10:00:00,,,,2018-01-20 00:00:00")
    append(editable, "olist_order_items_dataset.csv", "o04,1,p01,s01,2018-01-05 10:00:00,7.00,1.00")

    dataset = t.transform(editable)

    assert table(dataset, "orders", "orders").discarded == {t.REASON_UNKNOWN_CUSTOMER: 1}
    assert table(dataset, "orders", "order_items").discarded[t.REASON_UNKNOWN_ORDER] == 2


def test_an_unreadable_timestamp_stops_the_load_naming_the_file(editable):
    append(editable, "olist_orders_dataset.csv", "o05,c01,delivered,not a date,,,,2018-01-20 00:00:00")

    with pytest.raises(LoadError, match="olist_orders_dataset.csv"):
        t.transform(editable)
