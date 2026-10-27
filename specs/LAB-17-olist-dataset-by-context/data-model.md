# Data Model: Carga del dataset Olist en esquemas por contexto

Nombres en inglés (constitución, principio III). Los tipos son de PostgreSQL 16. Las FKs listadas son **solo internas a
cada esquema**; entre esquemas no hay ninguna (S1/S5 de LAB-105). Los scripts no califican nombres con el esquema
(Flyway fija `defaultSchema`), de modo que el lint S2 no encuentra referencias al otro esquema.

## Esquema `orders` — `db/migration/orders/V2__olist_orders_tables.sql`

### `customers`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `customer_id` | text | PK | `customer_id` |
| `customer_unique_id` | text | NOT NULL, índice | `customer_unique_id` |
| `zip_code_prefix` | text | | `customer_zip_code_prefix` (texto: conserva ceros iniciales) |
| `city` | text | | `customer_city` |
| `state` | text | | `customer_state` |

### `orders`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `order_id` | text | PK | `order_id` |
| `customer_id` | text | NOT NULL, FK → `customers` | `customer_id` |
| `status` | text | NOT NULL, índice | `order_status` |
| `purchased_at` | timestamp | | `order_purchase_timestamp` |
| `approved_at` | timestamp | NULL permitido | `order_approved_at` |
| `delivered_to_carrier_at` | timestamp | NULL permitido | `order_delivered_carrier_date` |
| `delivered_to_customer_at` | timestamp | NULL permitido | `order_delivered_customer_date` |
| `estimated_delivery_at` | timestamp | | `order_estimated_delivery_date` |

Estados observados en el origen: `delivered`, `shipped`, `canceled`, `unavailable`, `invoiced`, `processing`,
`created`, `approved`. Se guardan como texto sin enumerado (el cargador no los valida; YAGNI).

### `order_items`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `order_id` | text | PK (parte 1), FK → `orders` | `order_id` |
| `item_number` | integer | PK (parte 2) | `order_item_id` |
| `product_id` | text | NOT NULL, índice, **sin FK** | `product_id` |
| `seller_id` | text | NOT NULL, índice, **sin FK** | `seller_id` |
| `shipping_limit_at` | timestamp | | `shipping_limit_date` |
| `price` | numeric(10,2) | NOT NULL, CHECK ≥ 0 | `price` |
| `freight_value` | numeric(10,2) | NOT NULL, CHECK ≥ 0 | `freight_value` |

### `order_payments`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `order_id` | text | PK (parte 1), FK → `orders` | `order_id` |
| `payment_sequence` | integer | PK (parte 2) | `payment_sequential` |
| `payment_type` | text | NOT NULL | `payment_type` |
| `installments` | integer | NOT NULL, CHECK ≥ 0 | `payment_installments` |
| `payment_value` | numeric(10,2) | NOT NULL, CHECK ≥ 0 | `payment_value` |

### `order_reviews`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `id` | bigint | PK, `generated always as identity` | (sintética) |
| `review_id` | text | NOT NULL, índice | `review_id` (no único en el origen) |
| `order_id` | text | NOT NULL, índice, FK → `orders` | `order_id` |
| `score` | smallint | NOT NULL, CHECK entre 1 y 5 | `review_score` |
| `comment_title` | text | NULL permitido | `review_comment_title` |
| `comment_message` | text | NULL permitido | `review_comment_message` |
| `created_at` | timestamp | | `review_creation_date` |
| `answered_at` | timestamp | NULL permitido | `review_answer_timestamp` |

## Esquema `catalog` — `db/migration/catalog/V2__olist_catalog_tables.sql`

### `product_categories`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `name` | text | PK | `product_category_name` (portugués, nombre original) |
| `name_en` | text | NULL permitido | `product_category_name_english` (nulo en categorías sin traducción) |

### `products`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `product_id` | text | PK | `product_id` |
| `category_name` | text | NULL permitido, FK → `product_categories` | `product_category_name` |
| `name_length` | integer | NULL permitido | `product_name_lenght` |
| `description_length` | integer | NULL permitido | `product_description_lenght` |
| `photos_count` | integer | NULL permitido | `product_photos_qty` |
| `weight_g` | integer | NULL permitido, CHECK ≥ 0 | `product_weight_g` |
| `length_cm` / `height_cm` / `width_cm` | integer | NULL permitido, CHECK ≥ 0 | `product_length_cm`, `product_height_cm`, `product_width_cm` |
| `reference_price` | numeric(10,2) | NULL permitido, CHECK ≥ 0 | **derivado**: mediana de `order_items.price` del producto |
| `stock` | integer | NOT NULL, CHECK ≥ 0 | **sintético** determinista (research D-6) |

### `sellers`

| Columna | Tipo | Restricciones | Origen |
|---|---|---|---|
| `seller_id` | text | PK | `seller_id` |
| `zip_code_prefix` | text | | `seller_zip_code_prefix` |
| `city` | text | | `seller_city` |
| `state` | text | | `seller_state` |

## Relaciones

```text
orders:   customers 1──N orders 1──N order_items
                              1──N order_payments
                              1──N order_reviews
catalog:  product_categories 1──N products          sellers (independiente)

entre esquemas (solo por id, SIN clave foránea):
  orders.order_items.product_id ┄┄> catalog.products.product_id
  orders.order_items.seller_id  ┄┄> catalog.sellers.seller_id
```

## Reglas de validación y transformación (cargador)

- Cadena vacía → `NULL` en columnas que lo permiten; en columnas `NOT NULL` la fila se descarta y se cuenta.
- Códigos postales como texto (no se pierden ceros iniciales).
- `order_items`, `order_payments` y `order_reviews` con `order_id` inexistente en `orders` se descartan (integridad
  interna) y se cuentan por motivo.
- `order_items` con `product_id` o `seller_id` ausentes en `catalog`: se **cargan** y se cuentan como huérfanos.
- Categorías de productos ausentes en la tabla de traducción: se insertan en `product_categories` con `name_en` nulo.
- `reference_price` = mediana de `price` por producto; sin ventas → `NULL`.
- `stock` = función determinista del `product_id` (research D-6).

## Estado

Los datos son de solo carga; no hay transiciones de estado en esta historia. Cada ejecución reemplaza por completo el
contenido de las tablas de ambos esquemas (research D-2).
