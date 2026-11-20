"""Delivery times, delays and reviews. Everything is vectorised on whole columns (research D-11 to D-14).

An order is *delayed* when the real delivery to the customer is later than the estimated date. Only orders with a
delivery date and coherent dates are analysed; every excluded order is counted with exactly one reason.
"""

from dataclasses import dataclass

import numpy as np
import pandas as pd

NO_CATEGORY = "sin categoría"
BUCKET_LABELS = ("a tiempo o antes", "1 a 7 días tarde", "más de 7 días tarde")
LATE_BUCKET_LIMIT_DAYS = 7
REASON_NO_DELIVERY_DATE = "sin_fecha_de_entrega"
REASON_DELIVERED_BEFORE_PURCHASE = "entrega_anterior_a_compra"
REASON_ESTIMATED_BEFORE_PURCHASE = "estimada_anterior_a_compra"
SUMMARY_COLUMNS = ["group", "orders", "mean_delay_days", "delayed_pct", "mean_review_score"]
_DAY = np.timedelta64(1, "D")


@dataclass(frozen=True)
class ExclusionCounts:
    total_orders: int
    analyzed: int
    no_delivery_date: int
    no_delivery_date_by_status: dict[str, int]
    delivered_before_purchase: int
    estimated_before_purchase: int
    # Inconsistencies of the source that are reported but are not an exclusion reason of their own:
    delivered_without_date: int  # status delivered with no delivery date (already counted in no_delivery_date)
    date_with_non_delivered_status: int  # a delivery date on an order whose status is not delivered


@dataclass(frozen=True)
class TimeStatistics:
    analyzed_orders: int
    actual_days: dict[str, float] | None
    estimated_days: dict[str, float] | None
    delay_days: dict[str, float] | None
    early_pct: float | None  # delivered before the estimate
    same_day_pct: float | None  # delivered exactly on the estimate
    late_pct: float | None  # delivered after the estimate


def delay_bucket(delay_days: pd.Series) -> pd.Series:
    """Delay tiers: on time or early (<= 0), up to 7 days late (0, 7] and more than 7 days late; NaN stays NaN."""
    conditions = [
        delay_days <= 0,
        (delay_days > 0) & (delay_days <= LATE_BUCKET_LIMIT_DAYS),
        delay_days > LATE_BUCKET_LIMIT_DAYS,
    ]
    buckets = pd.Series(np.select(conditions, list(BUCKET_LABELS), default=""), index=delay_days.index)
    return buckets.mask(delay_days.isna())


def _items_with_category(tables: dict[str, pd.DataFrame]) -> pd.DataFrame:
    """Every item with its category: the English name, the original one without translation, or NO_CATEGORY."""
    items = tables["orders.order_items"][["order_id", "item_number", "product_id", "price"]]
    products = tables["catalog.products"][["product_id", "category_name"]]
    categories = tables["catalog.product_categories"][["name", "name_en"]].rename(columns={"name": "category_name"})
    merged = items.merge(products, on="product_id", how="left").merge(categories, on="category_name", how="left")
    merged["category"] = merged["name_en"].fillna(merged["category_name"]).fillna(NO_CATEGORY)
    return merged


def multicategory_orders(tables: dict[str, pd.DataFrame]) -> int:
    """Orders whose items belong to more than one category, to size the bias of the primary-category rule."""
    categories_per_order = _items_with_category(tables).groupby("order_id")["category"].nunique()
    return int((categories_per_order > 1).sum())


def _primary_category(tables: dict[str, pd.DataFrame]) -> pd.Series:
    """The category of the most expensive item of each order; a price tie goes to the lowest item number."""
    ranked = _items_with_category(tables).sort_values(
        ["order_id", "price", "item_number"], ascending=[True, False, True]
    )
    return ranked.drop_duplicates("order_id").set_index("order_id")["category"]


def build_delivery_frame(tables: dict[str, pd.DataFrame]) -> pd.DataFrame:
    """One row per order with times, delay, bucket, exclusion reason, customer state, category and review score."""
    orders = tables["orders.orders"]
    customers = tables["orders.customers"][["customer_id", "state"]].rename(columns={"state": "customer_state"})
    frame = orders[
        ["order_id", "customer_id", "status", "purchased_at", "delivered_to_customer_at", "estimated_delivery_at"]
    ].merge(customers, on="customer_id", how="left")

    frame["actual_days"] = (frame["delivered_to_customer_at"] - frame["purchased_at"]) / _DAY
    frame["estimated_days"] = (frame["estimated_delivery_at"] - frame["purchased_at"]) / _DAY
    frame["delay_days"] = (frame["delivered_to_customer_at"] - frame["estimated_delivery_at"]) / _DAY
    frame["is_delayed"] = frame["delay_days"] > 0
    frame["delay_bucket"] = delay_bucket(frame["delay_days"])

    reason = np.select(
        [frame["delivered_to_customer_at"].isna(), frame["actual_days"] < 0, frame["estimated_days"] < 0],
        [REASON_NO_DELIVERY_DATE, REASON_DELIVERED_BEFORE_PURCHASE, REASON_ESTIMATED_BEFORE_PURCHASE],
        default="",
    )
    frame["exclusion_reason"] = pd.Series(reason, index=frame.index).mask(reason == "")
    frame["analyzed"] = frame["exclusion_reason"].isna()

    frame["primary_category"] = frame["order_id"].map(_primary_category(tables)).fillna(NO_CATEGORY)
    review_mean = tables["orders.order_reviews"].groupby("order_id")["score"].mean().astype("float64")
    frame["review_score_mean"] = frame["order_id"].map(review_mean)
    return frame


def exclusion_counts(frame: pd.DataFrame) -> ExclusionCounts:
    reason = frame["exclusion_reason"]
    no_date = frame[reason == REASON_NO_DELIVERY_DATE]
    delivered = frame["status"] == "delivered"
    has_date = frame["delivered_to_customer_at"].notna()
    return ExclusionCounts(
        total_orders=len(frame),
        analyzed=int(frame["analyzed"].sum()),
        no_delivery_date=len(no_date),
        no_delivery_date_by_status=dict(sorted(no_date["status"].value_counts().items())),
        delivered_before_purchase=int((reason == REASON_DELIVERED_BEFORE_PURCHASE).sum()),
        estimated_before_purchase=int((reason == REASON_ESTIMATED_BEFORE_PURCHASE).sum()),
        delivered_without_date=int((delivered & ~has_date).sum()),
        date_with_non_delivered_status=int((~delivered & has_date).sum()),
    )


def _describe(series: pd.Series) -> dict[str, float]:
    return {"mean": float(series.mean()), "median": float(series.median()), "p95": float(series.quantile(0.95))}


def time_statistics(frame: pd.DataFrame) -> TimeStatistics:
    """Distribution of real and estimated times and the share of orders early, on the estimated day and late."""
    analysed = frame[frame["analyzed"]]
    if analysed.empty:
        return TimeStatistics(0, None, None, None, None, None, None)
    delay = analysed["delay_days"]
    return TimeStatistics(
        analyzed_orders=len(analysed),
        actual_days=_describe(analysed["actual_days"]),
        estimated_days=_describe(analysed["estimated_days"]),
        delay_days=_describe(delay),
        early_pct=float((delay < 0).mean() * 100),
        same_day_pct=float((delay == 0).mean() * 100),
        late_pct=float((delay > 0).mean() * 100),
    )


def delay_summary(frame: pd.DataFrame, group_column: str, min_orders: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Delay by group: (ranking, small groups). Groups under ``min_orders`` do not enter the ranking."""
    analysed = frame[frame["analyzed"]]
    if analysed.empty:
        return pd.DataFrame(columns=SUMMARY_COLUMNS), pd.DataFrame(columns=SUMMARY_COLUMNS)
    grouped = (
        analysed.groupby(group_column)
        .agg(
            orders=("order_id", "size"),
            mean_delay_days=("delay_days", "mean"),
            delayed_share=("is_delayed", "mean"),
            mean_review_score=("review_score_mean", "mean"),
        )
        .reset_index()
        .rename(columns={group_column: "group"})
    )
    grouped["delayed_pct"] = grouped["delayed_share"] * 100
    grouped = grouped[SUMMARY_COLUMNS]
    enough = grouped["orders"] >= min_orders
    ranking = grouped[enough].sort_values(["delayed_pct", "orders", "group"], ascending=[False, False, True])
    small = grouped[~enough].sort_values(["orders", "group"], ascending=[False, True])
    return ranking.reset_index(drop=True), small.reset_index(drop=True)


def score_distribution(reviews: pd.DataFrame) -> pd.Series:
    """How many reviews have each score from 1 to 5 (zeros included)."""
    counts = reviews["score"].astype("int64").value_counts().reindex([1, 2, 3, 4, 5], fill_value=0)
    return counts.rename("reseñas")


def score_by_delay_bucket(frame: pd.DataFrame) -> pd.DataFrame:
    """Mean and median review score of the analysed orders in each delay tier, every tier listed."""
    analysed = frame[frame["analyzed"]]
    grouped = analysed.groupby("delay_bucket").agg(
        orders=("order_id", "size"),
        mean_score=("review_score_mean", "mean"),
        median_score=("review_score_mean", "median"),
    )
    result = grouped.reindex(list(BUCKET_LABELS))
    result["orders"] = result["orders"].fillna(0).astype(int)
    return result.rename_axis("bucket").reset_index()
