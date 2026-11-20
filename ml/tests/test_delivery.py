import math

import numpy as np
import pandas as pd
import pytest

from olist_eda import delivery

ON_TIME = "a tiempo o antes"
LATE = "1 a 7 días tarde"
VERY_LATE = "más de 7 días tarde"


@pytest.fixture
def frame(tables):
    return delivery.build_delivery_frame(tables).set_index("order_id")


def test_one_row_per_order(tables):
    result = delivery.build_delivery_frame(tables)

    assert len(result) == 12
    assert result["order_id"].is_unique


def test_times_are_measured_in_days_and_delay_is_positive_when_late(frame):
    assert frame.loc["o1", "actual_days"] == 7.0
    assert frame.loc["o1", "estimated_days"] == 9.0
    assert frame.loc["o1", "delay_days"] == -2.0
    assert frame.loc["o2", "delay_days"] == 2.0
    assert frame.loc["o3", "delay_days"] == 15.0
    assert frame.loc["o8", "delay_days"] == 0.0
    assert frame.loc["o11", "delay_days"] == -5.0


def test_an_order_is_delayed_only_when_delivered_after_the_estimate(frame):
    assert frame.loc[["o1", "o2", "o3", "o8", "o11"], "is_delayed"].tolist() == [False, True, True, False, False]


def test_an_order_without_delivery_date_has_no_times(frame):
    assert frame.loc["o4", ["actual_days", "delay_days"]].isna().all()
    assert not frame.loc["o4", "is_delayed"]


def test_delay_buckets_at_their_boundaries():
    delay = pd.Series([-1.0, 0.0, 0.0001, 7.0, 7.0001, np.nan])

    buckets = delivery.delay_bucket(delay)

    assert buckets.iloc[:5].tolist() == [ON_TIME, ON_TIME, LATE, LATE, VERY_LATE]
    assert pd.isna(buckets.iloc[5])


def test_the_analysed_population_has_a_delivery_date_and_coherent_dates(frame):
    assert sorted(frame.index[frame["analyzed"]]) == ["o1", "o11", "o2", "o3", "o8"]


def test_every_excluded_order_has_exactly_one_reason(frame):
    reasons = frame.loc[~frame["analyzed"], "exclusion_reason"]

    assert reasons.notna().all()
    assert frame.loc[frame["analyzed"], "exclusion_reason"].isna().all()
    assert reasons.to_dict() == {
        "o4": "sin_fecha_de_entrega",
        "o5": "sin_fecha_de_entrega",
        "o6": "sin_fecha_de_entrega",
        "o7": "entrega_anterior_a_compra",
        "o9": "estimada_anterior_a_compra",
        "o10": "sin_fecha_de_entrega",
        "o12": "sin_fecha_de_entrega",
    }


def test_exclusions_are_counted_by_reason_and_the_total_adds_up(frame):
    counts = delivery.exclusion_counts(frame.reset_index())

    assert counts.total_orders == 12
    assert counts.analyzed == 5
    assert counts.no_delivery_date == 5
    assert counts.no_delivery_date_by_status == {
        "canceled": 1, "created": 1, "delivered": 1, "shipped": 1, "unavailable": 1,
    }
    assert counts.delivered_before_purchase == 1
    assert counts.estimated_before_purchase == 1
    excluded = counts.no_delivery_date + counts.delivered_before_purchase + counts.estimated_before_purchase
    assert counts.analyzed + excluded == counts.total_orders


def test_inconsistencies_that_are_not_exclusions_are_counted_too(frame):
    counts = delivery.exclusion_counts(frame.reset_index())

    assert counts.delivered_without_date == 1  # o4: status delivered, no delivery date
    assert counts.date_with_non_delivered_status == 1  # o11: shipped, but with a delivery date


def test_time_statistics_of_the_analysed_orders(frame):
    stats = delivery.time_statistics(frame.reset_index())

    assert stats.analyzed_orders == 5
    assert stats.actual_days == pytest.approx({"mean": 11.0, "median": 9.0, "p95": 21.4})
    assert stats.estimated_days["mean"] == 9.0
    assert stats.early_pct == pytest.approx(40.0)
    assert stats.same_day_pct == pytest.approx(20.0)
    assert stats.late_pct == pytest.approx(40.0)
    assert stats.early_pct + stats.same_day_pct + stats.late_pct == pytest.approx(100.0)


def test_without_analysed_orders_the_statistics_are_empty_not_an_error(tables):
    tables["orders.orders"]["delivered_to_customer_at"] = pd.NaT

    stats = delivery.time_statistics(delivery.build_delivery_frame(tables))

    assert stats.analyzed_orders == 0
    assert stats.actual_days is None
    assert stats.late_pct is None


def test_the_customer_state_comes_from_the_customer_of_the_order(frame):
    assert frame.loc[["o1", "o3", "o11"], "customer_state"].tolist() == ["SP", "RJ", "RJ"]


def test_primary_category_is_the_one_of_the_most_expensive_item(frame):
    assert frame.loc["o1", "primary_category"] == "category a"  # p1 (100) beats p2 (50)


def test_a_price_tie_is_broken_by_the_lowest_item_number(frame):
    assert frame.loc["o2", "primary_category"] == "category b"  # p3 and p4 cost 80; item 1 wins


def test_a_category_without_translation_shows_its_original_name(tables):
    tables["orders.order_items"].loc[tables["orders.order_items"]["order_id"] == "o9", "product_id"] = "p4"

    assert delivery.build_delivery_frame(tables).set_index("order_id").loc["o9", "primary_category"] == "cat_c"


def test_missing_category_missing_product_and_no_items_are_labelled(frame):
    assert frame.loc["o4", "primary_category"] == delivery.NO_CATEGORY  # p5 has no category
    assert frame.loc["o7", "primary_category"] == delivery.NO_CATEGORY  # p_missing is not in catalog
    assert frame.loc["o12", "primary_category"] == delivery.NO_CATEGORY  # an order with no items


def test_orders_with_products_of_several_categories_are_counted(tables):
    assert delivery.multicategory_orders(tables) == 2  # o1 (a, b) and o2 (b, c)


def test_the_review_score_of_an_order_is_the_mean_of_its_reviews(frame):
    assert frame.loc["o3", "review_score_mean"] == 1.5
    assert frame.loc["o1", "review_score_mean"] == 5.0
    assert pd.isna(frame.loc["o5", "review_score_mean"])


def test_delay_by_customer_state_ranks_the_worst_first(frame):
    ranking, small = delivery.delay_summary(frame.reset_index(), "customer_state", min_orders=1)

    assert ranking["group"].tolist() == ["RJ", "SP"]
    rj = ranking.set_index("group").loc["RJ"]
    assert rj["orders"] == 2
    assert rj["mean_delay_days"] == 5.0
    assert rj["delayed_pct"] == 50.0
    assert rj["mean_review_score"] == pytest.approx(2.25)
    sp = ranking.set_index("group").loc["SP"]
    assert (sp["orders"], sp["mean_delay_days"], sp["delayed_pct"]) == (3, 0.0, pytest.approx(100 / 3))
    assert small.empty


def test_delay_by_category(frame):
    ranking, _ = delivery.delay_summary(frame.reset_index(), "primary_category", min_orders=1)

    by_group = ranking.set_index("group")
    assert by_group.loc["category a", "orders"] == 2
    assert by_group.loc["category a", "mean_delay_days"] == 6.5
    assert by_group.loc["category b", "orders"] == 3
    assert by_group.loc["category b", "mean_delay_days"] == -1.0


def test_groups_below_the_threshold_are_kept_apart_from_the_ranking(frame):
    ranking, small = delivery.delay_summary(frame.reset_index(), "customer_state", min_orders=3)

    assert ranking["group"].tolist() == ["SP"]
    assert small["group"].tolist() == ["RJ"]
    assert small.iloc[0]["orders"] == 2


def test_when_no_group_reaches_the_threshold_the_ranking_is_empty(frame):
    ranking, small = delivery.delay_summary(frame.reset_index(), "customer_state", min_orders=100)

    assert ranking.empty
    assert len(small) == 2


def test_the_summary_of_an_empty_population_is_empty(tables):
    tables["orders.orders"]["delivered_to_customer_at"] = pd.NaT

    ranking, small = delivery.delay_summary(delivery.build_delivery_frame(tables), "customer_state", min_orders=1)

    assert ranking.empty
    assert small.empty


def test_score_distribution_counts_every_score_including_zeros(tables):
    distribution = delivery.score_distribution(tables["orders.order_reviews"])

    assert distribution.index.tolist() == [1, 2, 3, 4, 5]
    assert distribution.tolist() == [1, 1, 3, 2, 2]
    assert distribution.sum() == 9


def test_mean_score_by_delay_bucket(frame):
    result = delivery.score_by_delay_bucket(frame.reset_index()).set_index("bucket")

    assert result.index.tolist() == [ON_TIME, LATE, VERY_LATE]
    assert result.loc[ON_TIME, "orders"] == 3
    assert result.loc[ON_TIME, "mean_score"] == 4.0
    assert result.loc[LATE, "mean_score"] == 4.0
    assert result.loc[VERY_LATE, "mean_score"] == 1.5


def test_an_empty_bucket_is_listed_with_no_score(frame):
    only_on_time = frame.reset_index()
    only_on_time = only_on_time[only_on_time["delay_bucket"] == ON_TIME]

    result = delivery.score_by_delay_bucket(only_on_time).set_index("bucket")

    assert result.loc[LATE, "orders"] == 0
    assert math.isnan(result.loc[LATE, "mean_score"])
