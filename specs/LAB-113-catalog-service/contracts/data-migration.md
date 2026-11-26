# Contrato de Migración y Carga de Datos: Olist Catalog

Define las reglas de migración y poblamiento de datos para la base de datos `catalog`.

## Origen de Datos

Los datos provienen del dataset público Olist procesado por `data/olist/` (LAB-17).

| Archivo Olist | Tabla Destino | Volumen Estimado | Transformaciones Requeridas |
|---|---|---|---|
| `product_category_name_translation.csv` | `product_categories` | 71 categorías | Carga directa de `product_category_name` y `product_category_name_english`. |
| `olist_products_dataset.csv` | `products` | 32.951 productos | 1. `stock`: cálculo sintético determinista `olist-stock-v1`.<br/>2. `reference_price`: cálculo de mediana histórica de ventas. |
| `olist_sellers_dataset.csv` | `sellers` | 3.095 vendedores | Carga directa sin claves foráneas a otros esquemas. |

## Reglas de Integridad y Validación

1. **Cero FKs cruzadas**: No se crea ninguna clave foránea hacia órdenes, pagos o clientes de otros dominios.
2. **Determinismo de Stock (`olist-stock-v1`)**:
   - Para un `product_id` dado, `hash(product_id) % 10 == 0` asigna `stock = 0` (10% de productos agotados).
   - Para el resto, `(hash(product_id) % 200) + 1` asigna un stock entre 1 y 200 unidades.
3. **Idempotencia**: El script de carga es reejecutable de manera segura (`ON CONFLICT (product_id) DO NOTHING` o `UPSERT`).
4. **Verificación post-carga**:
   - `SELECT count(*) FROM product_categories` = 71
   - `SELECT count(*) FROM products` = 32.951
   - `SELECT count(*) FROM products WHERE stock = 0` ≈ 3.295 (±5%)
   - `SELECT count(*) FROM sellers` = 3.095
