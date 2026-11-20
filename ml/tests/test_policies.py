from pathlib import Path

import pytest

from olist_eda.config import REPO_ROOT
from olist_eda.errors import PolicyParseError
from olist_eda.policies import load_policy_reference

REAL_KB = REPO_ROOT / "data" / "kb"
PAYMENTS = "payments-01-payment-methods"
TRACKING = "shipping-03-order-tracking"

PAYMENTS_DOC = """# Métodos de pago

## Cómo funciona
| Método | Nombre en el pedido | Cómo se paga |
|---|---|---|
| Tarjeta | `credit_card` | en cuotas |
| Boleto | `boleto` | en un banco |

## Plazos y costos
| Concepto | Valor |
|---|---|
| Aprobación | `ignored_token` |
"""

TRACKING_DOC = """# Seguimiento

## Estados del pedido
| Estado | Significado |
|---|---|
| `created` | pedido creado |
| `delivered` | entregado |

## Casos especiales
- Si está en `shipped` no hay código.
"""


def write_corpus(directory: Path, payments: str = PAYMENTS_DOC, tracking: str = TRACKING_DOC) -> Path:
    (directory / f"{PAYMENTS}.md").write_text(payments, encoding="utf-8")
    (directory / f"{TRACKING}.md").write_text(tracking, encoding="utf-8")
    return directory


def test_the_real_corpus_declares_four_payment_types_and_eight_order_states():
    reference = load_policy_reference(REAL_KB, [])

    assert reference.declared_payment_types == {"credit_card", "debit_card", "boleto", "voucher"}
    assert reference.declared_order_states == {
        "created", "approved", "invoiced", "processing", "shipped", "delivered", "unavailable", "canceled",
    }


def test_the_real_corpus_mentions_a_status_in_more_than_one_document():
    reference = load_policy_reference(REAL_KB, ["canceled", "voucher", "not_defined"])

    assert TRACKING in reference.mentions["canceled"]
    assert "returns-04-exchanges-and-cancellations" in reference.mentions["canceled"]
    assert PAYMENTS in reference.mentions["voucher"]
    assert "returns-03-refunds" in reference.mentions["voucher"]
    assert "not_defined" not in reference.mentions  # no policy cites it


def test_only_the_declared_and_observed_values_are_tracked_as_mentions(tmp_path):
    corpus = write_corpus(tmp_path)

    reference = load_policy_reference(corpus, ["shipped"])

    assert set(reference.mentions) == {"credit_card", "boleto", "created", "delivered", "shipped"}
    assert "ignored_token" not in reference.mentions
    assert reference.mentions["shipped"] == (TRACKING,)


def test_index_and_review_files_are_not_part_of_the_corpus(tmp_path):
    corpus = write_corpus(tmp_path)
    (corpus / "INDEX.md").write_text("`ghost_value` listed in the index", encoding="utf-8")
    (corpus / "REVIEW.md").write_text("`ghost_value` reviewed", encoding="utf-8")

    reference = load_policy_reference(corpus, ["ghost_value"])

    assert "ghost_value" not in reference.mentions


def test_values_of_the_canonical_tables_come_only_from_their_column(tmp_path):
    reference = load_policy_reference(write_corpus(tmp_path), [])

    assert reference.declared_payment_types == {"credit_card", "boleto"}
    assert reference.declared_order_states == {"created", "delivered"}  # `shipped` is only mentioned in prose


def test_a_missing_document_names_it(tmp_path):
    (tmp_path / f"{TRACKING}.md").write_text(TRACKING_DOC, encoding="utf-8")

    with pytest.raises(PolicyParseError, match=PAYMENTS):
        load_policy_reference(tmp_path, [])


def test_a_missing_section_names_the_document_and_the_section(tmp_path):
    corpus = write_corpus(tmp_path, tracking="# Seguimiento\n\n## Otra cosa\ntexto\n")

    with pytest.raises(PolicyParseError) as error:
        load_policy_reference(corpus, [])

    assert TRACKING in str(error.value)
    assert "Estados del pedido" in str(error.value)


def test_a_section_without_a_table_is_an_error(tmp_path):
    corpus = write_corpus(tmp_path, tracking="# Seguimiento\n\n## Estados del pedido\nsolo texto\n")

    with pytest.raises(PolicyParseError, match="tabla"):
        load_policy_reference(corpus, [])


def test_a_table_without_the_expected_column_is_an_error(tmp_path):
    payments = PAYMENTS_DOC.replace("Nombre en el pedido", "Otra columna")
    corpus = write_corpus(tmp_path, payments=payments)

    with pytest.raises(PolicyParseError, match="Nombre en el pedido"):
        load_policy_reference(corpus, [])


def test_a_table_without_values_in_backticks_is_an_error(tmp_path):
    tracking = TRACKING_DOC.replace("`created`", "created").replace("`delivered`", "delivered")
    corpus = write_corpus(tmp_path, tracking=tracking)

    with pytest.raises(PolicyParseError, match="sin valores"):
        load_policy_reference(corpus, [])
