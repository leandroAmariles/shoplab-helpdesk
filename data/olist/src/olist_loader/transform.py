"""Turns the Olist CSV files into the rows of each table of the orders and catalog schemas.

Pure data work, no database: reads the files as streams, renames the columns, turns empty values into nulls, drops
the rows that would break an integrity rule inside their own schema (counting each reason), and derives the two
values the dataset does not carry (synthetic stock and reference price). See specs/LAB-17 data-model.md.
"""

import csv
import hashlib
import statistics
from collections import Counter
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from pathlib import Path

from olist_loader.errors import LoadError

STOCK_SEED = "olist-stock-v1:"  # bump the version to change the formula on purpose
CENTS = Decimal("0.01")

REASON_UNKNOWN_ORDER = "order_id inexistente"
REASON_UNKNOWN_CUSTOMER = "customer_id inexistente"
REASON_DUPLICATE_KEY = "clave duplicada"
REASON_SCORE_RANGE = "puntuación fuera de rango (1 a 5)"


def reason_missing(column: str) -> str:
    return f"valor obligatorio vacío: {column}"


def reason_negative(column: str) -> str:
    return f"valor negativo: {column}"


@dataclass
class TableData:
    schema: str
    name: str
    columns: tuple[str, ...]
    rows: list[tuple] = field(default_factory=list)
    read: int = 0
    discarded: Counter = field(default_factory=Counter)
    derived: int = 0  # rows added that were not in the source (categories found only in products)


@dataclass(frozen=True)
class Orphans:
    """Order items whose product or seller is not in the catalog; kept, only counted (no cross-schema integrity)."""

    product: int
    seller: int

    @property
    def total(self) -> int:
        return self.product + self.seller


@dataclass
class TransformedDataset:
    schemas: dict[str, list[TableData]]
    orphans: Orphans


def stock_for(product_id: str) -> int:
    """Deterministic synthetic stock: about a tenth of the products are out of stock, the rest hold 1 to 200."""
    digest = hashlib.sha256((STOCK_SEED + product_id).encode()).digest()
    value = int.from_bytes(digest[:4], "big")
    if value % 10 == 0:
        return 0
    return 1 + (value // 10) % 200


def median_price(prices: Iterable[Decimal]) -> Decimal | None:
    values = list(prices)
    if not values:
        return None
    return Decimal(statistics.median(values)).quantize(CENTS, rounding=ROUND_HALF_UP)


class _Row:
    """One CSV record, with typed accessors that name the file and line when a value cannot be read."""

    def __init__(self, file: str, line: int, values: dict[str, str]):
        self._file, self._line, self._values = file, line, values

    def text(self, column: str) -> str | None:
        value = self._values.get(column)
        return None if value is None or value.strip() == "" else value

    def _fail(self, column: str, expected: str) -> LoadError:
        return LoadError(f"{self._file}, línea {self._line}: el valor de '{column}' no es {expected}.")

    def timestamp(self, column: str) -> datetime | None:
        value = self.text(column)
        if value is None:
            return None
        try:
            return datetime.fromisoformat(value.strip())
        except ValueError:
            raise self._fail(column, "una fecha con formato AAAA-MM-DD HH:MM:SS") from None

    def decimal(self, column: str) -> Decimal | None:
        value = self.text(column)
        if value is None:
            return None
        try:
            return Decimal(value.strip())
        except InvalidOperation:
            raise self._fail(column, "un número") from None

    def integer(self, column: str) -> int | None:
        """Accepts 40 and 40.0: the source writes whole numbers as decimals when the column has empty values."""
        number = self.decimal(column)
        if number is None:
            return None
        if number != number.to_integral_value():
            raise self._fail(column, "un número entero")
        return int(number)


def _rows(folder: Path, file: str) -> Iterator[_Row]:
    with (folder / file).open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        for values in reader:
            yield _Row(file, reader.line_num, values)


def _first_missing(values: dict[str, object]) -> str | None:
    return next((column for column, value in values.items() if value is None), None)


def _first_negative(values: dict[str, Decimal | int | None]) -> str | None:
    return next((column for column, value in values.items() if value is not None and value < 0), None)


def _customers(folder: Path) -> TableData:
    table = TableData("orders", "customers", ("customer_id", "customer_unique_id", "zip_code_prefix", "city", "state"))
    seen: set[str] = set()
    for row in _rows(folder, "olist_customers_dataset.csv"):
        table.read += 1
        customer_id, unique_id = row.text("customer_id"), row.text("customer_unique_id")
        missing = _first_missing({"customer_id": customer_id, "customer_unique_id": unique_id})
        if missing:
            table.discarded[reason_missing(missing)] += 1
        elif customer_id in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add(customer_id)
            table.rows.append((
                customer_id, unique_id, row.text("customer_zip_code_prefix"), row.text("customer_city"),
                row.text("customer_state")))
    return table


def _orders(folder: Path, customer_ids: set[str]) -> TableData:
    table = TableData("orders", "orders", (
        "order_id", "customer_id", "status", "purchased_at", "approved_at", "delivered_to_carrier_at",
        "delivered_to_customer_at", "estimated_delivery_at"))
    seen: set[str] = set()
    for row in _rows(folder, "olist_orders_dataset.csv"):
        table.read += 1
        order_id, customer_id, status = row.text("order_id"), row.text("customer_id"), row.text("order_status")
        missing = _first_missing({"order_id": order_id, "customer_id": customer_id, "status": status})
        if missing:
            table.discarded[reason_missing(missing)] += 1
        elif customer_id not in customer_ids:
            table.discarded[REASON_UNKNOWN_CUSTOMER] += 1
        elif order_id in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add(order_id)
            table.rows.append((
                order_id, customer_id, status, row.timestamp("order_purchase_timestamp"),
                row.timestamp("order_approved_at"), row.timestamp("order_delivered_carrier_date"),
                row.timestamp("order_delivered_customer_date"), row.timestamp("order_estimated_delivery_date")))
    return table


def _items(folder: Path, order_ids: set[str]) -> TableData:
    table = TableData("orders", "order_items", (
        "order_id", "item_number", "product_id", "seller_id", "shipping_limit_at", "price", "freight_value"))
    seen: set[tuple[str, int]] = set()
    for row in _rows(folder, "olist_order_items_dataset.csv"):
        table.read += 1
        order_id, number = row.text("order_id"), row.integer("order_item_id")
        product_id, seller_id = row.text("product_id"), row.text("seller_id")
        price, freight = row.decimal("price"), row.decimal("freight_value")
        missing = _first_missing({
            "order_id": order_id, "item_number": number, "product_id": product_id, "seller_id": seller_id,
            "price": price, "freight_value": freight})
        negative = _first_negative({"price": price, "freight_value": freight})
        if missing:
            table.discarded[reason_missing(missing)] += 1
        elif negative:
            table.discarded[reason_negative(negative)] += 1
        elif order_id not in order_ids:
            table.discarded[REASON_UNKNOWN_ORDER] += 1
        elif (order_id, number) in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add((order_id, number))
            table.rows.append((
                order_id, number, product_id, seller_id, row.timestamp("shipping_limit_date"), price, freight))
    return table


def _payments(folder: Path, order_ids: set[str]) -> TableData:
    table = TableData("orders", "order_payments", (
        "order_id", "payment_sequence", "payment_type", "installments", "payment_value"))
    seen: set[tuple[str, int]] = set()
    for row in _rows(folder, "olist_order_payments_dataset.csv"):
        table.read += 1
        order_id, sequence = row.text("order_id"), row.integer("payment_sequential")
        payment_type, installments = row.text("payment_type"), row.integer("payment_installments")
        value = row.decimal("payment_value")
        missing = _first_missing({
            "order_id": order_id, "payment_sequence": sequence, "payment_type": payment_type,
            "installments": installments, "payment_value": value})
        negative = _first_negative({"installments": installments, "payment_value": value})
        if missing:
            table.discarded[reason_missing(missing)] += 1
        elif negative:
            table.discarded[reason_negative(negative)] += 1
        elif order_id not in order_ids:
            table.discarded[REASON_UNKNOWN_ORDER] += 1
        elif (order_id, sequence) in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add((order_id, sequence))
            table.rows.append((order_id, sequence, payment_type, installments, value))
    return table


def _reviews(folder: Path, order_ids: set[str]) -> TableData:
    """review_id repeats across orders in the source, so there is no duplicate check: the table has its own key."""
    table = TableData("orders", "order_reviews", (
        "review_id", "order_id", "score", "comment_title", "comment_message", "created_at", "answered_at"))
    for row in _rows(folder, "olist_order_reviews_dataset.csv"):
        table.read += 1
        review_id, order_id, score = row.text("review_id"), row.text("order_id"), row.integer("review_score")
        missing = _first_missing({"review_id": review_id, "order_id": order_id, "score": score})
        if missing:
            table.discarded[reason_missing(missing)] += 1
        elif score < 1 or score > 5:
            table.discarded[REASON_SCORE_RANGE] += 1
        elif order_id not in order_ids:
            table.discarded[REASON_UNKNOWN_ORDER] += 1
        else:
            table.rows.append((
                review_id, order_id, score, row.text("review_comment_title"), row.text("review_comment_message"),
                row.timestamp("review_creation_date"), row.timestamp("review_answer_timestamp")))
    return table


def _sellers(folder: Path) -> TableData:
    table = TableData("catalog", "sellers", ("seller_id", "zip_code_prefix", "city", "state"))
    seen: set[str] = set()
    for row in _rows(folder, "olist_sellers_dataset.csv"):
        table.read += 1
        seller_id = row.text("seller_id")
        if seller_id is None:
            table.discarded[reason_missing("seller_id")] += 1
        elif seller_id in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add(seller_id)
            table.rows.append((
                seller_id, row.text("seller_zip_code_prefix"), row.text("seller_city"), row.text("seller_state")))
    return table


def _categories(folder: Path) -> TableData:
    table = TableData("catalog", "product_categories", ("name", "name_en"))
    seen: set[str] = set()
    for row in _rows(folder, "product_category_name_translation.csv"):
        table.read += 1
        name = row.text("product_category_name")
        if name is None:
            table.discarded[reason_missing("name")] += 1
        elif name in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add(name)
            table.rows.append((name, row.text("product_category_name_english")))
    return table


def _products(folder: Path, categories: TableData, prices: dict[str, list[Decimal]]) -> TableData:
    table = TableData("catalog", "products", (
        "product_id", "category_name", "name_length", "description_length", "photos_count", "weight_g", "length_cm",
        "height_cm", "width_cm", "reference_price", "stock"))
    known = {name for name, _ in categories.rows}
    unlisted: set[str] = set()
    seen: set[str] = set()
    for row in _rows(folder, "olist_products_dataset.csv"):
        table.read += 1
        product_id, category = row.text("product_id"), row.text("product_category_name")
        measures = {
            "weight_g": row.integer("product_weight_g"), "length_cm": row.integer("product_length_cm"),
            "height_cm": row.integer("product_height_cm"), "width_cm": row.integer("product_width_cm")}
        negative = _first_negative(measures)
        if product_id is None:
            table.discarded[reason_missing("product_id")] += 1
        elif negative:
            table.discarded[reason_negative(negative)] += 1
        elif product_id in seen:
            table.discarded[REASON_DUPLICATE_KEY] += 1
        else:
            seen.add(product_id)
            if category is not None and category not in known:
                unlisted.add(category)
            table.rows.append((
                product_id, category, row.integer("product_name_lenght"), row.integer("product_description_lenght"),
                row.integer("product_photos_qty"), measures["weight_g"], measures["length_cm"],
                measures["height_cm"], measures["width_cm"], median_price(prices.get(product_id, [])),
                stock_for(product_id)))
    # A category that only products mention keeps its products: it is added with no English name.
    for name in sorted(unlisted):
        categories.rows.append((name, None))
    categories.derived = len(unlisted)
    return table


def transform(folder: Path) -> TransformedDataset:
    customers = _customers(folder)
    orders = _orders(folder, {row[0] for row in customers.rows})
    order_ids = {row[0] for row in orders.rows}
    items = _items(folder, order_ids)
    payments = _payments(folder, order_ids)
    reviews = _reviews(folder, order_ids)

    prices: dict[str, list[Decimal]] = {}
    for _, _, product_id, _, _, price, _ in items.rows:
        prices.setdefault(product_id, []).append(price)

    sellers = _sellers(folder)
    categories = _categories(folder)
    products = _products(folder, categories, prices)

    product_ids = {row[0] for row in products.rows}
    seller_ids = {row[0] for row in sellers.rows}
    orphans = Orphans(
        product=sum(1 for row in items.rows if row[2] not in product_ids),
        seller=sum(1 for row in items.rows if row[3] not in seller_ids),
    )
    return TransformedDataset(
        schemas={
            "orders": [customers, orders, items, payments, reviews],
            "catalog": [categories, products, sellers],
        },
        orphans=orphans,
    )
