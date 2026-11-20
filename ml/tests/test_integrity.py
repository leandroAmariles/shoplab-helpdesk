import pytest

from olist_eda import integrity


def by_reference(results):
    return {item.reference: item for item in results}


def test_items_pointing_to_a_missing_product_or_seller_are_counted(tables):
    results = by_reference(integrity.orphan_references(tables))

    product = results["order_items.product_id -> catalog.products"]
    assert product.checked == 12
    assert product.orphans == 1  # o7 points to p_missing
    assert product.pct == pytest.approx(1 / 12 * 100)

    seller = results["order_items.seller_id -> catalog.sellers"]
    assert seller.checked == 12
    assert seller.orphans == 1  # o6 points to s_missing


def test_the_internal_category_reference_is_checked_only_for_products_with_a_category(tables):
    category = by_reference(integrity.orphan_references(tables))["products.category_name -> product_categories"]

    assert category.checked == 4  # p5 has no category
    assert category.orphans == 0


def test_a_product_with_an_unknown_category_is_an_orphan(tables):
    tables["catalog.products"].loc[0, "category_name"] = "cat_unknown"

    category = by_reference(integrity.orphan_references(tables))["products.category_name -> product_categories"]

    assert category.orphans == 1


def test_without_items_there_is_no_division_by_zero(tables):
    tables["orders.order_items"] = tables["orders.order_items"].iloc[0:0]

    results = by_reference(integrity.orphan_references(tables))

    assert results["order_items.product_id -> catalog.products"].checked == 0
    assert results["order_items.product_id -> catalog.products"].orphans == 0
    assert results["order_items.product_id -> catalog.products"].pct == 0.0


def test_the_three_checks_are_always_reported(tables):
    assert [item.reference for item in integrity.orphan_references(tables)] == [
        "order_items.product_id -> catalog.products",
        "order_items.seller_id -> catalog.sellers",
        "products.category_name -> product_categories",
    ]
