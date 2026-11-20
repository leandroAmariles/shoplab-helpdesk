"""Quality profile of each table: nulls, duplicates, types, ranges. Everything works on whole columns (FR-005)."""

from dataclasses import asdict, dataclass
from typing import Any

import pandas as pd

from olist_eda.schema import RANGE_RULES, TABLES, TableSpec, outside_dataset_window, unexpected_null_mask

PERCENTILES = (0.01, 0.25, 0.5, 0.75, 0.99)
RANGE_FIELDS = ("min", "max", "p01", "p25", "p50", "p75", "p99")


@dataclass(frozen=True)
class ColumnProfile:
    name: str
    dtype: str
    null_count: int
    null_pct: float  # 0-100
    unexpected_null_count: int
    min: Any
    max: Any
    p01: Any
    p25: Any
    p50: Any
    p75: Any
    p99: Any
    implausible_count: int


@dataclass(frozen=True)
class TableProfile:
    table: str
    rows: int
    key_duplicates: int  # repeated rows by natural key
    row_duplicates: int  # repeated rows by the whole row, without surrogate columns
    columns: tuple[ColumnProfile, ...]

    def to_frame(self) -> pd.DataFrame:
        frame = pd.DataFrame([asdict(item) for item in self.columns])
        return frame.rename(columns={"name": "column"})


def _is_rangeable(series: pd.Series) -> bool:
    """Numeric (not boolean) and date columns have a range; text columns do not."""
    dtype = series.dtype
    if pd.api.types.is_bool_dtype(dtype):
        return False
    return pd.api.types.is_numeric_dtype(dtype) or pd.api.types.is_datetime64_any_dtype(dtype)


def _scalar(value: Any, series: pd.Series) -> Any:
    return value if pd.api.types.is_datetime64_any_dtype(series.dtype) else float(value)


def _range(series: pd.Series) -> dict[str, Any]:
    empty = dict.fromkeys(RANGE_FIELDS)
    if not _is_rangeable(series):
        return empty
    clean = series.dropna()
    if clean.empty:
        return empty
    quantiles = clean.quantile(list(PERCENTILES))
    values = [clean.min(), clean.max(), *quantiles.tolist()]
    return {name: _scalar(value, clean) for name, value in zip(RANGE_FIELDS, values, strict=True)}


def _implausible_count(table: str, column: str, series: pd.Series) -> int:
    rule = RANGE_RULES.get((table, column))
    if rule is not None:
        mask = rule(series)
    elif pd.api.types.is_datetime64_any_dtype(series.dtype):
        mask = outside_dataset_window(series)
    else:
        return 0
    return int(mask.fillna(False).astype(bool).sum())


def _profile_column(spec: TableSpec, frame: pd.DataFrame, name: str) -> ColumnProfile:
    series = frame[name]
    rows = len(frame)
    null_count = int(series.isna().sum())
    return ColumnProfile(
        name=name,
        dtype=str(series.dtype),
        null_count=null_count,
        null_pct=null_count / rows * 100 if rows else 0.0,
        unexpected_null_count=int(unexpected_null_mask(spec, frame, name).sum()),
        implausible_count=_implausible_count(spec.qualified, name, series),
        **_range(series),
    )


def profile_table(spec: TableSpec, frame: pd.DataFrame) -> TableProfile:
    """The profile of one table. Duplicates are measured by natural key and by whole row (without surrogate ids)."""
    without_surrogate = frame.drop(columns=list(spec.surrogate_columns))
    return TableProfile(
        table=spec.qualified,
        rows=len(frame),
        key_duplicates=int(frame.duplicated(subset=list(spec.natural_key)).sum()),
        row_duplicates=int(without_surrogate.duplicated().sum()),
        columns=tuple(_profile_column(spec, frame, name) for name in frame.columns),
    )


def profile_tables(tables: dict[str, pd.DataFrame]) -> dict[str, TableProfile]:
    return {name: profile_table(spec, tables[name]) for name, spec in TABLES.items()}


def informative_repetitions(tables: dict[str, pd.DataFrame]) -> dict[str, int]:
    """Repetitions the source allows on purpose: they are explained in the notebook, they are not defects."""
    customers = tables["orders.customers"]
    reviews = tables["orders.order_reviews"]
    customer_ids_per_person = customers.groupby("customer_unique_id")["customer_id"].nunique()
    reviews_per_order = reviews.groupby("order_id").size()
    return {
        "persons_with_several_customer_ids": int((customer_ids_per_person > 1).sum()),
        "orders_with_several_reviews": int((reviews_per_order > 1).sum()),
    }
