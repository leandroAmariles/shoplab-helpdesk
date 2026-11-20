# Research: Análisis exploratorio de Olist con Pandas y NumPy

Todas las decisiones que la spec dejó al plan. No queda ningún `NEEDS CLARIFICATION`.

## D-1. Un proyecto uv en `ml/`, paquete + notebook delgado

- **Decisión**: `ml/` es un proyecto uv (`shoplab-ml`) con el paquete `olist_eda` en `src/` y el notebook en `ml/notebooks/`.
  La lógica vive en el paquete (probada, con lint); el notebook solo la orquesta y la interpreta.
- **Razón**: es el patrón de LAB-26, que ya demostró que un notebook con lógica embebida no se puede probar ni revisar en un
  diff. Un solo proyecto para `ml/` porque las historias siguientes de F8 compartirán entorno.
- **Alternativas**: un proyecto por notebook (se multiplicaría `uv.lock` y CI sin necesidad); toda la lógica dentro del
  notebook (no probable, sin cobertura ni lint); ampliar `notebooks/` (la historia exige `ml/notebooks/`, y F8 es otro
  dominio que el de los experimentos de RAG).

## D-2. Lectura de Postgres con `psycopg` y DataFrames, sin SQLAlchemy

- **Decisión**: `db.py` abre una conexión con `psycopg` 3 (el driver del cargador), ejecuta `SELECT *` por tabla y construye el
  DataFrame con `pd.DataFrame(cursor.fetchall(), columns=[d.name for d in cursor.description])`. Después convierte los tipos
  (`numeric` → `float64`, `timestamp` → `datetime64[ns]`).
- **Razón**: `pandas.read_sql` solo soporta oficialmente SQLAlchemy y emite una advertencia con conexiones DBAPI; añadir
  SQLAlchemy para ocho `SELECT` es peso innecesario (Constitución VII). Construir un DataFrame desde tuplas no es un bucle
  por fila del análisis: el criterio de la historia (sin bucles por fila) se refiere a los cálculos de perfilado, y todo
  cálculo posterior es vectorizado.
- **Alternativas**: `COPY ... TO STDOUT` a CSV en memoria y `read_csv` (más rápido, pero reintroduce un paso por CSV que la
  historia quiere evitar); `connectorx` o `adbc` (dependencias binarias extra sin necesidad con ~100 000 filas por tabla).

## D-3. Conexión de solo lectura

- **Decisión**: la conexión se abre con `options=-c default_transaction_read_only=on`. Cualquier `INSERT`/`UPDATE`/DDL
  accidental falla en la base de datos.
- **Razón**: FR-025 pide no modificar esquemas ni datos; una garantía de ejecución vale más que una convención.
- **Alternativas**: un rol de base de datos de solo lectura (requiere cambiar `infra/postgres/init`, fuera de alcance, y un
  secreto más); confiar en que el código solo hace `SELECT`.

## D-4. Configuración: variables de entorno del cargador, sin importarlo

- **Decisión**: `AnalysisSettings` lee `POSTGRES_URL` (acepta la forma `jdbc:postgresql://`), `POSTGRES_USER` y
  `POSTGRES_PASSWORD` con los mismos valores por defecto locales que `olist_loader.config` (`shoplab` / `change-me-local-only`
  de `infra/docker-compose.yml`). Añade `EDA_MIN_GROUP_ORDERS` (umbral mínimo por grupo, por defecto 30) y
  `KB_DIR` (por defecto `data/kb` resuelto desde la raíz del repositorio).
- **Razón**: cumple FR-019 (sin editar credenciales) y mantiene `ml/` desacoplado de `data/olist`: son proyectos con ciclos
  distintos; importar `olist_loader` acoplaría dependencias y lockfiles por 20 líneas.
- **Alternativas**: dependencia por ruta a `olist-loader` (acoplamiento y dependencia transitiva de `typer` y `httpx`).

## D-5. Preflight antes de calcular

- **Decisión**: la segunda celda de código (tras los parámetros) llama a `preflight()`: conecta (timeout de 5 s, como el
  cargador), comprueba que existen los dos esquemas y las ocho tablas y que cada una tiene al menos una fila. Si falla,
  lanza `PreflightError` con el motivo y el paso para resolverlo (levantar `docker compose`, arrancar el backend para aplicar
  las migraciones, ejecutar `olist-loader load`), y las celdas siguientes no se ejecutan.
- **Razón**: FR-018 y la edge case de base inalcanzable; mismo criterio que LAB-26 (detenerse antes de producir resultados
  parciales). La comprobación de «tabla vacía» evita un análisis de ceros que parece válido.

## D-6. Extracción de lo que dicen las políticas

- **Decisión**: `policies.py` parsea los Markdown de `data/kb` (sin `INDEX.md` ni `REVIEW.md`) y produce dos conjuntos:
  - **Declarados**: los valores en código de las tablas canónicas: la tabla «Métodos de pago» de
    `payments-01-payment-methods` (columna del identificador, p. ej. `credit_card`) y la tabla «Estados del pedido» de
    `shipping-03-order-tracking` (p. ej. `shipped`). Hoy: 4 tipos de pago y 8 estados.
  - **Mencionados**: todo token entre comillas invertidas con forma `[a-z_]+` que coincida con un valor observado, con la lista
    de documentos que lo citan (`canceled` aparece también en la política de boleto y en la de cancelaciones).
  El contraste usa los **declarados** para «valor de la política que no existe en los datos» y los **mencionados** para
  «valor de los datos que ninguna política cita».
- **Razón**: distingue «la política lo define» de «alguien lo nombra de pasada» y evita falsos negativos. Si se rompe el
  formato de las tablas, `PolicyParseError` falla con el documento y la sección, y una prueba lo verifica contra el corpus
  real.
- **Alternativas**: búsqueda de texto libre del valor en todo el corpus (falsos positivos: `approved` aparece en prosa);
  lista de valores copiada a mano (se desincroniza del corpus en silencio).

## D-7. Estados de la consulta de pedidos (LAB-41, aún sin implementar)

- **Decisión**: `ml/notebooks/data/order-tool-states.toml` declara los estados que mostrará la herramienta
  `getOrderStatus`, con un campo `source` que lo explica. Hoy contiene los 8 estados de la tabla de seguimiento del corpus
  (la historia LAB-41 no define otro conjunto). Cuando LAB-41 se implemente, ese archivo es lo único que se actualiza.
- **Razón**: el contraste con una herramienta inexistente no puede leerse del código; un archivo de datos explícito y
  versionado deja la suposición a la vista (spec, Assumptions) y la hace fácil de corregir.
- **Límite**: mientras el archivo coincida con la tabla del corpus, ese contraste no aporta información nueva; el notebook lo
  dice en la sección correspondiente en lugar de aparentar un doble contraste.
- **Alternativas**: leer el estado del backend (no existe); omitir el contraste (incumple el criterio).

## D-8. Nulos esperados frente a inesperados

- **Decisión**: reglas declaradas en `schema.py` como funciones vectorizadas que devuelven la máscara de nulos
  *inesperados*; el resto de nulos son *esperados por el origen*. Reglas iniciales:
  - `orders.delivered_to_customer_at` nulo y `status == 'delivered'` → inesperado.
  - `orders.delivered_to_carrier_at` nulo y `status ∈ {shipped, delivered}` → inesperado.
  - `orders.approved_at` nulo y `status ∉ {created, canceled}` → inesperado.
  - `order_reviews.comment_title`, `comment_message` y `answered_at` → siempre esperados (opcionales en Olist).
  - `products.*` con nulos y `category_name` nulo → inesperados (producto sin categoría o sin medidas).
  - Columnas sin regla: cualquier nulo se reporta como inesperado, para que un hallazgo nuevo no pase inadvertido.
- **Razón**: FR-007 pide declarar el criterio; la regla por defecto («todo nulo sin regla es inesperado») es la más segura.
- **Alternativas**: tratar todo nulo como defecto (ruido: un pedido cancelado no se entrega); tratar los nulos como esperados
  por defecto (oculta hallazgos).

## D-9. Duplicados: clave natural y fila completa

- **Decisión**: por tabla se calculan dos cifras con `DataFrame.duplicated`: por clave natural y por **fila completa
  excluyendo la columna sustituta** (`order_reviews.id`). Claves naturales: `customers.customer_id`, `orders.order_id`,
  `order_items.(order_id, item_number)`, `order_payments.(order_id, payment_sequence)`, `order_reviews.review_id`,
  `product_categories.name`, `products.product_id`, `sellers.seller_id`. Además, como **dato informativo** (no defecto):
  `customers.customer_unique_id` repetido (una persona con varios `customer_id`) y `order_reviews.order_id` repetido.
- **Razón**: FR-006. En las tablas con clave primaria la duplicidad por clave es 0 por construcción; lo interesante es
  `review_id` (no único en el origen, LAB-17), los duplicados exactos y las repeticiones legítimas, que se explican en lugar
  de contarse como error.

## D-10. Integridad entre esquemas

- **Decisión**: `integrity.py` cuenta con `Series.isin` los ítems cuyo `product_id` no está en `catalog.products` y cuyo
  `seller_id` no está en `catalog.sellers`, y los productos con `category_name` ausente de `product_categories` (esto último
  tiene FK interna, debe dar 0). Informa cantidad y porcentaje.
- **Razón**: FR-008; LAB-17 eliminó las FK entre esquemas a propósito, así que esta es la única comprobación de integridad
  que existirá.

## D-11. Tiempos de entrega, retraso y población analizada

- **Decisión**: columnas derivadas, todas vectorizadas con `Timedelta` convertido a días (`/ np.timedelta64(1, "D")`):
  - `actual_days = delivered_to_customer_at − purchased_at`
  - `estimated_days = estimated_delivery_at − purchased_at`
  - `delay_days = delivered_to_customer_at − estimated_delivery_at` (positivo = tarde)
  - `is_delayed = delay_days > 0`
- **Población analizada**: pedidos con `delivered_to_customer_at` no nulo **y** fechas coherentes (entrega ≥ compra, fecha
  estimada ≥ compra). El resto se cuenta por motivo y se muestra: sin fecha de entrega (por estado), entrega anterior a la
  compra, estimada anterior a la compra, y `delivered` sin fecha.
- **Razón**: FR-011 y FR-012. Mide solo lo medible y deja visible lo excluido. «A tiempo» = `delay_days ≤ 0`; se separa
  «antes» (`< 0`) y «el mismo día» (`= 0`) en la fracción que pide el criterio.
- **Alternativas**: imputar fechas faltantes (inventa datos); contar por `status == 'delivered'` (incluye `delivered` sin
  fecha, que no se puede medir).

## D-12. Categoría principal de un pedido con varias categorías

- **Decisión**: cada pedido se asigna a **la categoría de su ítem de mayor precio**; el empate se rompe por menor
  `item_number`. Se calcula con `sort_values` + `drop_duplicates(subset="order_id")` sobre `order_items` unido a
  `products`. El nombre mostrado es `name_en` y, si no hay traducción, el nombre original; sin categoría → «sin categoría».
- **Razón**: cuenta cada pedido una sola vez (la alternativa de contarlo en cada categoría infla el volumen y duplica el
  retraso). Es simple, determinista y declarable. El número de pedidos multicategoría se informa para dimensionar el sesgo.
- **Alternativas**: contar el pedido en cada categoría presente; prorratear (complejidad sin beneficio para un análisis
  descriptivo).

## D-13. Umbral mínimo por grupo

- **Decisión**: los grupos (estado o categoría) con menos de `EDA_MIN_GROUP_ORDERS` pedidos (por defecto **30**, la
  referencia habitual para que una media sea mínimamente estable) se muestran aparte como «grupos pequeños» y quedan fuera
  del ranking. En CI se fija en `1` porque las fixtures tienen 3 pedidos.
- **Razón**: la spec exige un umbral declarado; la variable de entorno permite ejecutar con volumen pequeño sin tablas
  vacías (edge case de datos en CI). Si ningún grupo alcanza el umbral, el notebook lo dice en lugar de dibujar un gráfico
  vacío.

## D-14. Reseñas y retraso

- **Decisión**: se calcula la puntuación media por pedido (un pedido puede tener varias reseñas) y se compara por categoría
  de retraso: «a tiempo o antes», «1 a 7 días tarde» y «más de 7 días tarde» (`np.select`). Se muestran el histograma de
  puntuaciones 1 a 5 y la puntuación media y mediana por categoría de retraso.
- **Razón**: FR-014; los tramos son legibles y descriptivos, sin afirmar causalidad (límite declarado en las conclusiones).

## D-15. Hallazgos y acciones: «ninguna discrepancia sin acción» por construcción

- **Decisión**: `findings.py` define el tipo `Finding(kind, subject, count, share, area, impact)` y un detector por tipo
  (catálogo en [data-model.md](data-model.md)). Las acciones viven en `discrepancy-actions.toml` con dos niveles de clave:
  `kind:subject` (específica, curada a mano tras ver el análisis real) y `kind` (genérica). `resolve_action` busca la
  específica, luego la genérica y, si no existe ninguna, lanza `MissingActionError`.
- **Razón**: SC-003 y FR-016 se cumplen mecánicamente: ejecutar el notebook con datos que produzcan un hallazgo nuevo sin
  acción lo hace fallar, también en CI. Las acciones específicas se escriben durante la implementación, mirando los
  hallazgos reales de la ejecución local; el texto narrativo de las conclusiones se escribe sobre esa ejecución.
- **Alternativas**: tabla de acciones escrita a mano en Markdown (se desincroniza del análisis y no se puede validar);
  generar la acción automáticamente (no es criterio de negocio).

## D-16. Salidas del notebook: agregados, sin identificadores

- **Decisión**: ninguna celda muestra más de 5 filas de una tabla, y nunca columnas de identificadores (`*_id`). Una prueba
  recorre las salidas del `.ipynb` guardado y falla si encuentra cadenas hexadecimales de 32 caracteres (la forma de los
  identificadores de Olist). Los gráficos se guardan como PNG embebidos con resolución moderada para mantener el notebook por
  debajo de ~2 MB.
- **Razón**: FR-024 y la licencia CC BY-NC-SA del dataset: no se versiona ni se vuelca, solo se resume.

## D-17. CI: matriz de Python para lint y pruebas, y un job con Postgres para ejecutar el notebook

- **Decisión**:
  1. Filtro nuevo `ml`: `ml/**`, `data/kb/**` (el parser lee el corpus), `data/olist/**` (cargador y fixtures), las
     migraciones de `orders` y `catalog`, y el propio `ci.yml`.
  2. `ml` se añade a la matriz de Python (`ruff` + `pytest`, sin base de datos).
  3. Job nuevo `ml-notebook`, con servicio Postgres (`pgvector/pgvector:0.8.7-pg16`, la imagen de `infra/docker-compose.yml`):
     `ml/ci/prepare-database.sh` aplica con `psql` las migraciones `V*__*.sql` de `orders` y `catalog` en orden de versión
     (la misma lógica que `data/olist/tests/conftest.py`) y carga las **fixtures de LAB-17**
     (`data/olist/tests/fixtures`) con `olist-loader load --source`. Después, `uv run jupyter nbconvert --to notebook --execute
     --output-dir "$RUNNER_TEMP" ml/notebooks/olist-eda.ipynb` con `EDA_MIN_GROUP_ORDERS=1`. La salida va a un directorio
     temporal para no modificar el notebook versionado.
  4. `ml-notebook` se añade a `needs` de `ci-gate`.
- **Razón**: el dataset completo no se puede descargar en CI (licencia y credenciales de Kaggle), y las fixtures reales del
  cargador ya existen: se reutilizan, no se duplican. Ejecutar contra Postgres de verdad, y no contra un simulacro, es lo que
  hace que el criterio «se ejecuta en CI» valga algo. Ejecutar en un directorio temporal evita que CI y el repositorio
  discrepen.
- **Riesgo conocido**: con 3 pedidos las cifras del notebook en CI no se parecen a las de la ejecución completa; por eso las
  pruebas de contenido usan DataFrames sintéticos, y el job de CI solo garantiza que el notebook **corre sin error con
  volumen pequeño**, no que las conclusiones sigan siendo ciertas. Las conclusiones están escritas sobre la ejecución local y
  la prueba `test_notebook` comprueba que coinciden con `olist-eda-summary.json`.
- **Alternativas**: descargar Olist en CI (inviable); generar un dataset sintético mayor (esfuerzo sin retorno para esta
  historia); ejecutar el notebook en el job de la matriz sin Postgres (no puede leer la base de datos).

## D-18. Coherencia entre conclusiones y resultados

- **Decisión**: el titular numérico de cada sección lo genera código; la interpretación en Markdown está escrita sobre la
  ejecución guardada. `olist-eda-summary.json` guarda las cifras clave (filas por tabla, frecuencias, fracciones de retraso,
  hallazgos) y `test_notebook.py` comprueba que el notebook guardado y el JSON coinciden en ellas. El README avisa de volver
  a leer las conclusiones si cambian los datos.
- **Razón**: es la lección de LAB-26: una conclusión en prosa sobre cifras viejas es la forma habitual de que un notebook
  mienta.

## D-19. Enmienda de la constitución y README

- **Decisión**: PATCH 1.0.1 → 1.0.2 de `.specify/memory/constitution.md`: en «Technology & Architecture Constraints» se
  añade `ml/` («análisis exploratorio y ML clásico de F8; nunca importado por código de producción») y se aclara que
  `notebooks/` conserva los experimentos de RAG. Se añade una fila de `ml/` al README del repositorio.
- **Razón**: la constitución prevalece sobre las demás guías; si no se enmienda, el directorio nuevo la contradice. Es una
  aclaración, no una redefinición de un principio, por eso PATCH.
