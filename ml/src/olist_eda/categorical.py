"""Frequencies of payment_type and order_status, contrasted with the policies and with the order-status tool."""

import re
import tomllib
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

from olist_eda.policies import PolicyReference

_STATE_FORM = re.compile(r"^[a-z_]+$")


@dataclass(frozen=True)
class OrderToolStates:
    """The states the order-status tool (LAB-41) will show, as declared in order-tool-states.toml."""

    source: str
    states: tuple[str, ...]


@dataclass(frozen=True)
class ValueFrequency:
    column: str  # payment_type or order_status
    value: str
    count: int
    share: float  # 0-1
    declared_by_policy: bool
    mentioned_in: tuple[str, ...]
    covered_by_order_tool: bool | None  # only meaningful for order_status


@dataclass(frozen=True)
class AbsentValues:
    """Values a source declares that never occur in the data."""

    payment_type: tuple[str, ...]
    order_status_policy: tuple[str, ...]
    order_status_tool: tuple[str, ...]


def load_order_tool_states(path: Path) -> OrderToolStates:
    if not path.is_file():
        raise ValueError(f"No existe {path.name}: declara los estados de la consulta de pedidos (campo `states`).")
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    source = data.get("source")
    if not isinstance(source, str) or not source.strip():
        raise ValueError(f"{path.name}: el campo `source` es obligatorio y debe explicar de dónde sale la lista.")
    states = data.get("states")
    if not isinstance(states, list) or not states or not all(isinstance(state, str) for state in states):
        raise ValueError(f"{path.name}: el campo `states` debe ser una lista no vacía de cadenas.")
    if len(set(states)) != len(states):
        raise ValueError(f"{path.name}: el campo `states` tiene valores duplicados.")
    invalid = [state for state in states if not _STATE_FORM.match(state)]
    if invalid:
        raise ValueError(f"{path.name}: el campo `states` solo admite la forma [a-z_]+; no cumplen: {invalid}.")
    return OrderToolStates(source=source, states=tuple(states))


def _frequencies(
    column: str,
    series: pd.Series,
    declared: frozenset[str],
    reference: PolicyReference,
    tool: OrderToolStates | None,
) -> list[ValueFrequency]:
    counts = series.value_counts()
    ordered = pd.DataFrame({"value": counts.index.astype(str), "count": counts.to_numpy()})
    ordered = ordered.sort_values(["count", "value"], ascending=[False, True])
    total = int(ordered["count"].sum())
    return [
        ValueFrequency(
            column=column,
            value=value,
            count=int(count),
            share=int(count) / total,
            declared_by_policy=value in declared,
            mentioned_in=reference.mentions.get(value, ()),
            covered_by_order_tool=None if tool is None else value in tool.states,
        )
        for value, count in zip(ordered["value"], ordered["count"], strict=True)
    ]


def value_frequencies(
    tables: dict[str, pd.DataFrame], reference: PolicyReference, tool: OrderToolStates
) -> dict[str, list[ValueFrequency]]:
    """Every distinct value with its frequency and coverage, most frequent first (ties by name)."""
    return {
        "payment_type": _frequencies(
            "payment_type",
            tables["orders.order_payments"]["payment_type"],
            reference.declared_payment_types,
            reference,
            None,
        ),
        "order_status": _frequencies(
            "order_status", tables["orders.orders"]["status"], reference.declared_order_states, reference, tool
        ),
    }


def absent_values(
    tables: dict[str, pd.DataFrame], reference: PolicyReference, tool: OrderToolStates
) -> AbsentValues:
    """The values the policies or the tool declare that no row of the data has."""
    payment_types = set(tables["orders.order_payments"]["payment_type"])
    statuses = set(tables["orders.orders"]["status"])
    return AbsentValues(
        payment_type=tuple(sorted(reference.declared_payment_types - payment_types)),
        order_status_policy=tuple(sorted(reference.declared_order_states - statuses)),
        order_status_tool=tuple(sorted(set(tool.states) - statuses)),
    )


def _yes_no(flag: bool) -> str:
    return "sí" if flag else "no"


def frequencies_frame(items: list[ValueFrequency]) -> pd.DataFrame:
    """The frequency table for reading; the order-tool column only appears for order_status."""
    frame = pd.DataFrame(
        {
            "valor": [item.value for item in items],
            "cantidad": [item.count for item in items],
            "%": [round(item.share * 100, 2) for item in items],
            "declarado por la política": [_yes_no(item.declared_by_policy) for item in items],
            "citado en": [", ".join(item.mentioned_in) or "—" for item in items],
        }
    )
    if any(item.covered_by_order_tool is not None for item in items):
        frame["cubierto por la consulta de pedidos"] = [_yes_no(bool(item.covered_by_order_tool)) for item in items]
    return frame


def uncovered_by_policies(items: list[ValueFrequency]) -> list[ValueFrequency]:
    """Values of the data that no policy declares in its canonical table nor mentions anywhere."""
    return [item for item in items if not item.declared_by_policy and not item.mentioned_in]


def uncovered_by_tool(items: list[ValueFrequency]) -> list[ValueFrequency]:
    """Order states of the data that the order-status tool does not cover."""
    return [item for item in items if item.covered_by_order_tool is False]
