import pytest

from olist_eda import categorical
from olist_eda.categorical import OrderToolStates
from olist_eda.policies import PolicyReference

ALL_STATES = frozenset(
    {"created", "approved", "invoiced", "processing", "shipped", "delivered", "unavailable", "canceled"}
)
PAYMENTS = "payments-01-payment-methods"
TRACKING = "shipping-03-order-tracking"


@pytest.fixture
def reference():
    return PolicyReference(
        declared_payment_types=frozenset({"credit_card", "debit_card", "boleto", "voucher"}),
        declared_order_states=ALL_STATES,
        mentions={
            "credit_card": (PAYMENTS,),
            "delivered": (TRACKING, "returns-01-return-policy"),
            "unavailable": (TRACKING,),
        },
    )


@pytest.fixture
def tool():
    # the order tool does not cover `unavailable` and promises a `lost` state no order has
    return OrderToolStates(
        source="test",
        states=("created", "approved", "invoiced", "processing", "shipped", "delivered", "canceled", "lost"),
    )


def by_value(items):
    return {item.value: item for item in items}


def test_every_distinct_value_is_listed_ordered_by_frequency_then_name(tables, reference, tool):
    payments = categorical.value_frequencies(tables, reference, tool)["payment_type"]

    assert [(item.value, item.count) for item in payments] == [
        ("credit_card", 6), ("boleto", 3), ("voucher", 2), ("debit_card", 1), ("not_defined", 1),
    ]
    assert sum(item.share for item in payments) == pytest.approx(1.0)
    assert payments[0].share == pytest.approx(6 / 13)


def test_order_status_frequencies(tables, reference, tool):
    statuses = categorical.value_frequencies(tables, reference, tool)["order_status"]

    assert [(item.value, item.count) for item in statuses] == [
        ("delivered", 7), ("shipped", 2), ("canceled", 1), ("created", 1), ("unavailable", 1),
    ]
    assert sum(item.share for item in statuses) == pytest.approx(1.0)


def test_each_value_says_whether_the_policies_declare_and_mention_it(tables, reference, tool):
    payments = by_value(categorical.value_frequencies(tables, reference, tool)["payment_type"])

    assert payments["credit_card"].declared_by_policy is True
    assert payments["credit_card"].mentioned_in == (PAYMENTS,)
    assert payments["not_defined"].declared_by_policy is False
    assert payments["not_defined"].mentioned_in == ()
    assert payments["debit_card"].declared_by_policy is True
    assert payments["debit_card"].mentioned_in == ()  # declared but cited nowhere else


def test_each_status_says_whether_the_order_tool_covers_it(tables, reference, tool):
    statuses = by_value(categorical.value_frequencies(tables, reference, tool)["order_status"])

    assert statuses["delivered"].covered_by_order_tool is True
    assert statuses["unavailable"].covered_by_order_tool is False
    assert statuses["delivered"].mentioned_in == (TRACKING, "returns-01-return-policy")


def test_payment_types_have_no_order_tool_coverage(tables, reference, tool):
    payments = categorical.value_frequencies(tables, reference, tool)["payment_type"]

    assert all(item.covered_by_order_tool is None for item in payments)


def test_values_declared_but_absent_from_the_data_are_listed_apart(tables, reference, tool):
    absent = categorical.absent_values(tables, reference, tool)

    assert absent.payment_type == ()
    assert absent.order_status_policy == ("approved", "invoiced", "processing")
    assert absent.order_status_tool == ("approved", "invoiced", "lost", "processing")


def test_a_declared_payment_type_that_never_occurs_is_absent(tables, tool):
    reference = PolicyReference(frozenset({"credit_card", "pix"}), ALL_STATES, {})

    assert categorical.absent_values(tables, reference, tool).payment_type == ("pix",)


def test_the_frequency_table_is_readable(tables, reference, tool):
    frequencies = categorical.value_frequencies(tables, reference, tool)

    payments = categorical.frequencies_frame(frequencies["payment_type"])
    statuses = categorical.frequencies_frame(frequencies["order_status"])

    assert list(payments.columns) == ["valor", "cantidad", "%", "declarado por la política", "citado en"]
    assert list(statuses.columns) == [
        "valor", "cantidad", "%", "declarado por la política", "citado en", "cubierto por la consulta de pedidos",
    ]
    assert payments.iloc[0]["valor"] == "credit_card"
    assert payments.iloc[0]["%"] == 46.15
    assert payments[payments["valor"] == "not_defined"].iloc[0]["citado en"] == "—"
    assert statuses[statuses["valor"] == "unavailable"].iloc[0]["cubierto por la consulta de pedidos"] == "no"


def test_an_empty_table_has_no_values(tables, reference, tool):
    tables["orders.order_payments"] = tables["orders.order_payments"].iloc[0:0]

    assert categorical.value_frequencies(tables, reference, tool)["payment_type"] == []


def test_values_that_no_policy_declares_or_mentions_are_the_gaps(tables, reference, tool):
    frequencies = categorical.value_frequencies(tables, reference, tool)

    assert [item.value for item in categorical.uncovered_by_policies(frequencies["payment_type"])] == ["not_defined"]
    assert categorical.uncovered_by_policies(frequencies["order_status"]) == []


def test_a_declared_value_is_not_a_gap_even_if_no_other_document_cites_it(tables, reference, tool):
    payments = categorical.value_frequencies(tables, reference, tool)["payment_type"]

    assert "debit_card" not in [item.value for item in categorical.uncovered_by_policies(payments)]


def test_a_mentioned_but_undeclared_value_is_not_a_gap(tables, tool):
    reference = PolicyReference(frozenset({"credit_card"}), ALL_STATES, {"not_defined": ("some-doc",)})
    payments = categorical.value_frequencies(tables, reference, tool)["payment_type"]

    assert categorical.uncovered_by_policies(payments) == [
        item for item in payments if item.value in {"boleto", "voucher", "debit_card"}
    ]


def test_statuses_the_order_tool_does_not_cover(tables, reference, tool):
    statuses = categorical.value_frequencies(tables, reference, tool)["order_status"]

    assert [item.value for item in categorical.uncovered_by_tool(statuses)] == ["unavailable"]


# -- order-tool-states.toml ---------------------------------------------------------------------------------------


def write(path, text):
    path.write_text(text, encoding="utf-8")
    return path


def test_the_states_file_is_loaded(tmp_path):
    path = write(tmp_path / "states.toml", 'source = "tabla del corpus"\nstates = ["created", "shipped"]\n')

    loaded = categorical.load_order_tool_states(path)

    assert loaded == OrderToolStates(source="tabla del corpus", states=("created", "shipped"))


def test_the_real_states_file_matches_the_tracking_policy():
    from olist_eda.config import REPO_ROOT
    from olist_eda.policies import load_policy_reference

    loaded = categorical.load_order_tool_states(REPO_ROOT / "ml" / "notebooks" / "data" / "order-tool-states.toml")
    declared = load_policy_reference(REPO_ROOT / "data" / "kb", []).declared_order_states

    assert set(loaded.states) == declared
    assert loaded.source


@pytest.mark.parametrize(
    ("text", "field"),
    [
        ('states = ["created"]\n', "source"),
        ('source = ""\nstates = ["created"]\n', "source"),
        ('source = "x"\n', "states"),
        ('source = "x"\nstates = []\n', "states"),
        ('source = "x"\nstates = ["created", "created"]\n', "states"),
        ('source = "x"\nstates = ["Created"]\n', "states"),
        ('source = "x"\nstates = ["in-transit"]\n', "states"),
        ('source = "x"\nstates = "created"\n', "states"),
    ],
)
def test_an_invalid_states_file_names_the_field(tmp_path, text, field):
    with pytest.raises(ValueError, match=field):
        categorical.load_order_tool_states(write(tmp_path / "states.toml", text))


def test_a_missing_states_file_is_an_error(tmp_path):
    with pytest.raises(ValueError, match="order-tool-states"):
        categorical.load_order_tool_states(tmp_path / "order-tool-states.toml")
