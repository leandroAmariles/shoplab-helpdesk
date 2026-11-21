# Data Model: Persistencia de las ejecuciones de evaluación (LAB-31)

Esquema `eval`, dueño: módulo `eval` del backend. Migraciones en `backend/src/main/resources/db/migration/eval/`:
`V1__baseline.sql` (solo comentarios, como el resto de módulos) y `V2__evaluation_history.sql` (las tres tablas). Ninguna
clave foránea sale del esquema (ADR-0015). Los tipos son orientativos; el detalle de las columnas es de implementación.

```text
eval_run 1 ──────< eval_result >────── 1 eval_case
 (ejecución)        (resultado)          (revisión de un caso)
```

## `eval_run` — una ejecución

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | bigint identidad, PK | |
| `run_id` | text, **UNIQUE** | `^[A-Za-z0-9._-]{1,32}$` (la regla que ya usa la herramienta) |
| `kind` | text | `RETRIEVAL` o `QUALITY` |
| `started_at`, `finished_at` | timestamptz | `finished_at >= started_at` |
| `recorded_at` | timestamptz | `now()`; cuándo entró en la base |
| `origin` | text | etiqueta corta (`local`, `dev`, `ci`); sin usuario ni máquina |
| `commit_sha` | text, nulo | 40 hex o nulo |
| `dataset_hash` | text | SHA-256 (64 hex) de los bytes de `golden.jsonl` |
| `cases_measured`, `cases_excluded` | int | `>= 0` |
| `assistant_model` | text, nulo | nulo si no se indicó (FR-008) |
| `embedding_model` | text | el que devolvió la búsqueda |
| `prompts` | jsonb array | `[{"name","version","source","pinned"}]`; `[]` en `RETRIEVAL` (no usa prompts) |
| `judge` | jsonb objeto, nulo | solo `QUALITY`: `model`, `underlying_model`, `temperature`, `seed`, `max_input_chars` |
| `evaluator` | jsonb objeto, nulo | solo `QUALITY`: `library`, `library_version`, `judgment_instructions_version` |
| `retrieval_config` | jsonb objeto | `top_k`, `similarity_threshold`, `max_context_characters` (del asistente) y, en `RETRIEVAL`, `depth` y `ks` |

- `prompts[].version` es un entero; es `null` cuando `source = DEFAULT` (texto empaquetado, sin Langfuse). `pinned = true`
  cuando la versión se fijó por petición.
- Restricciones: `kind` ∈ valores válidos; `jsonb_typeof(prompts) = 'array'`; `judge` y `evaluator` presentes si y solo si
  `kind = QUALITY`.
- **Una fila existe solo si la ejecución está entera** (se inserta en la misma transacción que sus resultados).

## `eval_case` — una revisión de un caso del dataset

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | bigint identidad, PK | |
| `case_id` | text | `^G[0-9]{3}$`, estable entre revisiones |
| `content_hash` | text | SHA-256 (64 hex) de los cinco campos, calculado por el backend |
| `category` | text | una de las ocho del dataset |
| `question` | text | 10–300 caracteres (2–300 en conversación) |
| `reference_answer` | text | 20–800 caracteres |
| `expected_documents` | text[] | de 0 a 2 ids de documento |
| `created_at` | timestamptz | primera vez que se midió esta revisión |

- **`UNIQUE (case_id, content_hash)`**: la misma revisión se reutiliza entre ejecuciones; cambiar cualquiera de los cinco
  campos crea otra fila y las ejecuciones anteriores siguen apuntando a la suya.
- Solo datos del dataset golden (sintético, Constitución VI).

## `eval_result` — lo que un caso dio en una ejecución

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | bigint identidad, PK | |
| `run_ref` | bigint, FK → `eval_run(id)` | |
| `case_ref` | bigint, FK → `eval_case(id)` | |
| `case_kind` | text | `answered` o `unanswered` (los de conversación no se guardan, FR-006 de LAB-29) |
| `scores` | jsonb objeto | `{ "<métrica>": número 0–1 \| null }`; ver abajo |
| `retrieved_documents` | text[] | ids de documento en el orden entregado; en calidad conserva repeticiones, como el CSV |
| `abstained` | boolean, nulo | solo `QUALITY` |
| `trace_id` | text, nulo | traza de Langfuse; nulo en recuperación |

- **`UNIQUE (run_ref, case_ref)`**: un caso aparece una vez por ejecución.
- **Métricas** (nombres estables): `faithfulness`, `answer_relevancy`, `context_precision` en `QUALITY`;
  `hit_at_<k>`, `recall_at_<k>` por cada k pedido y `reciprocal_rank` en `RETRIEVAL`. Un valor numérico va entre 0 y 1
  (restricción `CHECK`); `null` o clave ausente = no aplica o no se calculó **por definición**; `0` es una puntuación.
- **No se guarda** el texto de la respuesta ni el de los fragmentos (FR-009): el `trace_id` apunta a Langfuse.

## Reglas de validación de una ejecución (antes de guardar)

Las comprueba el caso de uso y devuelven 422 con la causa, sin tocar la base:

1. Todos los campos obligatorios presentes y con el formato de arriba; `kind` conocido; `finished_at >= started_at`.
2. Sin `case_id` repetido en la misma ejecución; `cases_measured` coincide con el número de resultados.
3. Cada resultado trae exactamente las métricas que su tipo de ejecución y su tipo de caso permiten (p. ej. un caso
   `unanswered` en `QUALITY` solo puede traer `faithfulness`); ningún valor fuera de 0–1.
4. `judge` y `evaluator` solo en `QUALITY`; ningún campo de texto de respuesta o fragmento (el contrato no los define y se
   rechazan las claves desconocidas).

## Transiciones

No hay estados: una ejecución no se edita ni se borra (fuera de alcance). Una revisión de caso tampoco. El historial solo
crece.

## Consultas que el diseño debe poder responder (se documentan en `eval/README.md`)

| Pregunta | Idea |
|---|---|
| Listar ejecuciones por fecha y tipo | `eval_run ORDER BY started_at DESC` |
| Parámetros y resumen de una ejecución | `eval_run` + media de `scores->>'<métrica>'` agrupada por `eval_case.category` |
| Evolución de una métrica | media por ejecución ordenada por `started_at` |
| Comparar dos ejecuciones caso a caso | `eval_result` de ambas unido por `case_id`; marca `no comparable` si `case_ref` difiere |
| Ejecuciones con un prompt, versión o modelo | contención `prompts @> '[…]'`, `assistant_model = …` |
| Casos que empeoraron | diferencia de `faithfulness` por caso entre dos ejecuciones comparables |
