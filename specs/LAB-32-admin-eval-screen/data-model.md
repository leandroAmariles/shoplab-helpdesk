# Data Model: Pantalla de evolución de métricas (admin-eval) — LAB-32

**No hay tablas ni migraciones nuevas.** El esquema `eval` (`eval_run`, `eval_case`, `eval_result`) es el de LAB-31 (ver
[`specs/LAB-31-eval-run-persistence/data-model.md`](../LAB-31-eval-run-persistence/data-model.md)) y esta historia solo lo
**lee**. Este documento define los **modelos de lectura** que el backend devuelve y el **estado** de la pantalla. Los tipos son
orientativos; la forma exacta de la API está en [contracts/eval-history-api.md](contracts/eval-history-api.md).

```text
eval_run 1 ───< eval_result >─── 1 eval_case          (esquema de LAB-31, solo lectura)
   │                  │                │
   ▼                  ▼                ▼
RunListItem       StoredResult     (contenido del caso tal como se midió)
ScopeSummary           └──────────┐
   └──────── StoredRun ───────────┘
                │
                ▼  (dos ejecuciones del mismo tipo)
          RunComparison ── CaseComparison / ParameterDifference
```

## Modelos de lectura (backend, módulo `eval`)

### `MetricStat` — el valor de una métrica en un ámbito

| Campo | Tipo | Reglas |
|---|---|---|
| `mean` | número o **nulo** | Media de los valores numéricos de la métrica en el ámbito, **ignorando los vacíos**. Nulo cuando ningún caso tiene valor (hueco): nunca 0 |
| `n` | entero ≥ 0 | Casos con valor que entraron en la media. `0` ⇔ `mean` nulo |

Es la misma definición que `eval/queries/03-run-summary.sql` y que la fila `all` del CSV (FR-007).

### `RunListItem` — una ejecución en la lista y en la gráfica

`run_id`, `kind` (`RETRIEVAL` | `QUALITY`), `started_at`, `finished_at`, `origin`, `commit_sha` (nulo), `assistant_model`
(nulo), `prompts[]` (`name`, `version` nulo si `DEFAULT`, `source`, `pinned`), `cases_measured` y `metrics`: mapa
`nombre de la métrica → MetricStat` del **conjunto completo**. Una métrica que la ejecución no tiene **no figura** en el mapa
(la serie la trata como hueco, igual que una con `mean = null`).

### `RunListing` — la respuesta de la lista

`kind`, `runs[]` (las `limit` más recientes del tipo y filtro, en orden cronológico ascendente), `shown` (= `runs.length`),
`total` (las que cumplen kind + origin), `total_all_origins` (las del tipo sin filtrar por origen) y `origins[]`
(`origin`, `count`, sobre el tipo). Con ellos la pantalla dice «mostrando N de M» y «K ocultas por el filtro» (FR-006, FR-023).

### `StoredRun` — una ejecución completa

| Parte | Contenido |
|---|---|
| `run` | Todos los parámetros de `eval_run`: `run_id`, `kind`, `started_at`, `finished_at`, `recorded_at`, `origin`, `commit_sha`, `dataset_hash`, `cases_measured`, `cases_excluded`, `assistant_model`, `embedding_model`, `prompts[]`, `judge` (solo calidad), `evaluator` (solo calidad), `retrieval_config` (`top_k`, `similarity_threshold`, `max_context_characters` y, en recuperación, `depth` y `ks`) |
| `summary` | `ScopeSummary[]`: `scope` (`all` o una categoría), `cases` (casos del ámbito) y `metrics` (`nombre → MetricStat`) |
| `results` | `StoredResult[]`, uno por caso medido, ordenados por `case_id` |

Un dato desconocido (commit, modelo del asistente) va como **nulo** y la pantalla escribe «desconocido» (FR-008).

### `StoredResult` — lo que dio un caso, con el caso tal como se midió

| Campo | Tipo | Reglas |
|---|---|---|
| `case_id` | `^G[0-9]{3}$` | estable entre revisiones |
| `revision` | SHA-256 (64 hex) | el `content_hash` de la revisión; dos resultados son comparables si coincide |
| `category` | una de las seis medidas | las de conversación no se guardan (LAB-31) |
| `question`, `reference_answer`, `expected_documents[]` | texto del **dataset golden** (sintético) | el contenido que tenía el caso **cuando se midió**, no el actual (FR-014) |
| `case_kind` | `answered` \| `unanswered` | un `unanswered` solo trae `faithfulness` |
| `scores` | mapa `métrica → número 0–1 \| null` | `null` = no aplica o no se pudo calcular por definición; `0` es una puntuación |
| `retrieved_documents[]` | ids de documento en el orden entregado | en calidad conserva repeticiones, como el CSV |
| `abstained` | booleano o nulo | solo calidad |
| `trace_id`, `trace_url` | texto o nulo | `trace_url` solo si hay plantilla configurada (R6) |

**No existe ningún campo con el texto de la respuesta del asistente ni el de un fragmento** (FR-016): la lista de campos de
arriba es cerrada y una prueba la fija (SC-008).

### `RunComparison` — dos ejecuciones del mismo tipo

| Parte | Contenido |
|---|---|
| `base`, `target` | cabecera de cada ejecución (`run_id`, `kind`, `started_at`, `origin`) |
| `metric`, `drop` | la métrica por la que se clasifican los casos y la tolerancia (0–1; por defecto 0,05) |
| `metrics[]` | por cada métrica de las dos: `base` y `target` (`MetricStat`) y `delta` (`target − base`, nulo si alguna no tiene media) |
| `parameters[]` | `ParameterDifference`: `field` (p. ej. `prompts.chat-system.version`, `assistant_model`, `judge.temperature`, `retrieval_config.top_k`, `dataset_hash`), `base`, `target` y `affects_comparability` |
| `warnings[]` | los `field` con `affects_comparability = true`: juez, modelo del asistente, embeddings, recuperación o dataset (FR-018) |
| `cases[]` | `CaseComparison` por `case_id` de la unión (ver abajo) |
| `counts` | número de casos por estado |

`CaseComparison`: `case_id`, `category`, `question` (de la revisión de `target`, o de la de `base` si solo está en `base`),
`status` (`WORSE`, `BETTER`, `SAME`, `NO_VALUE`, `NOT_COMPARABLE`, `ONLY_BASE`, `ONLY_TARGET`; reglas en
[research.md](research.md) R9), `base_value`, `target_value` y `delta` (solo con `WORSE`, `BETTER`, `SAME`).

`affects_comparability` es **verdadero** para: juez (y sus ajustes), biblioteca de evaluación e instrucciones de juicio,
`assistant_model`, `embedding_model`, `retrieval_config` y `dataset_hash`; es **falso** para las versiones de prompt, el commit
y el origen (lo que normalmente se quiere medir).

## Estado de la pantalla (frontend)

El estado vive en `EvalStore` (signals) y no se persiste. No son entidades de negocio; documentan qué recuerda la pantalla.

| Estado | Valores / reglas |
|---|---|
| `kind` | `QUALITY` (por defecto) o `RETRIEVAL`; en la dirección (`?tipo=`). Cambiarlo vacía las series, nunca las mezcla (FR-003) |
| `selectedMetrics` | de 1 a 5 nombres de métrica del tipo; por defecto `faithfulness`, o `reciprocal_rank` + `hit_at_<mayor k>` |
| `originFilter` | `null` (todos) o un origen de las facetas (FR-006) |
| `rangeLimit` | 100 por defecto; «ver más» sube a 250 y 500 (FR-023) |
| `listing` | último `RunListing` leído; se **conserva** mientras se recarga o si falla la recarga |
| `selectedRunId` | en la dirección (`?ejecucion=`); su `StoredRun` se carga al elegirla y se **cachea** por `run_id` (inmutable) |
| `failureCriterion` | calidad: `threshold` (0–1, paso 0,05, inicial 0,5); recuperación: `k` (inicial = mayor k de la ejecución) |
| `failureFilters` | categoría (o todas), métrica (o cualquiera), orden (peor primero por defecto) |
| `comparison` | `{ baseRunId, targetRunId, metric, drop }` y su `RunComparison`; solo entre ejecuciones del mismo tipo |
| `loading`, `loadError` | por petición (lista, detalle, comparación); el error muestra «Reintentar» sin vaciar lo mostrado |

### Reglas del criterio de fallo (función pura, cliente)

- **Calidad**: un resultado falla si existe una métrica `m` con valor (`≠ null`) y `m < umbral`. Si ninguna métrica tiene valor,
  no falla. Se reporta con **qué métricas** bajaron del umbral (para ordenar «peor primero» por la mínima).
- **Recuperación**: un resultado falla si `expected_documents` no está vacío y `hit_at_<k> = 0` para el `k` elegido. Sin
  `expected_documents`, o con `hit_at_<k>` nulo, no falla.
- Siempre se informa «F casos fallan de M medidos» (FR-012); `M` es `cases_measured` de la ejecución.

## Transiciones

No hay estados de negocio ni transiciones: el historial es inmutable (LAB-31) y la pantalla es de solo lectura. El único
«estado» que cambia con el tiempo es el de la **vista** (cargando → con datos | vacío | error), descrito en
[contracts/admin-eval-screen.md](contracts/admin-eval-screen.md).

## Validaciones de las peticiones de lectura

Las comprueba el caso de uso y devuelven 422 con la causa (campo `detail`), sin tocar la base:

1. `kind` presente y conocido; `limit` entre 1 y 500 (por defecto 100); `origin`, si viene, con el formato de origen de LAB-31.
2. `runId`, `base` y `target` con el formato de `run_id` de LAB-31; `base ≠ target`.
3. `metric` con el formato de nombre de métrica (`^[a-z][a-z0-9_]{0,63}$`) — se valida contra las métricas de las ejecuciones
   comparadas: una métrica que ninguna tiene devuelve 422 en vez de una comparación vacía.
4. `drop` numérico entre 0 y 1.
