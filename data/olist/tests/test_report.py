import re
from collections import Counter

from olist_loader.report import render
from olist_loader.transform import Orphans, TableData, TransformedDataset


def make_table(name: str, read: int, rows: list[tuple], discarded: dict[str, int] | None = None, derived: int = 0):
    return TableData(
        schema="orders",
        name=name,
        columns=("id", "value"),
        rows=rows,
        read=read,
        discarded=Counter(discarded or {}),
        derived=derived,
    )


def make_dataset(orphans: Orphans | None = None) -> TransformedDataset:
    orphans = orphans or Orphans(0, 0)
    return TransformedDataset(
        schemas={
            "orders": [
                make_table("customers", 3, [("c1", "SECRET-VALUE")] * 3),
                make_table("order_items", 5, [("o1", "SECRET-VALUE")] * 3, {"order_id inexistente": 2}),
            ],
            "catalog": [make_table("product_categories", 2, [("a", "x")] * 3, derived=1)],
        },
        orphans=orphans,
    )


def test_every_schema_and_table_reports_read_loaded_and_discarded():
    text = render(make_dataset(), elapsed_seconds=1)

    assert "Esquema orders" in text and "Esquema catalog" in text
    assert re.search(r"customers\s+leídas 3\s+cargadas 3\s+descartadas 0", text)
    assert re.search(r"order_items\s+leídas 5\s+cargadas 3\s+descartadas 2", text)


def test_the_reasons_of_the_discarded_rows_are_listed_under_their_table():
    lines = render(make_dataset(), elapsed_seconds=1).splitlines()
    position = next(index for index, line in enumerate(lines) if "order_items" in line)

    assert lines[position + 1].strip() == "- order_id inexistente: 2"


def test_derived_rows_are_reported_when_there_are_any():
    text = render(make_dataset(), elapsed_seconds=1)

    assert re.search(r"product_categories.*cargadas 3.*\(1 derivadas\)", text)
    assert "(0 derivadas)" not in text


def test_cross_schema_orphans_are_counted_with_their_breakdown():
    text = render(make_dataset(Orphans(product=2, seller=1)), elapsed_seconds=1)

    assert "Referencias huérfanas entre esquemas: 3 (order_items.product_id: 2, order_items.seller_id: 1)" in text


def test_the_total_time_is_shown_as_hours_minutes_seconds():
    assert "Tiempo total: 00:01:05" in render(make_dataset(), elapsed_seconds=65.4)
    assert "Tiempo total: 01:00:00" in render(make_dataset(), elapsed_seconds=3600)


def test_a_dry_run_says_nothing_was_written():
    assert "no se escribió" in render(make_dataset(), elapsed_seconds=1, dry_run=True)
    assert "no se escribió" not in render(make_dataset(), elapsed_seconds=1)


def test_no_row_value_reaches_the_summary():
    assert "SECRET-VALUE" not in render(make_dataset(), elapsed_seconds=1)
