# Research: Carga del dataset Olist en esquemas por contexto

Todas las decisiones de Fase 0. No quedan `NEEDS CLARIFICATION`. Los datos del dataset (conteos, columnas) son los del
«Brazilian E-Commerce Public Dataset by Olist» y **deben contrastarse con la descarga real** en la primera ejecución
(tarea de verificación en `quickstart.md`); no se han podido verificar desde este entorno.

## D-1. Quién crea las tablas y quién las llena

- **Decisión**: las tablas las crea el backend con migraciones Flyway del módulo dueño (`V2__…`); el cargador Python
  solo inserta datos y **no ejecuta DDL**. Antes de cargar comprueba que las tablas existen.
- **Rationale**: es lo que exige el criterio 4 y el ADR-0015 (migraciones en el módulo dueño); evita dos fuentes de
  verdad para el esquema y hace que extraer un módulo mueva su DDL con él. El cargador escribe en dos esquemas porque
  es una herramienta de siembra de desarrollo, no un módulo: no hay joins ni FKs, solo `COPY` independiente por
  esquema.
- **Alternativas**: el cargador crea las tablas (rechazada: duplica el DDL y se desincroniza); sembrar desde Java con un
  `ApplicationRunner` (rechazada: el criterio pide un script Python y mezclaría 100 mil filas con el arranque).

## D-2. Estrategia de carga repetible: transacción por esquema con `TRUNCATE` + `COPY`

- **Decisión**: por cada esquema, una transacción que hace `TRUNCATE` de sus tablas y `COPY` desde los CSV
  transformados; se confirma al final. El orden es: `catalog` y `orders` por separado (no dependen entre sí).
- **Rationale**: satisface FR-008 con la menor complejidad: sin datos parciales (la transacción revierte), mismo
  estado al repetir, sin lógica de upsert. Con ~400 mil filas el `TRUNCATE` + `COPY` tarda segundos.
- **Alternativas**: `INSERT … ON CONFLICT DO NOTHING` (rechazada: deja datos antiguos si el dataset cambia y es más
  lento); tablas de staging con intercambio (rechazada: sobredimensionada, YAGNI).
- **Nota**: `TRUNCATE` toma un bloqueo exclusivo; es aceptable porque la carga es solo local. Dentro de `orders` el
  `TRUNCATE` va en una sola sentencia con todas las tablas del esquema (resuelve las FKs internas).

## D-3. Descarga del dataset

- **Decisión**: descarga HTTP con `httpx` del conjunto `olistbr/brazilian-ecommerce` desde la API de Kaggle con
  autenticación básica (`KAGGLE_USERNAME` y `KAGGLE_KEY` por entorno), al ZIP `data/raw/olist.zip`, y extracción a
  `data/raw/olist/`. Si el ZIP o los CSV ya existen, se reutilizan (FR-009). `--source <ruta>` acepta un ZIP o una
  carpeta ya descargados y evita la red.
- **Rationale**: evita arrastrar la librería `kaggle` (que exige un `kaggle.json` y es pesada); `httpx` y la API REST
  bastan. `data/raw/` ya está en `.gitignore` (FR-013). El dataset se publica bajo CC BY-NC-SA 4.0: uso no comercial,
  por eso no se versiona.
- **Riesgo**: el endpoint exacto de descarga y el formato de credenciales de Kaggle cambian con el tiempo y no se
  verificaron aquí; la tarea correspondiente lo comprueba contra la documentación vigente y, si el esquema de
  autenticación no sirve, se cambia a la librería `kagglehub` sin afectar al resto del diseño (el módulo `download.py`
  oculta el origen).
- **Alternativas**: `kagglehub` (válida, más dependencias); un espejo público en otro sitio (rechazada: fuente
  no oficial y legalmente menos clara); versionar los CSV en el repositorio (rechazada por licencia y tamaño, ~120 MB).

## D-4. Reparto de tablas del dataset entre esquemas

| Archivo de Olist | Esquema | Tabla destino |
|---|---|---|
| `olist_customers_dataset.csv` | `orders` | `customers` |
| `olist_orders_dataset.csv` | `orders` | `orders` |
| `olist_order_items_dataset.csv` | `orders` | `order_items` |
| `olist_order_payments_dataset.csv` | `orders` | `order_payments` |
| `olist_order_reviews_dataset.csv` | `orders` | `order_reviews` |
| `olist_products_dataset.csv` | `catalog` | `products` |
| `product_category_name_translation.csv` | `catalog` | `product_categories` |
| `olist_sellers_dataset.csv` | `catalog` | `sellers` |
| `olist_geolocation_dataset.csv` | (fuera de alcance, FR-016) | — |

- **Rationale**: clientes, pedidos, ítems, pagos y reseñas giran alrededor del pedido; productos, categorías y
  vendedores son la oferta. El precio es un hecho del ítem de pedido (se queda en `orders`); `catalog` guarda un
  precio de referencia propio (D-6).
- **Referencias entre contextos**: `order_items.product_id` y `order_items.seller_id` son columnas `text` sin FK. Se
  indexan para poder resolverlas por la API del módulo dueño.
- **Reseñas**: en `orders` porque se emiten sobre un pedido.

## D-5. Claves e integridad dentro de cada esquema

- **Decisión**: claves naturales del dataset como PK donde son únicas (`customer_id`, `order_id`, `product_id`,
  `seller_id`, `product_category_name`); `order_items` con PK `(order_id, order_item_id)`; `order_payments` con PK
  `(order_id, payment_sequential)`. **Reseñas**: PK surrogate `bigint generated always as identity`, con `review_id`
  e `order_id` como columnas indexadas, porque el dataset repite `review_id` en pedidos distintos (alrededor de 800
  repetidos, a confirmar con la descarga). FKs internas: `orders.customer_id → customers`, `order_items.order_id`,
  `order_payments.order_id` y `order_reviews.order_id → orders`; `products.category_name → product_categories`.
- **Productos con categoría sin traducción** (dos conocidas) o sin categoría (≈600 con valor vacío): ver D-7.
- **Rationale**: PK natural es lo más simple y verificable; la surrogate evita descartar reseñas válidas por una
  anomalía del origen.
- **Nota sobre `products.category_name`**: para conservar productos con categoría sin traducción (spec, Edge Cases), el
  cargador inserta también en `product_categories` esas categorías con traducción nula; así la FK interna se
  mantiene sin descartar filas.

## D-6. Stock sintético y precio de referencia

- **Stock** (aclarado 2026-10-27, opción A): `h` = los 4 primeros bytes de `sha256("olist-stock-v1:" + product_id)`
  como entero. Si `h % 10 == 0` el stock es 0 (≈10 % agotados); si no, `1 + (h // 10) % 200`. Es determinista (no
  depende de ejecución, hora ni orden), cumple FR-015 y se documenta en `docs/olist-dataset.md`. La versión en la
  cadena (`v1`) permite cambiar la fórmula de forma explícita.
- **Precio de referencia**: mediana de `price` de los ítems de cada `product_id`, a 2 decimales. Productos sin
  ventas: `NULL` (columna nullable). Se calcula en el cargador leyendo el CSV de ítems; no se hace un join entre
  esquemas en la base de datos.
- **Alternativas**: media (sensible a precios atípicos), último precio (depende del orden), precio constante (menos
  realista).

## D-7. Datos sucios del origen

- **Decisión**: valores vacíos → `NULL` en columnas opcionales (fechas de entrega de pedidos no entregados, comentarios
  de reseñas, medidas de productos). Solo se descartan filas que violen una restricción interna (p. ej. un pago o
  ítem cuyo `order_id` no está en pedidos), y se cuentan por motivo en el resumen (FR-011). Las referencias entre
  esquemas (producto o vendedor ausente en `catalog`) **no** se descartan: se cuentan como huérfanas informativas
  (historia 2, escenario 3).
- **Columnas con nombre mal escrito en el origen** (`product_name_lenght`, `product_description_lenght`): se
  renombran en la tabla destino a `name_length` y `description_length`; el mapa vive en `transform.py`.
- **Fechas**: se interpretan como hora local sin zona (el dataset no indica zona) y se guardan como
  `timestamp without time zone`.

## D-8. Precondiciones y mensajes de error

- **Decisión**: `preflight.py` comprueba (a) conexión, (b) que existen los esquemas `orders` y `catalog` y todas las
  tablas esperadas con su clave primaria, y (c) que el historial de Flyway de cada esquema no está en estado fallido.
  Si falta algo, sale con código distinto de cero y el mensaje «arranca el backend para aplicar las migraciones»
  (FR-010).
- **Rationale**: es el comportamiento pedido en la spec y evita crear tablas por la puerta de atrás.

## D-9. Resumen y trazabilidad

- **Decisión**: `report.py` imprime por esquema y tabla `leídas / cargadas / descartadas (motivo)`, el recuento de
  referencias huérfanas entre esquemas y el tiempo total. Los logs no incluyen datos personales de clientes (ya
  anonimizados, aun así no se registran valores de fila).

## D-10. Pruebas

- **Cargador**: `pytest` con `testcontainers[postgres]`; el `conftest.py` crea una Postgres desechable y ejecuta los
  `V*.sql` **reales** de cada módulo contra el esquema correspondiente (misma fuente que el backend), de modo que el
  DDL probado es el de producción. CSV de ejemplo sintéticos y pequeños (unas decenas de filas con valores obvios de
  prueba, incluidos casos límite: reseña repetida, producto sin categoría, categoría sin traducción, ítem con
  producto inexistente, pedido sin entregar).
- **Backend**: las pruebas de LAB-105 (S4/S5) ya verifican esquemas y FKs cruzadas; se amplían para comprobar las
  tablas esperadas y que `order_items` no tiene ninguna FK hacia otro esquema. El lint S1–S3 se ejecuta sobre los
  nuevos scripts (no pueden calificar nombres con el otro esquema).
- **Requiere Docker** en local y CI (igual que las pruebas de contexto del backend).

## D-11. CI

- **Decisión**: el job `python` de `.github/workflows/ci.yml` ya itera sobre `ingest` y `eval`; se añade `data/olist`
  al filtro de rutas y a la matriz. El job detecta `pyproject.toml` por componente, así que no rompe nada mientras no
  exista. `images.yml` no se toca (el cargador no tiene imagen).
- **Alternativas**: dejar el cargador fuera de la CI (rechazada: el principio IV exige pruebas ejecutadas).

## D-12. Infraestructura y despliegue

- **Decisión**: ningún cambio en `infra/` ni en `shoplab-infra`. La carga es solo local (FR-016) y el cargador usa
  `POSTGRES_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD` con los mismos valores por defecto que
  `infra/docker-compose.yml`. En dev (Kubernetes) las tablas se crearán al desplegar el backend, pero **vacías**; la
  carga en dev es una historia aparte si se necesita.
