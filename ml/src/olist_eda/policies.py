"""What the policy corpus (data/kb, LAB-18) says about payment types and order states.

Two different things are extracted (research D-6): the values a policy **declares** in its canonical table, and the
values that any document merely **mentions** between backticks. A value declared but absent from the data, or present in
the data but cited nowhere, is a discrepancy.
"""

import re
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from olist_eda.errors import PolicyParseError

EXCLUDED_DOCUMENTS = frozenset({"INDEX", "REVIEW"})
_BACKTICKED = re.compile(r"`([a-z_]+)`")
_SEPARATOR_CELL = re.compile(r"^:?-{3,}:?$")


@dataclass(frozen=True)
class _CanonicalTable:
    document: str
    section: str
    column: str


PAYMENT_TYPES_TABLE = _CanonicalTable("payments-01-payment-methods", "Cómo funciona", "Nombre en el pedido")
ORDER_STATES_TABLE = _CanonicalTable("shipping-03-order-tracking", "Estados del pedido", "Estado")


@dataclass(frozen=True)
class PolicyReference:
    declared_payment_types: frozenset[str]
    declared_order_states: frozenset[str]
    mentions: dict[str, tuple[str, ...]]  # tracked value -> documents that cite it between backticks


def _cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def _section_lines(text: str, section: str) -> list[str] | None:
    lines = text.splitlines()
    try:
        start = next(index for index, line in enumerate(lines) if line.strip() == f"## {section}")
    except StopIteration:
        return None
    body = []
    for line in lines[start + 1 :]:
        if line.startswith("## "):
            break
        body.append(line)
    return body


def _first_table(lines: list[str]) -> list[str]:
    table: list[str] = []
    for line in lines:
        if line.lstrip().startswith("|"):
            table.append(line)
        elif table:
            break
    return table


def _declared_values(kb_dir: Path, canonical: _CanonicalTable) -> frozenset[str]:
    path = kb_dir / f"{canonical.document}.md"
    if not path.is_file():
        raise PolicyParseError(f"No existe el documento {canonical.document}.md en {kb_dir.name}.")
    where = f"{canonical.document}.md, sección «{canonical.section}»"
    body = _section_lines(path.read_text(encoding="utf-8"), canonical.section)
    if body is None:
        raise PolicyParseError(f"{where}: no se encontró la sección.")
    table = _first_table(body)
    if not table:
        raise PolicyParseError(f"{where}: la sección no tiene ninguna tabla.")
    header = [cell.lower() for cell in _cells(table[0])]
    if canonical.column.lower() not in header:
        raise PolicyParseError(f"{where}: la tabla no tiene la columna «{canonical.column}».")
    index = header.index(canonical.column.lower())
    values: set[str] = set()
    for line in table[1:]:
        cells = _cells(line)
        if all(_SEPARATOR_CELL.match(cell) for cell in cells) or index >= len(cells):
            continue
        values.update(_BACKTICKED.findall(cells[index]))
    if not values:
        raise PolicyParseError(f"{where}: la columna «{canonical.column}» queda sin valores entre comillas invertidas.")
    return frozenset(values)


def _mentions(kb_dir: Path, tracked: frozenset[str]) -> dict[str, tuple[str, ...]]:
    found: dict[str, list[str]] = {}
    for path in sorted(kb_dir.glob("*.md")):
        if path.stem in EXCLUDED_DOCUMENTS:
            continue
        for token in set(_BACKTICKED.findall(path.read_text(encoding="utf-8"))) & tracked:
            found.setdefault(token, []).append(path.stem)
    return {token: tuple(documents) for token, documents in found.items()}


def load_policy_reference(kb_dir: Path, observed_values: Iterable[str]) -> PolicyReference:
    """Declared values come from the two canonical tables; mentions are tracked for declared and observed values."""
    payment_types = _declared_values(kb_dir, PAYMENT_TYPES_TABLE)
    order_states = _declared_values(kb_dir, ORDER_STATES_TABLE)
    tracked = payment_types | order_states | frozenset(observed_values)
    return PolicyReference(payment_types, order_states, _mentions(kb_dir, tracked))
