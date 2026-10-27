"""The summary printed at the end of a run: counts per schema and table, orphans and elapsed time.

Counts and reasons only; never a value from a row.
"""

from olist_loader.transform import TransformedDataset


def _clock(seconds: float) -> str:
    total = int(seconds)
    return f"{total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}"


def render(dataset: TransformedDataset, elapsed_seconds: float, dry_run: bool = False) -> str:
    lines: list[str] = []
    if dry_run:
        lines.append("Simulación: no se escribió en la base de datos.")
    for schema, tables in dataset.schemas.items():
        lines.append(f"Esquema {schema}")
        for table in tables:
            discarded = sum(table.discarded.values())
            line = (
                f"  {table.name:<20} leídas {table.read:<7} cargadas {len(table.rows):<7} descartadas {discarded}"
            )
            if table.derived:
                line += f"  ({table.derived} derivadas)"
            lines.append(line)
            for reason, count in table.discarded.most_common():
                lines.append(f"    - {reason}: {count}")
    orphans = dataset.orphans
    lines.append(
        f"Referencias huérfanas entre esquemas: {orphans.total} "
        f"(order_items.product_id: {orphans.product}, order_items.seller_id: {orphans.seller})"
    )
    lines.append(f"Tiempo total: {_clock(elapsed_seconds)}")
    return "\n".join(lines)
