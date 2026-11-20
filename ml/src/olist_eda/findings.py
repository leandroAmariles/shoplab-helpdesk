"""Discrepancies of the analysis as typed findings, each one resolving an action (research D-15).

A finding is the data disagreeing with the corpus, with the order-status tool, or with itself. Its action comes from
discrepancy-actions.toml: a rule for ``kind:subject`` (curated by hand after seeing the real data) or, failing that, a
rule for the ``kind``. Without either the run fails, so no discrepancy can be left without an action (SC-003).
"""

import tomllib
from dataclasses import dataclass, replace
from pathlib import Path

import pandas as pd

from olist_eda import categorical, delivery, integrity, profiling
from olist_eda.categorical import OrderToolStates
from olist_eda.errors import MissingActionError
from olist_eda.policies import PolicyReference

KINDS = (
    "value_not_in_policies",
    "policy_value_not_in_data",
    "status_not_in_order_tool",
    "tool_state_not_in_data",
    "unexpected_nulls",
    "duplicate_rows",
    "orphan_reference",
    "incoherent_dates",
    "delivered_without_date",
    "date_with_non_delivered_status",
    "implausible_range",
    "category_without_translation",
)
AREA_ORDER = ("quality", "values", "delivery")
TARGETS = ("corpus", "LAB-41", "loader", "new_task", "analysis_note")

# Reported by a dedicated detector, so the generic null detector skips them to avoid saying the same thing twice.
COVERED_ELSEWHERE = frozenset({("orders.orders", "delivered_to_customer_at")})
# The source does not guarantee these keys unique (LAB-17): their repetition is measured and explained, not a defect.
KEY_NOT_UNIQUE_AT_SOURCE = frozenset({"orders.order_reviews"})


@dataclass(frozen=True)
class Finding:
    kind: str
    subject: str
    area: str
    count: int
    share: float  # 0-1
    impact: str
    action: str = ""
    target: str = ""


@dataclass(frozen=True)
class ActionRule:
    target: str
    action: str


# -- actions ---------------------------------------------------------------------------------------------------------


def _rule(key: str, body: object) -> ActionRule:
    if not isinstance(body, dict):
        raise ValueError(f"discrepancy-actions.toml: la entrada «{key}» debe ser una tabla con `target` y `action`.")
    target, action = body.get("target"), body.get("action")
    if target not in TARGETS:
        raise ValueError(
            f"discrepancy-actions.toml: «{key}» tiene un `target` inválido ({target!r}); usa uno de {TARGETS}."
        )
    if not isinstance(action, str) or not action.strip():
        raise ValueError(f"discrepancy-actions.toml: «{key}» necesita una `action` no vacía.")
    return ActionRule(target=target, action=action.strip())


def load_actions(path: Path, *, require_every_kind: bool = True) -> dict[str, ActionRule]:
    """Reads the rules keyed by ``kind`` or ``kind:subject``; by default every kind must have its generic rule."""
    if not path.is_file():
        raise ValueError(f"No existe {path.name}: declara la acción de cada tipo de hallazgo.")
    rules: dict[str, ActionRule] = {}
    for key, body in tomllib.loads(path.read_text(encoding="utf-8")).items():
        kind = key.split(":", 1)[0]
        if kind not in KINDS:
            raise ValueError(
                f"discrepancy-actions.toml: «{key}» usa un tipo desconocido ({kind}); tipos válidos: {KINDS}."
            )
        rules[key] = _rule(key, body)
    if require_every_kind:
        missing = [kind for kind in KINDS if kind not in rules]
        if missing:
            raise ValueError(f"discrepancy-actions.toml: falta la acción por tipo de: {', '.join(missing)}.")
    return rules


def resolve_action(kind: str, subject: str, actions: dict[str, ActionRule]) -> ActionRule:
    """The specific rule if there is one, else the rule of the kind; otherwise the finding has no action."""
    rule = actions.get(f"{kind}:{subject}") or actions.get(kind)
    if rule is None:
        raise MissingActionError(
            f"El hallazgo {kind}:{subject} no tiene acción. "
            f"Añade «{kind}» (o «{kind}:{subject}») a discrepancy-actions.toml."
        )
    return rule


def with_actions(found: list[Finding], actions: dict[str, ActionRule]) -> list[Finding]:
    resolved = []
    for finding in found:
        rule = resolve_action(finding.kind, finding.subject, actions)
        resolved.append(replace(finding, action=rule.action, target=rule.target))
    return resolved


# -- detectors -------------------------------------------------------------------------------------------------------


def _share(count: int, total: int) -> float:
    return count / total if total else 0.0


def _value_findings(
    frequencies: dict[str, list[categorical.ValueFrequency]], absent: categorical.AbsentValues
) -> list[Finding]:
    found = []
    for column, items in frequencies.items():
        for item in categorical.uncovered_by_policies(items):
            found.append(
                Finding(
                    "value_not_in_policies", f"{column}:{item.value}", "values", item.count, item.share,
                    f"`{item.value}` aparece en {item.count:,} registros de {column} y ninguna política lo declara ni "
                    "lo cita: el chat no sabría explicarlo.",
                )
            )
    for item in categorical.uncovered_by_tool(frequencies["order_status"]):
        found.append(
            Finding(
                "status_not_in_order_tool", f"order_status:{item.value}", "values", item.count, item.share,
                f"El estado `{item.value}` tiene {item.count:,} pedidos y la consulta de pedidos no lo cubre: el chat "
                "no podría informarlo.",
            )
        )
    declared_absent = [("payment_type", value) for value in absent.payment_type]
    declared_absent += [("order_status", value) for value in absent.order_status_policy]
    for column, value in declared_absent:
        found.append(
            Finding(
                "policy_value_not_in_data", f"{column}:{value}", "values", 0, 0.0,
                f"Una política declara `{value}` como valor de {column}, pero ningún registro lo tiene: el chat podría "
                "prometer algo que los datos no reflejan.",
            )
        )
    for value in absent.order_status_tool:
        found.append(
            Finding(
                "tool_state_not_in_data", f"order_status:{value}", "values", 0, 0.0,
                f"La consulta de pedidos prevé el estado `{value}`, pero ningún pedido lo tiene: solo se puede probar "
                "con datos sintéticos.",
            )
        )
    return found


def _quality_findings(
    tables: dict[str, pd.DataFrame],
    profiles: dict[str, profiling.TableProfile],
    orphans: list[integrity.OrphanCount],
) -> list[Finding]:
    found = []
    for table, profile in profiles.items():
        for column in profile.columns:
            if column.unexpected_null_count and (table, column.name) not in COVERED_ELSEWHERE:
                found.append(
                    Finding(
                        "unexpected_nulls", f"{table}.{column.name}", "quality", column.unexpected_null_count,
                        _share(column.unexpected_null_count, profile.rows),
                        f"{column.unexpected_null_count:,} nulos sin explicación en {table}.{column.name}: lo que "
                        "dependa de esa columna queda incompleto.",
                    )
                )
            if column.implausible_count:
                found.append(
                    Finding(
                        "implausible_range", f"{table}.{column.name}", "quality", column.implausible_count,
                        _share(column.implausible_count, profile.rows),
                        f"{column.implausible_count:,} valores implausibles en {table}.{column.name}: distorsionan "
                        "rangos y promedios.",
                    )
                )
        if profile.row_duplicates:
            found.append(
                Finding(
                    "duplicate_rows", f"{table}:fila_completa", "quality", profile.row_duplicates,
                    _share(profile.row_duplicates, profile.rows),
                    f"{profile.row_duplicates:,} filas repetidas en {table}: se contarían dos veces en los agregados.",
                )
            )
        if profile.key_duplicates and table not in KEY_NOT_UNIQUE_AT_SOURCE:
            found.append(
                Finding(
                    "duplicate_rows", f"{table}:clave_natural", "quality", profile.key_duplicates,
                    _share(profile.key_duplicates, profile.rows),
                    f"{profile.key_duplicates:,} filas repiten la clave natural de {table}, que debería ser única.",
                )
            )
    for orphan in orphans:
        if orphan.orphans:
            found.append(
                Finding(
                    "orphan_reference", orphan.reference, "quality", orphan.orphans,
                    _share(orphan.orphans, orphan.checked),
                    f"{orphan.orphans:,} filas apuntan a un id inexistente: una consulta cruzada daría resultados "
                    "incompletos y ninguna clave foránea lo impide.",
                )
            )
    categories = tables["catalog.product_categories"]
    untranslated = int(categories["name_en"].isna().sum())
    if untranslated:
        found.append(
            Finding(
                "category_without_translation", "catalog.product_categories.name_en", "quality", untranslated,
                _share(untranslated, len(categories)),
                f"{untranslated:,} categorías no tienen traducción: el chat las mostraría con su nombre original.",
            )
        )
    return found


def _delivery_findings(tables: dict[str, pd.DataFrame], exclusions: delivery.ExclusionCounts) -> list[Finding]:
    orders = tables["orders.orders"]
    total = exclusions.total_orders
    delivered_orders = int((orders["status"] == "delivered").sum())
    approved_before = int((orders["approved_at"] < orders["purchased_at"]).sum())
    found = []
    dates = (
        ("entrega_anterior_a_compra", exclusions.delivered_before_purchase, "tienen la entrega anterior a la compra"),
        (
            "estimada_anterior_a_compra",
            exclusions.estimated_before_purchase,
            "tienen la fecha estimada anterior a la compra",
        ),
        ("aprobacion_anterior_a_compra", approved_before, "tienen la aprobación anterior a la compra"),
    )
    for suffix, count, text in dates:
        if count:
            found.append(
                Finding(
                    "incoherent_dates", f"orders.orders:{suffix}", "delivery", count, _share(count, total),
                    f"{count:,} pedidos {text}: se excluyen de los tiempos de entrega.",
                )
            )
    if exclusions.delivered_without_date:
        count = exclusions.delivered_without_date
        found.append(
            Finding(
                "delivered_without_date", "orders.orders.delivered_to_customer_at", "delivery", count,
                _share(count, delivered_orders),
                f"{count:,} pedidos `delivered` no tienen fecha de entrega: el chat diría «entregado» sin poder dar la "
                "fecha, y se excluyen de los tiempos.",
            )
        )
    if exclusions.date_with_non_delivered_status:
        count = exclusions.date_with_non_delivered_status
        found.append(
            Finding(
                "date_with_non_delivered_status", "orders.orders.delivered_to_customer_at", "delivery", count,
                _share(count, total),
                f"{count:,} pedidos tienen fecha de entrega y un estado distinto de `delivered`: el estado y la fecha "
                "se contradicen.",
            )
        )
    return found


def detect_findings(
    tables: dict[str, pd.DataFrame], reference: PolicyReference, tool: OrderToolStates
) -> list[Finding]:
    """Every discrepancy of the data, without actions yet; ordered by area and then by size."""
    deliveries = delivery.build_delivery_frame(tables)
    found = [
        *_value_findings(
            categorical.value_frequencies(tables, reference, tool), categorical.absent_values(tables, reference, tool)
        ),
        *_quality_findings(tables, profiling.profile_tables(tables), integrity.orphan_references(tables)),
        *_delivery_findings(tables, delivery.exclusion_counts(deliveries)),
    ]
    position = {area: index for index, area in enumerate(AREA_ORDER)}
    return sorted(found, key=lambda item: (position[item.area], -item.count, item.kind, item.subject))
