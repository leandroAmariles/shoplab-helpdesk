"""Integrity between schemas. LAB-17 removed the foreign keys between orders and catalog on purpose, so this is the
only integrity check that exists between them."""

from dataclasses import dataclass

import pandas as pd


@dataclass(frozen=True)
class OrphanCount:
    reference: str
    checked: int
    orphans: int
    pct: float  # 0-100, of the rows checked


def _count(reference: str, values: pd.Series, existing: pd.Series) -> OrphanCount:
    checked = len(values)
    orphans = int((~values.isin(existing)).sum())
    return OrphanCount(reference, checked, orphans, orphans / checked * 100 if checked else 0.0)


def orphan_references(tables: dict[str, pd.DataFrame]) -> list[OrphanCount]:
    """Rows that point to an id missing from the table they reference (cross-schema, plus the internal category)."""
    items = tables["orders.order_items"]
    products = tables["catalog.products"]
    return [
        _count("order_items.product_id -> catalog.products", items["product_id"], products["product_id"]),
        _count("order_items.seller_id -> catalog.sellers", items["seller_id"], tables["catalog.sellers"]["seller_id"]),
        _count(
            "products.category_name -> product_categories",
            products["category_name"].dropna(),
            tables["catalog.product_categories"]["name"],
        ),
    ]
