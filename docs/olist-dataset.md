# Dataset de ejemplo: Olist

Los módulos `orders` y `catalog` se prueban con el dataset público
[Brazilian E-Commerce Public Dataset by Olist](https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce): clientes,
pedidos, ítems, pagos, reseñas, productos, categorías y vendedores reales de un marketplace, ya anonimizados.
Lo carga el script de `data/olist/` (ver su [README](../data/olist/README.md)); la especificación está en
`specs/LAB-17-olist-dataset-by-context/`.

> **Licencia.** El dataset se publica bajo CC BY-NC-SA 4.0 (uso no comercial). Por eso **no se versiona**: se descarga
> a `data/raw/`, que git ignora. No subas esos archivos ni los de una base de datos cargada con ellos a ningún sitio
> público.

## Cómo cargarlo

1. Levanta la infraestructura local y arranca el backend, que crea las tablas con las migraciones de cada módulo:

   ```bash
   docker compose -f infra/docker-compose.yml up -d --wait
   cd backend && ./mvnw spring-boot:run
   ```

2. Carga los datos (la primera vez necesita las credenciales de Kaggle; basta con una cuenta gratuita):

   ```bash
   cd data/olist
   uv sync
   KAGGLE_USERNAME=<usuario> KAGGLE_KEY=<clave> uv run olist-loader load
   ```

   Si ya tienes el ZIP, usa `--source <ruta>` y no hace falta cuenta ni red. Repetir el comando es seguro: deja siempre
   el mismo estado.

Si las tablas no existen, el cargador no las crea: se detiene con el código de salida 4 y te pide arrancar el backend.

## Qué esquema recibe cada tabla

| Archivo de Olist | Esquema | Tabla |
|---|---|---|
| `olist_customers_dataset.csv` | `orders` | `customers` |
| `olist_orders_dataset.csv` | `orders` | `orders` |
| `olist_order_items_dataset.csv` | `orders` | `order_items` |
| `olist_order_payments_dataset.csv` | `orders` | `order_payments` |
| `olist_order_reviews_dataset.csv` | `orders` | `order_reviews` |
| `olist_products_dataset.csv` | `catalog` | `products` |
| `product_category_name_translation.csv` | `catalog` | `product_categories` |
| `olist_sellers_dataset.csv` | `catalog` | `sellers` |

La tabla de geolocalización del dataset no se carga. Las columnas y restricciones están en
`specs/LAB-17-olist-dataset-by-context/data-model.md` y en las migraciones `V2__…` de
`backend/src/main/resources/db/migration/{orders,catalog}/`.

## Referencias entre `orders` y `catalog`: solo por id

Cada módulo es dueño de su esquema (ADR-0015). Un ítem de pedido guarda `product_id` y `seller_id` como texto, **sin
clave foránea**: ni la base de datos ni el cargador comprueban que existan en `catalog`. Para obtener el producto de un
ítem, la capa `application` de `orders` pregunta a la API del módulo `catalog`; no hay `JOIN` entre esquemas. Los ítems
cuyo producto o vendedor no esté en `catalog` se cargan igualmente y el resumen los cuenta como referencias huérfanas.

## Datos que no vienen del dataset

- **Stock** (`catalog.products.stock`): Olist no trae inventario, así que se genera de forma **sintética y
  determinista** a partir del `product_id`: unos 1 de cada 10 productos queda en 0 (agotado) y el resto entre 1 y 200.
  Es siempre el mismo valor para el mismo producto, en cualquier equipo y en cualquier carga, y no representa
  inventario real. La fórmula está en `stock_for` de `data/olist/src/olist_loader/transform.py`; cambiarla exige subir
  la versión de la semilla (`olist-stock-v1`).
- **Precio de referencia** (`catalog.products.reference_price`): mediana de los precios de los ítems vendidos de ese
  producto, a dos decimales; vacío si el producto no se ha vendido. El precio de cada venta sigue en
  `orders.order_items.price`.
- **Categorías sin traducción**: se conservan con `name_en` vacío.

## Qué se descarta

El resumen final lista, por tabla, las filas leídas, cargadas y descartadas con su motivo. Solo se descartan filas que
romperían una regla dentro de su propio esquema: un valor obligatorio vacío, un importe negativo, una clave repetida, o
un ítem, pago o reseña de un pedido que no existe. Los valores vacíos opcionales (por ejemplo la fecha de entrega de un
pedido sin entregar) se guardan como nulos.

## Alcance

Solo desarrollo local. Cargar los datos en el entorno `dev` del clúster queda fuera de esta historia: allí el backend
crea las tablas al desplegarse, pero vacías.
