# Data Model: Análisis exploratorio de Olist

Esta historia **no crea ni modifica tablas**: lee las ocho de LAB-17 (ver
[`specs/LAB-17-olist-dataset-by-context/data-model.md`](../LAB-17-olist-dataset-by-context/data-model.md)) y produce
estructuras en memoria y un JSON de resumen. Identificadores en inglés (constitución, principio III); textos al usuario en
español.

## Entrada: tablas leídas (solo lectura)

| Esquema | Tabla | Clave natural analizada | Columna sustituta (se excluye de «fila completa») |
|---|---|---|---|
| `orders` | `customers` | `customer_id` | — |
| `orders` | `orders` | `order_id` | — |
| `orders` | `order_items` | `order_id`, `item_number` | — |
| `orders` | `order_payments` | `order_id`, `payment_sequence` | — |
| `orders` | `order_reviews` | `review_id` (no único en el origen) | `id` |
| `catalog` | `product_categories` | `name` | — |
| `catalog` | `products` | `product_id` | — |
| `catalog` | `sellers` | `seller_id` | — |

Conversión al cargar: `numeric` → `float64`; `timestamp` → `datetime64` (la resolución la decide Pandas: con 3.x es `[us]`); `text` → `string`/`object`; `smallint` e
`integer` → enteros de Pandas que admiten nulos (`Int64`).

## Tipos del paquete `olist_eda`

### `AnalysisSettings` (`config.py`)

| Campo | Tipo | Origen / defecto |
|---|---|---|
| `postgres_url` | `str` | `POSTGRES_URL` (acepta `jdbc:postgresql://…`); `postgresql://localhost:5432/shoplab` |
| `postgres_user` | `str` | `POSTGRES_USER`; `shoplab` |
| `postgres_password` | `str` (oculto en `repr`) | `POSTGRES_PASSWORD`; `change-me-local-only` |
| `min_group_orders` | `int` ≥ 1 | `EDA_MIN_GROUP_ORDERS`; `30` |
| `kb_dir` | `Path` | `KB_DIR`; `data/kb` desde la raíz del repositorio |
| `top_n` | `int` | `10` (filas de los rankings de retraso por estado y categoría) |

### `TableSpec` (`schema.py`)

`schema`, `name`, `natural_key: tuple[str, ...]`, `surrogate_columns: tuple[str, ...]`,
`null_rules: dict[str, Callable[[DataFrame], Series[bool]]]` (máscara de nulos **inesperados** por columna; ver D-8).

### `TableProfile` y `ColumnProfile` (`profiling.py`)

| `TableProfile` | Significado |
|---|---|
| `table` | nombre cualificado (`orders.orders`) |
| `rows` | número de filas |
| `key_duplicates` | filas repetidas según la clave natural |
| `row_duplicates` | filas repetidas en la fila completa (sin columnas sustitutas) |
| `columns` | lista de `ColumnProfile` |

| `ColumnProfile` | Significado |
|---|---|
| `name`, `dtype` | nombre y tipo de Pandas |
| `null_count`, `null_pct` | nulos totales y porcentaje de las filas |
| `unexpected_null_count` | nulos que la regla declarada no justifica |
| `min`, `max`, `p01`, `p25`, `p50`, `p75`, `p99` | solo en columnas numéricas y de fecha; `None` en las demás |
| `implausible_count` | valores fuera de lo plausible según la regla de rango (p. ej. `price <= 0`, `weight_g = 0`) |

Reglas de rango declaradas (fuera de lo plausible): `price` ≤ 0, `freight_value` < 0, `weight_g` ≤ 0, dimensiones ≤ 0,
`score` fuera de 1–5 (la base ya lo impide con CHECK; confirmar), fechas posteriores a `2018-12-31` o anteriores a
`2016-01-01` (ventana del dataset).

### `ValueFrequency` (`categorical.py`)

| Campo | Significado |
|---|---|
| `column` | `payment_type` u `order_status` |
| `value` | valor distinto observado |
| `count`, `share` | frecuencia absoluta y relativa |
| `declared_by_policy` | `bool`: aparece en la tabla canónica de su política |
| `mentioned_in` | lista de documentos del corpus que lo citan (puede ser vacía) |
| `covered_by_order_tool` | `bool`, solo para `order_status`: está en `order-tool-states.toml` |

Un objeto aparte, `AbsentValues`, lista por columna los valores **declarados** por las políticas (o por la consulta de
pedidos) que **no existen** en los datos.

### `PolicyReference` (`policies.py`)

`declared_payment_types: set[str]`, `declared_order_states: set[str]` y `mentions: dict[str, tuple[str, ...]]` (token →
documentos que lo citan, restringido a los valores observados). Se construye con `load_policy_reference(kb_dir,
observed_values)`; lanza `PolicyParseError` (documento y sección) si no encuentra una tabla canónica.

### Columnas derivadas de entrega (`delivery.py`)

Sobre `orders.orders` (uno por pedido), todas vectorizadas:

| Columna | Fórmula | Nota |
|---|---|---|
| `actual_days` | `(delivered_to_customer_at − purchased_at)` en días | nulo si no hay entrega |
| `estimated_days` | `(estimated_delivery_at − purchased_at)` en días | |
| `delay_days` | `(delivered_to_customer_at − estimated_delivery_at)` en días | positivo = tarde |
| `is_delayed` | `delay_days > 0` | |
| `delay_bucket` | `a tiempo o antes` (≤ 0), `1 a 7 días tarde` (0 < d ≤ 7), `más de 7 días tarde` (> 7) | `np.select` |
| `exclusion_reason` | `sin_fecha_de_entrega`, `entrega_anterior_a_compra`, `estimada_anterior_a_compra` (en ese orden de prioridad) o nulo | un único motivo por pedido excluido |
| `analyzed` | `exclusion_reason` es nulo | es decir: hay fecha de entrega y `actual_days ≥ 0` y `estimated_days ≥ 0` |
| `customer_state` | de `customers.state` por `customer_id` | UF del cliente |
| `primary_category` | categoría del ítem de mayor precio del pedido (empate: menor `item_number`) | `name_en` o nombre original; «sin categoría» |
| `review_score_mean` | media de `order_reviews.score` por pedido | nulo si no hay reseña |

**Población analizada** = pedidos con `delivered_to_customer_at` no nulo y `coherent`. Los excluidos se clasifican en:
`sin_fecha_de_entrega` (con su estado), `entrega_anterior_a_compra`, `estimada_anterior_a_compra` y
`entregado_sin_fecha` (`status = delivered` y fecha nula).

### `DelaySummary` (`delivery.py`)

Una fila por grupo (estado o categoría): `group`, `orders`, `mean_delay_days`, `delayed_pct`, `mean_review_score`, y la bandera
`below_threshold` (menos de `min_group_orders` pedidos). Los grupos bajo el umbral se muestran aparte y no entran en el
ranking.

### `Finding` y `Action` (`findings.py`)

| `Finding` | Significado |
|---|---|
| `kind` | tipo del hallazgo (catálogo abajo) |
| `subject` | a qué afecta (`orders.approved_at`, `payment_type:not_defined`, `order_items.product_id`…) |
| `area` | `quality`, `values` o `delivery` |
| `count`, `share` | cuántas filas o valores y qué fracción |
| `impact` | frase en español sobre el efecto en el chat o en el análisis |
| `action` | resultado de `resolve_action(kind, subject)` |
| `target` | destino de la acción: `corpus`, `LAB-41`, `loader`, `new_task` u `analysis_note` |

**Catálogo de tipos de hallazgo** (todos generan acción genérica por defecto en `discrepancy-actions.toml`):

| `kind` | Se genera cuando |
|---|---|
| `value_not_in_policies` | un valor de `payment_type` u `order_status` no lo declara ni lo menciona ninguna política |
| `policy_value_not_in_data` | un valor que declara una política no existe en los datos |
| `status_not_in_order_tool` | un estado de los datos no está en `order-tool-states.toml` |
| `tool_state_not_in_data` | un estado de `order-tool-states.toml` no existe en los datos |
| `unexpected_nulls` | una columna tiene nulos que su regla no justifica |
| `duplicate_rows` | hay filas repetidas por fila completa o por una clave que debía ser única |
| `orphan_reference` | un ítem apunta a un producto o vendedor inexistente en `catalog` |
| `incoherent_dates` | entrega, aprobación o estimación anteriores a la compra |
| `delivered_without_date` | `status = delivered` con fecha de entrega nula |
| `date_with_non_delivered_status` | fecha de entrega presente en un pedido que no está `delivered` |
| `implausible_range` | valores fuera de la regla de rango (precio o peso en cero, fechas fuera de ventana) |
| `category_without_translation` | categorías de producto sin `name_en` |

Los hallazgos informativos (p. ej. `customer_unique_id` repetido) **no** son discrepancias: se explican en el perfil pero no
entran en la tabla de acciones.

### `ActionRule` (`discrepancy-actions.toml`)

Ver [contracts/data-files-format.md](contracts/data-files-format.md). Clave `kind:subject` o `kind`; campos `target` y
`action`.

## Salida: resumen JSON

`ml/notebooks/results/olist-eda-summary.json`, formato en [contracts/summary-format.md](contracts/summary-format.md).

## Reglas de validación y errores

| Regla | Dónde | Error |
|---|---|---|
| Base de datos alcanzable, esquemas y ocho tablas presentes, cada una con ≥ 1 fila | `db.preflight` | `PreflightError` (motivo + paso para resolverlo) |
| Una tabla canónica de política existe y tiene al menos un valor | `policies.load_policy_reference` | `PolicyParseError` (documento y sección) |
| Todo hallazgo tiene acción (específica o por tipo) | `findings.resolve_action` | `MissingActionError` (tipo y sujeto) |
| `min_group_orders` ≥ 1 y entero | `AnalysisSettings` | `ValueError` con la variable afectada |
| La conexión es de solo lectura | `db.connect` | cualquier escritura falla en Postgres |

## Relaciones

```text
tablas (DataFrame) ──profiling──> TableProfile ──────────────┐
orders+order_items+products ──integrity──> orphan counts ────┤
payments/orders ──categorical + policies + order-tool-states─┼──> Finding ──resolve_action──> Action
orders+customers+order_items+products+reviews ──delivery─────┤        (discrepancy-actions.toml)
                                                             └──> olist-eda-summary.json
```
