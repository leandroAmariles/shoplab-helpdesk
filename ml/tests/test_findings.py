import pytest

from olist_eda import findings
from olist_eda.categorical import OrderToolStates
from olist_eda.config import NOTEBOOK_DATA_DIR
from olist_eda.errors import MissingActionError
from olist_eda.findings import Finding
from olist_eda.policies import PolicyReference

ALL_STATES = frozenset(
    {"created", "approved", "invoiced", "processing", "shipped", "delivered", "unavailable", "canceled"}
)


@pytest.fixture
def reference():
    return PolicyReference(
        declared_payment_types=frozenset({"credit_card", "debit_card", "boleto", "voucher"}),
        declared_order_states=ALL_STATES,
        mentions={"credit_card": ("payments-01-payment-methods",)},
    )


@pytest.fixture
def tool():
    # does not cover `unavailable` and promises a `lost` state no order has
    return OrderToolStates(
        source="test",
        states=("created", "approved", "invoiced", "processing", "shipped", "delivered", "canceled", "lost"),
    )


@pytest.fixture
def detected(tables, reference, tool):
    return findings.detect_findings(tables, reference, tool)


def of_kind(items, kind):
    return {item.subject: item for item in items if item.kind == kind}


def test_the_fixture_triggers_every_kind_of_finding(detected):
    assert {item.kind for item in detected} == set(findings.KINDS)
    assert len(findings.KINDS) == 12


def test_a_value_no_policy_declares_or_mentions(detected):
    found = of_kind(detected, "value_not_in_policies")

    assert list(found) == ["payment_type:not_defined"]
    assert found["payment_type:not_defined"].count == 1
    assert found["payment_type:not_defined"].share == pytest.approx(1 / 13)
    assert found["payment_type:not_defined"].area == "values"
    assert "not_defined" in found["payment_type:not_defined"].impact


def test_policy_and_tool_values_that_do_not_exist_in_the_data(detected):
    assert set(of_kind(detected, "policy_value_not_in_data")) == {
        "order_status:approved", "order_status:invoiced", "order_status:processing",
    }
    assert set(of_kind(detected, "tool_state_not_in_data")) == {
        "order_status:approved", "order_status:invoiced", "order_status:processing", "order_status:lost",
    }
    assert all(item.count == 0 and item.share == 0.0 for item in of_kind(detected, "tool_state_not_in_data").values())


def test_a_status_the_order_tool_does_not_cover(detected):
    found = of_kind(detected, "status_not_in_order_tool")

    assert list(found) == ["order_status:unavailable"]
    assert found["order_status:unavailable"].count == 1


def test_unexpected_nulls_are_reported_per_column(detected):
    found = of_kind(detected, "unexpected_nulls")

    assert set(found) == {
        "catalog.products.category_name",
        "catalog.products.name_length",
        "catalog.products.description_length",
        "catalog.products.photos_count",
    }
    assert found["catalog.products.category_name"].count == 1
    assert found["catalog.products.category_name"].share == pytest.approx(1 / 5)


def test_the_delivery_date_is_not_reported_twice(detected):
    assert "orders.orders.delivered_to_customer_at" not in of_kind(detected, "unexpected_nulls")
    assert "orders.orders.delivered_to_customer_at" in of_kind(detected, "delivered_without_date")


def test_only_repeated_whole_rows_are_a_defect_not_a_repeated_review_id(detected):
    found = of_kind(detected, "duplicate_rows")

    assert list(found) == ["orders.order_reviews:fila_completa"]
    assert found["orders.order_reviews:fila_completa"].count == 1
    assert not any("review_id" in item.subject for item in detected)


def test_orphan_references(detected):
    found = of_kind(detected, "orphan_reference")

    assert set(found) == {
        "order_items.product_id -> catalog.products",
        "order_items.seller_id -> catalog.sellers",
    }
    assert found["order_items.product_id -> catalog.products"].count == 1
    assert found["order_items.product_id -> catalog.products"].share == pytest.approx(1 / 12)


def test_incoherent_dates(detected):
    found = of_kind(detected, "incoherent_dates")

    assert set(found) == {
        "orders.orders:entrega_anterior_a_compra",
        "orders.orders:estimada_anterior_a_compra",
    }
    assert all(item.count == 1 for item in found.values())


def test_a_delivered_order_without_delivery_date_and_a_date_on_a_non_delivered_one(detected):
    without_date = of_kind(detected, "delivered_without_date")["orders.orders.delivered_to_customer_at"]
    extra_date = of_kind(detected, "date_with_non_delivered_status")["orders.orders.delivered_to_customer_at"]

    assert without_date.count == 1
    assert without_date.share == pytest.approx(1 / 7)  # 7 delivered orders
    assert extra_date.count == 1


def test_implausible_values(detected):
    assert set(of_kind(detected, "implausible_range")) == {
        "orders.order_items.price",
        "catalog.products.weight_g",
    }


def test_a_category_without_translation(detected):
    found = of_kind(detected, "category_without_translation")

    assert list(found) == ["catalog.product_categories.name_en"]
    assert found["catalog.product_categories.name_en"].count == 1
    assert found["catalog.product_categories.name_en"].share == pytest.approx(1 / 3)


def test_repetitions_that_are_not_defects_are_not_findings(detected):
    assert not any("customer_unique_id" in item.subject for item in detected)


def test_every_finding_has_an_area_and_an_impact(detected):
    assert {item.area for item in detected} <= {"quality", "values", "delivery"}
    assert all(item.impact for item in detected)
    assert all(0.0 <= item.share <= 1.0 for item in detected)


def test_findings_are_ordered_by_area_then_by_count_descending(detected):
    order = {area: position for position, area in enumerate(findings.AREA_ORDER)}
    keys = [(order[item.area], -item.count) for item in detected]

    assert keys == sorted(keys)


# -- actions ---------------------------------------------------------------------------------------------------------


def write(path, text):
    path.write_text(text, encoding="utf-8")
    return path


GENERIC = """
[value_not_in_policies]
target = "corpus"
action = "Decidir si el valor merece documentarse."

["value_not_in_policies:payment_type:not_defined"]
target = "analysis_note"
action = "Es ruido: tres pagos de importe cero."
"""


def test_a_specific_action_wins_over_the_generic_one(tmp_path):
    actions = findings.load_actions(write(tmp_path / "a.toml", GENERIC), require_every_kind=False)

    specific = findings.resolve_action("value_not_in_policies", "payment_type:not_defined", actions)
    generic = findings.resolve_action("value_not_in_policies", "payment_type:other", actions)

    assert specific.target == "analysis_note"
    assert specific.action == "Es ruido: tres pagos de importe cero."
    assert generic.target == "corpus"


def test_a_finding_without_any_action_is_an_error_naming_it(tmp_path):
    actions = findings.load_actions(write(tmp_path / "a.toml", GENERIC), require_every_kind=False)

    with pytest.raises(MissingActionError) as error:
        findings.resolve_action("orphan_reference", "order_items.product_id -> catalog.products", actions)

    assert "orphan_reference" in str(error.value)
    assert "order_items.product_id" in str(error.value)


def test_with_actions_fills_target_and_action(tmp_path, detected):
    actions = findings.load_actions(NOTEBOOK_DATA_DIR / "discrepancy-actions.toml")

    resolved = findings.with_actions(detected, actions)

    assert len(resolved) == len(detected)
    assert all(item.action and item.target in findings.TARGETS for item in resolved)
    assert all(isinstance(item, Finding) for item in resolved)


def test_the_real_actions_file_has_an_entry_for_every_kind():
    actions = findings.load_actions(NOTEBOOK_DATA_DIR / "discrepancy-actions.toml")

    assert set(findings.KINDS) <= set(actions)


def test_a_missing_generic_entry_is_reported_when_every_kind_is_required(tmp_path):
    with pytest.raises(ValueError, match="orphan_reference"):
        findings.load_actions(write(tmp_path / "a.toml", GENERIC))


def test_a_specific_key_with_an_unknown_kind_is_an_error(tmp_path):
    text = '["made_up_kind:x"]\ntarget = "corpus"\naction = "algo"\n'

    with pytest.raises(ValueError, match="made_up_kind"):
        findings.load_actions(write(tmp_path / "a.toml", text), require_every_kind=False)


@pytest.mark.parametrize(
    "body",
    [
        'target = "elsewhere"\naction = "algo"\n',
        'target = "corpus"\naction = ""\n',
        'target = "corpus"\n',
        'action = "algo"\n',
    ],
)
def test_an_invalid_rule_is_rejected(tmp_path, body):
    with pytest.raises(ValueError, match="orphan_reference"):
        findings.load_actions(write(tmp_path / "a.toml", f"[orphan_reference]\n{body}"), require_every_kind=False)


def test_a_missing_actions_file_is_an_error(tmp_path):
    with pytest.raises(ValueError, match="discrepancy-actions"):
        findings.load_actions(tmp_path / "discrepancy-actions.toml")
