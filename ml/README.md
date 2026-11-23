# ml — análisis exploratorio y ML clásico (F8)

Trabajo de ciencia de datos de ShopLab Helpdesk sobre los datos de Olist. Hoy contiene un notebook,
[`notebooks/olist-eda.ipynb`](notebooks/olist-eda.ipynb) (LAB-116, F8-01), que explora las tablas de los esquemas `orders` y
`catalog` con Pandas y NumPy para **conocer su calidad** y **validar los valores** que usan el corpus de políticas (LAB-18) y las
herramientas del chat (LAB-41). Las siguientes historias de F8 (entrenamiento, MLflow, inferencia) se añadirán aquí.

El notebook **solo analiza y propone**: no modifica los datos, el corpus ni el código de producción, y nada de `backend/` ni de
`ingest/` importa este proyecto. Los experimentos de RAG siguen en [`notebooks/`](../notebooks/README.md).

## Qué hace

| Sección | Contenido |
|---|---|
| 0–2 | Parámetros, comprobación previa (preflight) y carga de las ocho tablas |
| 3–4 | **Calidad** de cada tabla (nulos esperados e inesperados, duplicados, tipos, rangos) e integridad entre esquemas |
| 5–7 | **Valores** de `payment_type` y `order_status`, contrastados con las políticas y con la consulta de pedidos (LAB-41) |
| 8–11 | **Entregas**: tiempo real frente a estimado, retrasos por estado y por categoría, y puntuación de reseñas |
| 12 | **Discrepancias** y la acción propuesta para cada una (tabla generada por código) |
| 13–14 | **Conclusiones** y **límites**, escritos sobre la ejecución guardada |

Se guarda con sus salidas: puedes **leerlo sin ejecutarlo**, en GitHub o en cualquier visor. Las cifras clave están también en
[`notebooks/results/olist-eda-summary.json`](notebooks/results/olist-eda-summary.json).

> **Si cambian los datos o el corpus, vuelve a leer las conclusiones (secciones 13 y 14).** Las tablas y los titulares los genera
> el código, pero la interpretación está escrita a mano sobre la ejecución guardada: puede dejar de ser cierta aunque el
> notebook siga ejecutándose sin errores.

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- La base local de `docker compose` con **Olist cargado** (ver el siguiente apartado). El notebook **no descarga el dataset ni
  ejecuta el cargador**, y lee siempre de la base de datos: no hay ningún CSV de Olist en el repositorio.

## Preparar la base de datos

1. Levanta la base: `docker compose -f infra/docker-compose.yml up -d --wait`.
2. Arranca el backend una vez para que Flyway cree los esquemas `orders` y `catalog`.
3. Carga Olist con el cargador de LAB-17 ([`data/olist`](../data/olist/README.md)):

```bash
cd data/olist
uv sync
uv run olist-loader load                    # descarga (necesita cuenta de Kaggle) y carga
uv run olist-loader load --source ruta.zip  # o desde un ZIP ya descargado
```

## Ejecutar

```bash
cd ml
uv sync
uv run jupyter nbconvert --to notebook --execute --inplace notebooks/olist-eda.ipynb
```

También puedes abrirlo en Jupyter, VS Code o IntelliJ y «Ejecutar todo». Sin editar código, rutas ni credenciales, con el
dataset completo tarda unos segundos (menos de 5 minutos en una máquina modesta).

La conexión es de **solo lectura**: cualquier `INSERT`, `UPDATE` o DDL accidental lo rechaza Postgres.

Si la base no está lista, **la segunda celda de código** (el preflight) se detiene con un mensaje que dice qué falta y cómo
resolverlo, y no se calcula nada antes: base apagada, usuario o contraseña incorrectos, esquemas o tablas ausentes o una tabla
vacía.

## Variables de entorno

Ninguna es obligatoria: con `docker compose` levantado y Olist cargado, todo funciona con los valores por defecto.

| Variable | Defecto | Uso |
|---|---|---|
| `POSTGRES_URL` | `postgresql://localhost:5432/shoplab` | Conexión; acepta también la forma `jdbc:postgresql://…` del backend |
| `POSTGRES_USER` | `shoplab` | Usuario |
| `POSTGRES_PASSWORD` | `change-me-local-only` | Valor local de `infra/docker-compose.yml`; nunca se muestra |
| `EDA_MIN_GROUP_ORDERS` | `30` | Pedidos mínimos para que un estado o una categoría entre en el ranking de retrasos |
| `KB_DIR` | `data/kb` | Corpus de políticas |

Si tu base local usa otra contraseña (el volumen de Postgres conserva la que tenía al crearse), exporta `POSTGRES_PASSWORD`
antes de ejecutar.

## Cómo leer los resultados

- **Nulos esperados e inesperados.** Un nulo es *esperado* cuando el negocio lo explica (un pedido `canceled` no se entrega);
  si no, es *inesperado*. Una columna sin regla trata todo nulo como inesperado. Las reglas están en
  [`src/olist_eda/schema.py`](src/olist_eda/schema.py).
- **Duplicados.** Se miden por clave natural y por fila completa; `review_id` no es único en el origen, así que repetirse no es
  un defecto.
- **Retraso.** Un pedido tiene retraso cuando se entregó **después** de la fecha estimada. Solo se analizan los pedidos con
  fecha de entrega y fechas coherentes; cada excluido se cuenta con un único motivo.
- **Categoría de un pedido.** Se usa la de su ítem de mayor precio (a igualdad, el de menor número de ítem).
- **Hallazgos y acciones.** Cada discrepancia sale con su acción de
  [`notebooks/data/discrepancy-actions.toml`](notebooks/data/discrepancy-actions.toml): primero la regla específica y, si no la
  hay, la del tipo. **Si un hallazgo no tiene ninguna, el notebook falla.** Las acciones son propuestas: nada se aplica ni se
  crean tareas en Jira.
- **Estados de la consulta de pedidos (LAB-41).** LAB-41 aún no está implementada: los estados se declaran en
  [`notebooks/data/order-tool-states.toml`](notebooks/data/order-tool-states.toml) y hoy copian la política. Cuando LAB-41 exista,
  ese archivo es lo único que hay que actualizar.

## Integración continua

En cada pull request que toque `ml/**`, el corpus (`data/kb`), el cargador (`data/olist`) o las migraciones de `orders` y
`catalog`, `.github/workflows/ci.yml` ejecuta:

- **`python` (`ml`)**: `ruff` y `pytest`, sin base de datos.
- **`ml-notebook`**: levanta un Postgres, aplica las migraciones reales (`ml/ci/prepare-database.sh`), carga las **fixtures de
  LAB-17** (3 pedidos) y ejecuta el notebook con `nbconvert --execute`. Falla el pull request si cualquier celda da error.

El dataset completo es CC BY-NC-SA y **no se descarga en CI**, así que `ml-notebook` demuestra que el notebook se ejecuta con
poco volumen, no que sus conclusiones sigan siendo ciertas. Por eso sus cifras no se parecen a las del notebook guardado, y por eso
escribe su copia en un directorio temporal en lugar de sobrescribir el del repositorio (las salidas guardadas salen siempre de una
ejecución local con el dataset completo).

## Pruebas

```bash
cd ml
uv run ruff check .
uv run pytest        # sin red ni base de datos; cobertura mínima del 80 % del paquete
```

Las pruebas cubren el perfilado, los valores, las entregas y los hallazgos sobre DataFrames conocidos, el parser de las políticas
contra el corpus real y el notebook guardado: que tiene las 15 secciones en orden, que no recorre filas una a una, que no filtra
identificadores, rutas ni credenciales, y que las conclusiones citan las cifras de `olist-eda-summary.json`.

## Estructura

```text
ml/
├── notebooks/
│   ├── olist-eda.ipynb              # el notebook, con sus salidas
│   ├── data/
│   │   ├── order-tool-states.toml   # estados de la consulta de pedidos (LAB-41)
│   │   └── discrepancy-actions.toml # acción propuesta por hallazgo
│   └── results/olist-eda-summary.json
├── src/olist_eda/                   # la lógica: db, schema, profiling, integrity, policies, categorical,
│                                    # delivery, findings y report
├── tests/
└── ci/prepare-database.sh           # solo CI: migraciones y fixtures
```

La lógica vive en `src/olist_eda/` (probada y con lint); el notebook solo la orquesta y la explica. Especificación, plan y
decisiones en [`specs/LAB-116-olist-eda-notebook/`](../specs/LAB-116-olist-eda-notebook/).

## Dataset de intenciones del cliente (LAB-117)

> **Estado: el dataset existe (685 ejemplos), pero lo etiquetó y revisó el asistente de IA, no una persona.** El segundo
> anotador de la muestra de acuerdo (κ = 0,85) es el modelo local. Hasta que una persona complete
> [`data/intents/REVIEW.md`](data/intents/REVIEW.md) (leer la guía, revisar una muestra, leer los comentarios de Olist buscando
> nombres y repetir la muestra de acuerdo) es un **dataset sin validar por humanos**; la ficha lo declara.

Mensajes de clientes en español y portugués etiquetados con una de cinco intenciones (`politica`, `producto`, `pedido`,
`escalado`, `charla`), para entrenar y evaluar un clasificador propio (LAB-118). Vive en [`data/intents/`](data/intents/):

| Archivo | Para qué sirve |
|---|---|
| `intents.jsonl` | El dataset (685 ejemplos), con su partición train/val/test ya asignada |
| [`LABELING-GUIDE.md`](data/intents/LABELING-GUIDE.md) | Definición de cada clase, prioridad entre clases y casos límite |
| [`DATASHEET.md`](data/intents/DATASHEET.md) | Ficha: origen, licencias (Olist **CC BY-NC-SA 4.0**), limitaciones; el bloque de cifras lo genera `stats --write` |
| [`REVIEW.md`](data/intents/REVIEW.md) | Registro de la revisión manual (quién, cuándo, qué) |
| `annotations/` | Muestra doble-etiquetada de 50 casos y el kappa de Cohen |
| `work/` | **Ignorado por git**: CSV de revisión, borradores de paráfrasis y hoja del segundo anotador |

### Validar (lo que corre en CI, sin red ni base de datos)

```bash
cd ml
uv run python -m intent_dataset validate --golden ../eval/golden.jsonl
uv run python -m intent_dataset stats          # las cifras de la ficha
```

`validate` ejecuta todas las reglas (etiquetas, mínimos por clase e idioma, fugas entre particiones, datos personales, kappa,
ficha y guía), imprime **todas** las incidencias y devuelve `1` si hay alguna. **No puede comprobar** que una etiqueta sea la
correcta ni que no se haya colado un nombre: eso lo cubre la revisión manual.

### Construirlo (local, una vez)

| Paso | Comando | Necesita |
|---|---|---|
| 1. Candidatos (golden + reseñas, **enmascarados al leer**) | `uv run python -m intent_dataset candidates` | Olist cargado en la base local |
| 2. Revisar `data/intents/work/candidates.csv` y añadir semillas | (a mano: `decision`, `label`, `pii_checked`) | **una persona** |
| 3. Incorporar lo aceptado | `uv run python -m intent_dataset build data/intents/work/candidates.csv` | — |
| 4. Paráfrasis con el modelo local | `uv run python -m intent_dataset paraphrase --translate` | gateway + `SHOPLAB_ML_LLM_API_KEY` |
| 5. Revisar las paráfrasis y `build` otra vez | (a mano) | **una persona** |
| 6. Partición fija | `uv run python -m intent_dataset split` | — |
| 7. Muestra de acuerdo | `uv run python -m intent_dataset sample`, luego el segundo anotador etiqueta `work/annotator-b.csv` y `agreement --kind person` | **una segunda persona** (o `annotate`, que usa el modelo y **no** es un acuerdo entre personas) |
| 8. Ficha y comprobación | `stats --write` y `validate --golden ../eval/golden.jsonl` | — |

| Variable | Defecto | Uso |
|---|---|---|
| `SHOPLAB_ML_LLM_API_KEY` | **sin valor** | Clave virtual `eval` del gateway; solo `paraphrase` y `annotate`; nunca se imprime |
| `SHOPLAB_ML_LLM_BASE_URL` | `http://localhost:4000/v1` | Gateway de modelos |
| `SHOPLAB_ML_LLM_MODEL` | `judge` | Modelo lógico del gateway |

Los comandos de preparación (`candidates`, `paraphrase`, `annotate`) no se ejecutan en CI. Especificación, plan y decisiones en
[`specs/LAB-117-intent-labeled-dataset/`](../specs/LAB-117-intent-labeled-dataset/); el paquete es `src/intent_dataset/`.

## Clasificador de intención con scikit-learn (LAB-118)

Clasificador supervisado clásico de 5 clases (`politica`, `producto`, `pedido`, `escalado`, `charla`) en español y portugués
entrenado sobre `ml/data/intents/intents.jsonl`. El paquete reside en [`src/intent/`](src/intent/) y los wrappers de conveniencia en [`intent/`](intent/).

### Protocolo de evaluación y resultados en `test`

Bajo validación cruzada agrupada (`StratifiedGroupKFold`, 5 pliegues) sobre `train` (481 ejemplos) y selección sobre `val` (102 ejemplos),
se comparan 4 variantes con la semilla fija `118`:

| Variante | Val F1 macro | Val Accuracy | Val Brier | Latencia CPU | Estado |
|---|---|---|---|---|---|
| `dummy_majority` | 0.0886 | 0.2843 | 0.7861 | 0.01 ms/msg | Baseline trivial |
| **`tfidf_logreg`** | **0.8447** | **0.8333** | **0.3567** | **0.13 ms/msg** | **Ganadora (seleccionada)** |
| `tfidf_linearsvc` | 0.8246 | 0.8137 | 0.2548 | 0.17 ms/msg | Calibrada Sigmoid |
| `embedding_logreg` | 0.5859 | 0.5784 | 0.5601 | 0.08 ms/msg | Caché densa local |

El pipeline seleccionado (`tfidf_logreg`: `TextPreprocessor` + `FeatureUnion` [palabras (1,2) + caracteres char_wb (3,5)] + `LogisticRegression`)
se reajusta sobre `train + val` (583 ejemplos) y se evalúa **una única vez** sobre la partición aislada `test` (102 ejemplos):

- **F1 macro en `test`**: **`0.8788`** (supera con holgura el objetivo de calidad **`≥ 0.85`**).
- **Exactitud en `test`**: **`0.8725`** (frente al baseline mayoritario de `0.2843`).
- **Latencia media en CPU**: **`0.1711 ms/mensaje`** (muy por debajo del límite de 15 ms).
- **Desglose lingüístico**: `es` F1 macro = `0.9158`, `pt` F1 macro = `0.8349`.
- **Calibración**: Brier = `0.3132`, Log-Loss = `0.6573`, ECE = `0.2967`.

### Comandos de ejecución

```bash
cd ml

# 1. Entrenar y comparar variantes (genera intent_classifier.joblib y train_metadata.json)
uv run python -m intent.train --seed 118

# 2. Evaluar sobre test (verifica objetivo F1 >= 0.85 y genera eval_report.json)
uv run python -m intent.evaluate --min-f1-macro 0.85

# 3. Generar / verificar caché de embeddings
uv run python -m intent embed-cache --mode deterministic-projection
```

Equivalentes directos mediante scripts:
- `uv run python intent/train.py`
- `uv run python intent/evaluate.py`

### Artefactos generados (`artifacts/intent/`)

- [`artifacts/intent/intent_classifier.joblib`](artifacts/intent/intent_classifier.joblib): Artefacto único `Pipeline` de scikit-learn con `predict()` y `predict_proba()` calibrado sobre `CANONICAL_LABELS`.
- [`artifacts/intent/train_metadata.json`](artifacts/intent/train_metadata.json): Registro de trazabilidad con tabla comparativa de las 4 variantes, mejores hiperparámetros y top features por clase.
- [`artifacts/intent/eval_report.json`](artifacts/intent/eval_report.json): Informe completo de evaluación en test, matriz de confusión 5×5, calibración, tramos de confianza y desglose por idioma.
- [`data/intents/embeddings_cache.npz`](data/intents/embeddings_cache.npz): Caché de 685 vectores densos para ejecución determinista en CPU y CI sin contenedores externos.
