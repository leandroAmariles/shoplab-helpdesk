# Contrato: el notebook `ml/notebooks/olist-eda.ipynb`

Lo que el notebook garantiza a quien lo ejecuta y a las pruebas (`test_notebook.py`). No hay contrato HTTP: esta historia no
expone endpoints.

## Variables de entorno

| Variable | Defecto | Uso |
|---|---|---|
| `POSTGRES_URL` | `postgresql://localhost:5432/shoplab` | Conexión; acepta la forma `jdbc:postgresql://…` del backend |
| `POSTGRES_USER` | `shoplab` | Usuario |
| `POSTGRES_PASSWORD` | `change-me-local-only` | Valor local de `infra/docker-compose.yml`; nunca se escribe en salidas |
| `EDA_MIN_GROUP_ORDERS` | `30` | Pedidos mínimos para que un estado o una categoría entre en el ranking de retrasos |
| `KB_DIR` | `data/kb` (desde la raíz del repo) | Corpus de políticas |

Ninguna es obligatoria en local: con `docker compose` levantado y Olist cargado, el notebook corre sin editar nada.

## Estructura (orden fijo de secciones)

Cada sección es un encabezado de Markdown de nivel 2 con este número y título; `test_notebook.py` comprueba que existen y
en este orden.

| Nº | Título | Historia / FR |
|---|---|---|
| 0 | Parámetros | FR-019: **una sola celda** con todos los parámetros y una explicación breve cada uno |
| 1 | Preflight | FR-018: **segunda celda de código**; se detiene antes de calcular |
| 2 | Carga de datos | FR-002: lectura de solo lectura; tamaño de cada tabla |
| 3 | Calidad por tabla | FR-003 a FR-007 (US1): ocho subsecciones, una por tabla |
| 4 | Integridad entre esquemas | FR-008 (US1) |
| 5 | Tipos de pago | FR-009 (US2) |
| 6 | Estados del pedido | FR-009 (US2) |
| 7 | Contraste con políticas y con la consulta de pedidos | FR-010 (US2) |
| 8 | Tiempos de entrega | FR-011, FR-012 (US3) |
| 9 | Retrasos por estado del cliente | FR-013 (US3) |
| 10 | Retrasos por categoría | FR-013 (US3) |
| 11 | Reseñas | FR-014 (US3) |
| 12 | Discrepancias y acciones | FR-016 (US4): tabla generada por código |
| 13 | Conclusiones | FR-015, FR-017 (US4): Markdown escrito sobre la ejecución guardada |
| 14 | Límites | FR-017 |

## Preflight (celda 2)

Antes de calcular nada comprueba, en este orden, y se detiene con `PreflightError` en la primera falla:

| Situación | Mensaje (resumen) |
|---|---|
| Base de datos inalcanzable (timeout 5 s) | «No se pudo conectar a `<host>:<puerto>`. Arranca la base local: `docker compose -f infra/docker-compose.yml up -d --wait`.» |
| Falta un esquema o una tabla | «Faltan tablas en `<esquema>`: …. Arranca el backend para aplicar las migraciones.» |
| Una tabla está vacía | «La tabla `<esquema>.<tabla>` no tiene filas. Carga Olist: `cd data/olist && uv run olist-loader load`.» |

Si el preflight falla, las celdas posteriores no se ejecutan (`nbconvert` termina con error) y no queda ningún resultado
parcial presentado como válido.

## Garantías sobre las salidas guardadas

- Solo agregados y muestras de **≤ 5 filas** sin columnas `*_id` (FR-024). Una prueba falla si encuentra una cadena
  hexadecimal de 32 caracteres en las salidas.
- Ninguna celda de perfilado contiene `iterrows`, `itertuples`, `apply(axis=1)` ni un `for` sobre filas de un DataFrame
  (FR-005, SC-007); una prueba lo comprueba sobre el código del notebook y del paquete.
- La tabla de la sección 12 no tiene ninguna fila sin acción (SC-003).
- Las cifras clave coinciden con `results/olist-eda-summary.json` ([summary-format.md](summary-format.md)).
- El tamaño del `.ipynb` no supera ~2 MB.

## Comportamiento con volumen pequeño (CI)

Con las fixtures de LAB-17 (3 pedidos) el notebook debe terminar sin error: los grupos bajo el umbral se muestran como tal,
las secciones con pocos datos muestran un aviso en lugar de gráficos vacíos, y no hay divisiones por cero. Un banner al
inicio de la sección 2 indica el tamaño de los datos («volumen reducido: muestra de pruebas») cuando hay menos de 1 000
pedidos.

## Cómo se ejecuta

| Dónde | Comando |
|---|---|
| Local | `cd ml && uv sync && uv run jupyter nbconvert --to notebook --execute --inplace notebooks/olist-eda.ipynb` |
| CI (job `ml-notebook`) | igual, con `--output-dir "$RUNNER_TEMP"` en lugar de `--inplace`, y `EDA_MIN_GROUP_ORDERS=1` |
