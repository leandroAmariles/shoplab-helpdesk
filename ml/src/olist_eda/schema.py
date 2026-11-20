"""What the analysis knows about the eight tables: natural keys, expected nulls and plausible ranges.

Nulls are reported as *expected* (the business explains them: a canceled order is never delivered) or *unexpected*.
A column without a rule treats every null as unexpected, so a new problem never goes unnoticed (research D-8).
"""

from collections.abc import Callable
from dataclasses import dataclass, field

import pandas as pd

from olist_eda.db import EXPECTED_TABLES, qualified

NullRule = Callable[[pd.DataFrame], pd.Series]  # mask of the UNEXPECTED nulls of a column
RangeRule = Callable[[pd.Series], pd.Series]  # mask of the implausible values

# Window of the public dataset; a date outside it is reported as implausible.
DATASET_START = pd.Timestamp("2016-01-01")
DATASET_END_EXCLUSIVE = pd.Timestamp("2019-01-01")

STATUSES_WITHOUT_APPROVAL = frozenset({"created", "canceled"})
STATUSES_WITH_CARRIER_DATE = frozenset({"shipped", "delivered"})


@dataclass(frozen=True)
class TableSpec:
    schema: str
    name: str
    natural_key: tuple[str, ...]
    surrogate_columns: tuple[str, ...] = ()
    null_rules: dict[str, NullRule] = field(default_factory=dict)

    @property
    def qualified(self) -> str:
        return qualified(self.schema, self.name)


def _always_expected(frame: pd.DataFrame) -> pd.Series:
    return pd.Series(False, index=frame.index)


def _null_when_status(column: str, statuses: frozenset[str], *, negate: bool = False) -> NullRule:
    """Unexpected nulls: the column is null AND the order status is (or, with ``negate``, is not) in ``statuses``."""

    def rule(frame: pd.DataFrame) -> pd.Series:
        in_statuses = frame["status"].isin(statuses)
        return frame[column].isna() & (~in_statuses if negate else in_statuses)

    return rule


_ORDER_RULES: dict[str, NullRule] = {
    "approved_at": _null_when_status("approved_at", STATUSES_WITHOUT_APPROVAL, negate=True),
    "delivered_to_carrier_at": _null_when_status("delivered_to_carrier_at", STATUSES_WITH_CARRIER_DATE),
    "delivered_to_customer_at": _null_when_status("delivered_to_customer_at", frozenset({"delivered"})),
}

_SPECS = (
    TableSpec("orders", "customers", ("customer_id",)),
    TableSpec("orders", "orders", ("order_id",), null_rules=_ORDER_RULES),
    TableSpec("orders", "order_items", ("order_id", "item_number")),
    TableSpec("orders", "order_payments", ("order_id", "payment_sequence")),
    TableSpec(
        "orders",
        "order_reviews",
        ("review_id",),  # not unique in the source: a repeated review_id is measured, not assumed impossible
        surrogate_columns=("id",),
        null_rules={name: _always_expected for name in ("comment_title", "comment_message", "answered_at")},
    ),
    TableSpec("catalog", "product_categories", ("name",), null_rules={"name_en": _always_expected}),
    TableSpec("catalog", "products", ("product_id",)),
    TableSpec("catalog", "sellers", ("seller_id",)),
)

TABLES: dict[str, TableSpec] = {spec.qualified: spec for spec in _SPECS}
assert list(TABLES) == [qualified(schema, table) for schema, table in EXPECTED_TABLES]

RANGE_RULES: dict[tuple[str, str], RangeRule] = {
    ("orders.order_items", "price"): lambda series: series <= 0,
    ("orders.order_items", "freight_value"): lambda series: series < 0,
    ("orders.order_reviews", "score"): lambda series: (series < 1) | (series > 5),
    ("catalog.products", "weight_g"): lambda series: series <= 0,
    ("catalog.products", "length_cm"): lambda series: series <= 0,
    ("catalog.products", "height_cm"): lambda series: series <= 0,
    ("catalog.products", "width_cm"): lambda series: series <= 0,
}


def outside_dataset_window(series: pd.Series) -> pd.Series:
    """Implausible dates: before 2016 or after 2018 (NaT is never flagged here: it is a null, not a bad date)."""
    return (series < DATASET_START) | (series >= DATASET_END_EXCLUSIVE)


def unexpected_null_mask(spec: TableSpec, frame: pd.DataFrame, column: str) -> pd.Series:
    rule = spec.null_rules.get(column)
    return rule(frame) if rule is not None else frame[column].isna()
