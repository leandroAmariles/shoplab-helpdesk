# Contrato: `ml/notebooks/results/olist-eda-summary.json`

Resumen máquina-legible de la última ejecución guardada. Lo escribe el notebook (sección 12) y lo lee `test_notebook.py` para
comprobar que las conclusiones en prosa siguen alineadas con las cifras. **No contiene identificadores ni filas de datos**,
solo agregados.

## Forma

```json
{
  "schema_version": 1,
  "generated_at": "2026-11-20T21:30:00Z",
  "data_scale": "full",
  "tables": [
    { "table": "orders.orders", "rows": 0, "key_duplicates": 0, "row_duplicates": 0,
      "columns_with_unexpected_nulls": ["delivered_to_customer_at"] }
  ],
  "values": {
    "payment_type": [
      { "value": "credit_card", "count": 0, "share": 0.0, "declared_by_policy": true, "mentioned_in": ["payments-01-payment-methods"] }
    ],
    "order_status": [
      { "value": "delivered", "count": 0, "share": 0.0, "declared_by_policy": true, "covered_by_order_tool": true, "mentioned_in": ["shipping-03-order-tracking"] }
    ],
    "absent_from_data": { "payment_type": [], "order_status": [] }
  },
  "delivery": {
    "analyzed_orders": 0,
    "excluded": { "no_delivery_date": 0, "delivered_before_purchase": 0, "estimated_before_purchase": 0, "delivered_without_date": 0 },
    "actual_days": { "mean": 0.0, "median": 0.0, "p95": 0.0 },
    "estimated_days": { "mean": 0.0, "median": 0.0, "p95": 0.0 },
    "early_pct": 0.0, "same_day_pct": 0.0, "late_pct": 0.0
  },
  "reviews": { "count": 0, "score_distribution": {"1": 0, "2": 0, "3": 0, "4": 0, "5": 0},
               "mean_score_by_delay_bucket": {} },
  "findings": [
    { "kind": "value_not_in_policies", "subject": "payment_type:not_defined", "area": "values",
      "count": 0, "share": 0.0, "target": "corpus", "action": "…" }
  ]
}
```

## Reglas

| Campo | Regla |
|---|---|
| `schema_version` | entero; sube si cambia la forma |
| `data_scale` | `full` si algún conteo de `orders.orders` ≥ 1 000, `sample` en caso contrario; las salidas guardadas en el repo deben ser `full` |
| `tables` | exactamente 8 entradas, una por tabla analizada (SC-001) |
| `values.*` | incluye el 100 % de los valores distintos presentes (SC-002); `absent_from_data` lista los declarados que no existen |
| `findings` | cada uno con `action` no vacía (SC-003); ordenados por `area`, luego `count` descendente |
| `*_pct`, `share` | fracciones o porcentajes con 4 decimales como máximo; las fracciones de retraso suman 100 ± 0,01 |
| Datos personales | prohibidos: ningún identificador, ni cadenas de 32 caracteres hexadecimales |

## Consumo

- `test_notebook.py` lee este JSON y comprueba que `tables` coincide con las filas que muestra el notebook guardado, que
  `findings` coincide con las filas de la tabla de la sección 12 y que `data_scale` es `full`.
- Las historias de F8 que necesiten estas cifras (p. ej. la variable objetivo de retraso) las leen de aquí en lugar de
  recalcularlas a mano, pero **no** deben depender de que exista: solo es un artefacto de documentación.
