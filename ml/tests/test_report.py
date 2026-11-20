import json
import re

import pandas as pd
import pytest

from olist_eda import categorical, delivery, findings, profiling, report
from olist_eda.categorical import OrderToolStates
from olist_eda.config import NOTEBOOK_DATA_DIR
from olist_eda.policies import PolicyReference
from olist_eda.schema import TABLES


def test_show_table_never_shows_more_than_five_rows():
    frame = pd.DataFrame({"value": range(20)})

    assert len(report.show_table(frame)) == 5
    assert len(report.show_table(frame, max_rows=2)) == 2


def test_show_table_hides_identifier_columns():
    frame = pd.DataFrame(
        {
            "order_id": ["a" * 32],
            "customer_unique_id": ["b" * 32],
            "review_id": ["c" * 32],
            "product_id": ["d" * 32],
            "status": ["delivered"],
        }
    )

    shown = report.show_table(frame)

    assert list(shown.columns) == ["status"]


def test_show_table_does_not_modify_its_input():
    frame = pd.DataFrame({"order_id": ["x"], "status": ["delivered"]})

    report.show_table(frame)

    assert list(frame.columns) == ["order_id", "status"]


def test_show_table_accepts_an_empty_frame():
    assert report.show_table(pd.DataFrame({"status": []})).empty


def test_formatting_helpers():
    assert report.format_pct(0.12345) == "12.35 %"
    assert report.format_pct(float("nan")) == "—"
    assert report.format_int(1234567) == "1,234,567"


def test_data_scale_distinguishes_the_full_dataset_from_a_sample():
    assert report.data_scale(99_441) == "full"
    assert report.data_scale(1_000) == "full"
    assert report.data_scale(999) == "sample"
    assert report.data_scale(3) == "sample"


def test_profile_view_formats_ranges_for_reading(tables):
    view = report.profile_view(profiling.profile_table(TABLES["orders.order_items"], tables["orders.order_items"]))

    assert list(view.columns) == [
        "columna", "tipo", "nulos", "% nulos", "nulos inesperados", "mín", "p01", "p50", "p99", "máx", "implausibles",
    ]
    price = view[view["columna"] == "price"].iloc[0]
    assert (price["mín"], price["p50"], price["máx"]) == ("0.00", "35.00", "100.00")
    assert price["implausibles"] == 1
    shipping = view[view["columna"] == "shipping_limit_at"].iloc[0]
    assert shipping["mín"] == "2017-01-03"
    text = view[view["columna"] == "order_id"].iloc[0]
    assert text["mín"] == "—"


def test_table_headline_is_generated_from_the_profile(tables):
    reviews = profiling.profile_table(TABLES["orders.order_reviews"], tables["orders.order_reviews"])

    assert report.table_headline(reviews) == (
        "orders.order_reviews: 9 filas · duplicados por clave: 2 · duplicados de fila completa: 1"
    )


# -- figures (part 2) ----------------------------------------------------------------------------------------------


def _delivery(tables):
    return delivery.build_delivery_frame(tables)


def test_the_delivery_time_figure_has_three_panels_and_moderate_resolution(tables):
    figure = report.plot_delivery_times(_delivery(tables))

    assert figure is not None
    assert len(figure.axes) == 3
    assert figure.dpi == 100


def test_there_is_no_figure_without_analysed_orders(tables):
    tables["orders.orders"]["delivered_to_customer_at"] = pd.NaT

    assert report.plot_delivery_times(_delivery(tables)) is None


def test_the_delay_ranking_figure_shows_the_groups(tables):
    ranking, _ = delivery.delay_summary(_delivery(tables), "customer_state", min_orders=1)

    figure = report.plot_delay_ranking(ranking, "Retraso por estado del cliente")

    assert figure is not None
    assert figure.dpi == 100
    assert [label.get_text() for label in figure.axes[0].get_yticklabels()] == ["SP", "RJ"]


def test_there_is_no_ranking_figure_for_an_empty_ranking():
    assert report.plot_delay_ranking(pd.DataFrame(), "vacío") is None


def test_the_review_figure_has_the_distribution_and_the_mean_by_delay(tables):
    distribution = delivery.score_distribution(tables["orders.order_reviews"])
    by_bucket = delivery.score_by_delay_bucket(_delivery(tables))

    figure = report.plot_review_scores(distribution, by_bucket)

    assert figure is not None
    assert len(figure.axes) == 2
    assert figure.dpi == 100


def test_there_is_no_review_figure_without_reviews():
    empty = pd.Series([0, 0, 0, 0, 0], index=[1, 2, 3, 4, 5])

    assert report.plot_review_scores(empty, pd.DataFrame()) is None


def test_summary_view_is_readable(tables):
    ranking, _ = delivery.delay_summary(_delivery(tables), "customer_state", min_orders=1)

    view = report.summary_view(ranking)

    assert list(view.columns) == ["grupo", "pedidos", "retraso medio (días)", "% con retraso", "puntuación media"]
    assert view.iloc[0].tolist() == ["RJ", 2, 5.0, 50.0, 2.25]
    assert report.summary_view(pd.DataFrame(columns=delivery.SUMMARY_COLUMNS)).empty


# -- summary (part 3) ------------------------------------------------------------------------------------------------

STATES = frozenset({"created", "approved", "invoiced", "processing", "shipped", "delivered", "unavailable", "canceled"})
GENERATED_AT = "2026-11-20T21:30:00Z"


@pytest.fixture
def summary(tables):
    reference = PolicyReference(frozenset({"credit_card", "debit_card", "boleto", "voucher"}), STATES, {})
    tool = OrderToolStates("test", ("created", "approved", "invoiced", "processing", "shipped", "delivered", "lost"))
    actions = findings.load_actions(NOTEBOOK_DATA_DIR / "discrepancy-actions.toml")
    deliveries = delivery.build_delivery_frame(tables)
    return report.build_summary(
        tables,
        profiles=profiling.profile_tables(tables),
        frequencies=categorical.value_frequencies(tables, reference, tool),
        absent=categorical.absent_values(tables, reference, tool),
        exclusions=delivery.exclusion_counts(deliveries),
        stats=delivery.time_statistics(deliveries),
        distribution=delivery.score_distribution(tables["orders.order_reviews"]),
        by_bucket=delivery.score_by_delay_bucket(deliveries),
        found=findings.with_actions(findings.detect_findings(tables, reference, tool), actions),
        generated_at=GENERATED_AT,
    )


def test_summary_has_the_shape_of_the_contract(summary):
    assert summary["schema_version"] == 1
    assert summary["generated_at"] == GENERATED_AT
    assert summary["data_scale"] == "sample"  # 12 orders
    assert list(summary) == ["schema_version", "generated_at", "data_scale", "tables", "values", "delivery", "reviews",
                             "findings"]


def test_summary_has_exactly_the_eight_tables(summary):
    assert [item["table"] for item in summary["tables"]] == list(TABLES)
    reviews = summary["tables"][4]
    assert reviews == {"table": "orders.order_reviews", "rows": 9, "key_duplicates": 2, "row_duplicates": 1,
                       "columns_with_unexpected_nulls": []}
    assert summary["tables"][6]["columns_with_unexpected_nulls"] == [
        "category_name", "name_length", "description_length", "photos_count",
    ]


def test_summary_lists_every_distinct_value_and_the_absent_ones(summary):
    payments = summary["values"]["payment_type"]

    assert [item["value"] for item in payments] == ["credit_card", "boleto", "voucher", "debit_card", "not_defined"]
    assert payments[0] == {"value": "credit_card", "count": 6, "share": 0.4615, "declared_by_policy": True,
                           "mentioned_in": []}
    assert "covered_by_order_tool" not in payments[0]
    statuses = summary["values"]["order_status"]
    assert statuses[0]["covered_by_order_tool"] is True
    assert summary["values"]["absent_from_data"] == {
        "payment_type": [],
        "order_status": ["approved", "invoiced", "processing"],
    }


def test_summary_delivery_block(summary):
    block = summary["delivery"]

    assert block["analyzed_orders"] == 5
    assert block["excluded"] == {"no_delivery_date": 5, "delivered_before_purchase": 1,
                                 "estimated_before_purchase": 1, "delivered_without_date": 1}
    assert block["actual_days"] == {"mean": 11.0, "median": 9.0, "p95": 21.4}
    assert (block["early_pct"], block["same_day_pct"], block["late_pct"]) == (40.0, 20.0, 40.0)


def test_summary_reviews_block(summary):
    block = summary["reviews"]

    assert block["count"] == 9
    assert block["score_distribution"] == {"1": 1, "2": 1, "3": 3, "4": 2, "5": 2}
    assert block["mean_score_by_delay_bucket"] == {
        "a tiempo o antes": 4.0, "1 a 7 días tarde": 4.0, "más de 7 días tarde": 1.5,
    }


def test_every_finding_has_a_non_empty_action_and_they_are_ordered(summary):
    rows = summary["findings"]

    assert rows
    assert all(row["action"] and row["target"] for row in rows)
    assert set(rows[0]) == {"kind", "subject", "area", "count", "share", "target", "action"}
    order = {area: position for position, area in enumerate(findings.AREA_ORDER)}
    keys = [(order[row["area"]], -row["count"]) for row in rows]
    assert keys == sorted(keys)


def test_decimals_are_rounded_and_nothing_looks_like_an_identifier(summary):
    text = json.dumps(summary, ensure_ascii=False)

    assert not re.search(r"[0-9a-f]{32}", text)
    for number in re.findall(r"\d+\.\d+", text):
        assert len(number.split(".")[1]) <= 4, number


def test_summary_is_written_and_read_back_unchanged(summary, tmp_path):
    path = tmp_path / "olist-eda-summary.json"

    report.write_summary(summary, path)

    assert report.read_summary(path) == summary
    assert path.read_text(encoding="utf-8").endswith(chr(10))
    assert "á" in path.read_text(encoding="utf-8")  # not escaped: the file is meant to be read


def test_a_summary_with_a_finding_without_action_is_rejected(summary):
    summary["findings"][0]["action"] = ""

    with pytest.raises(ValueError, match="action"):
        report.validate_summary(summary)


def test_a_summary_without_the_eight_tables_is_rejected(summary):
    summary["tables"] = summary["tables"][:7]

    with pytest.raises(ValueError, match="8"):
        report.validate_summary(summary)


def test_a_summary_containing_an_identifier_is_rejected(summary):
    summary["findings"][0]["subject"] = "a" * 32

    with pytest.raises(ValueError, match="identificador"):
        report.validate_summary(summary)


def test_findings_view_is_a_readable_table(summary, tables):
    actions = findings.load_actions(NOTEBOOK_DATA_DIR / "discrepancy-actions.toml")
    reference = PolicyReference(frozenset({"credit_card"}), STATES, {})
    tool = OrderToolStates("test", ("created",))
    found = findings.with_actions(findings.detect_findings(tables, reference, tool), actions)

    view = report.findings_view(found)

    assert list(view.columns) == ["área", "tipo", "sujeto", "cantidad", "%", "impacto", "destino", "acción"]
    assert len(view) == len(found)
    assert set(view["área"]) <= {"calidad", "valores", "entregas"}
    assert (view["acción"].str.len() > 0).all()
    assert report.findings_view([]).empty
