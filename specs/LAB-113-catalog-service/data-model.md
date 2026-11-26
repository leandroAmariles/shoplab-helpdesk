# Data Model: shoplab-catalog-service

Modelo de datos del microservicio de catálogo. Los nombres siguen la convención en inglés (Constitución, Principio III) y los tipos son nativos de PostgreSQL 16.

## Esquema `catalog`

### Tabla `product_categories`

Almacena la taxonomía de categorías de la tienda y sus traducciones oficiales.

| Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|
| `name` | text | PRIMARY KEY | Nombre canónico de la categoría en portugués (origen Olist) |
| `name_en` | text | NULL permitido | Traducción oficial de la categoría al inglés |

### Tabla `products`

Maestro de productos del catálogo con especificaciones físicas, precio de referencia e inventario disponible.

| Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|
| `product_id` | text | PRIMARY KEY | Identificador único del producto (alfanumérico de 32 caracteres) |
| `category_name` | text | NULL, FK → `product_categories(name)` | Categoría a la que pertenece el producto |
| `name_length` | integer | NULL permitido | Longitud del nombre del producto |
| `description_length` | integer | NULL permitido | Longitud del texto de descripción |
| `photos_count` | integer | NULL permitido | Cantidad de fotos registradas |
| `weight_g` | integer | CHECK (weight_g >= 0) | Peso en gramos del producto empaquetado |
| `length_cm` | integer | CHECK (length_cm >= 0) | Largo en centímetros |
| `height_cm` | integer | CHECK (height_cm >= 0) | Altura en centímetros |
| `width_cm` | integer | CHECK (width_cm >= 0) | Ancho en centímetros |
| `reference_price` | numeric(10,2) | CHECK (reference_price >= 0) | Mediana calculada de precios históricos de venta |
| `stock` | integer | NOT NULL, CHECK (stock >= 0) | Unidades disponibles (sintético determinista `olist-stock-v1`) |
| `created_at` | timestamptz | NOT NULL DEFAULT now() | Fecha de alta del producto en el catálogo |
| `updated_at` | timestamptz | NOT NULL DEFAULT now() | Última actualización de precio o stock |

**Índices**:
- `CREATE INDEX products_category_name_idx ON products (category_name);`
- `CREATE INDEX products_stock_idx ON products (stock);`
- `CREATE INDEX products_reference_price_idx ON products (reference_price);`

### Tabla `sellers`

Vendedores registrados en el marketplace Olist que suministran productos.

| Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|
| `seller_id` | text | PRIMARY KEY | Identificador único del vendedor |
| `zip_code_prefix` | text | NULL permitido | Prefijo de código postal (preserva ceros a la izquierda) |
| `city` | text | NULL permitido | Ciudad sede del vendedor |
| `state` | text | NULL permitido | Sigla del estado federativo |

## DDL Flyway (`V1__init_catalog_tables.sql`)

```sql
CREATE TABLE product_categories (
    name    text PRIMARY KEY,
    name_en text
);

CREATE TABLE products (
    product_id         text PRIMARY KEY,
    category_name      text REFERENCES product_categories (name) ON UPDATE CASCADE ON DELETE SET NULL,
    name_length        integer,
    description_length integer,
    photos_count       integer,
    weight_g           integer CHECK (weight_g >= 0),
    length_cm          integer CHECK (length_cm >= 0),
    height_cm          integer CHECK (height_cm >= 0),
    width_cm           integer CHECK (width_cm >= 0),
    reference_price    numeric(10,2) CHECK (reference_price >= 0),
    stock              integer NOT NULL DEFAULT 0 CHECK (stock >= 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category ON products (category_name);
CREATE INDEX idx_products_stock ON products (stock);
CREATE INDEX idx_products_price ON products (reference_price);

CREATE TABLE sellers (
    seller_id       text PRIMARY KEY,
    zip_code_prefix text,
    city            text,
    state           text
);
```
