# Contrato: migraciones de `orders` y `catalog`

Reglas que cumplen los dos scripts nuevos. Se apoyan en el contrato de aislamiento de LAB-105
(`specs/LAB-105-business-modules-data-ownership/contracts/schema-isolation.md`, reglas S1–S5).

## Ubicación y nombres

| Módulo | Script | Contenido |
|---|---|---|
| `orders` | `backend/src/main/resources/db/migration/orders/V2__olist_orders_tables.sql` | `customers`, `orders`, `order_items`, `order_payments`, `order_reviews` |
| `catalog` | `backend/src/main/resources/db/migration/catalog/V2__olist_catalog_tables.sql` | `product_categories`, `products`, `sellers` |

Columnas, tipos y restricciones: ver [data-model.md](../data-model.md).

## Reglas del DDL

| ID | Regla | Verificación |
|---|---|---|
| D1 | Cada script solo crea objetos de su propio esquema y no califica nombres con el esquema (Flyway fija `defaultSchema`) | Lint S2 de LAB-105 sobre los scripts nuevos |
| D2 | Ninguna `REFERENCES` apunta a otro esquema | Lint S1 y consulta S5 en integración |
| D3 | `order_items.product_id` y `order_items.seller_id` son `text NOT NULL` con índice y **sin** FK | Prueba de integración: lista las FKs de `order_items` y solo hay una (hacia `orders`) |
| D4 | Sin `CREATE SCHEMA`, `SET search_path` ni `SET SCHEMA` | Lint S3 |
| D5 | Las FKs internas existen (cliente→pedido, pedido→ítems/pagos/reseñas, categoría→producto) | Prueba de integración sobre `pg_constraint` |
| D6 | Los scripts V1 de LAB-105 no se modifican | Revisión de diff; Flyway rechaza cambios por checksum |
| D7 | Los scripts son idempotentes en el sentido de Flyway: se aplican una vez y quedan en `<modulo>.flyway_schema_history` | Prueba de integración: arrancar dos veces no cambia el historial |

## Consultas de verificación

Sin FKs entre esquemas (debe devolver 0 filas): la consulta S5 del contrato de LAB-105.

Referencias de `order_items` (debe listar solo la FK hacia `orders`):

```sql
SELECT c.conname, confrelid::regclass AS referenced_table
FROM pg_constraint c
WHERE c.contype = 'f' AND c.conrelid = 'orders.order_items'::regclass;
```

## Compatibilidad

- Los esquemas `tickets`, `chat` y `retrieval` no cambian (spec, historia 3, escenario 4).
- Las migraciones posteriores (`V3__…`) de cada módulo siguen la misma convención.
