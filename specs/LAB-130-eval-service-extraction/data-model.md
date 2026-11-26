# Data Model: LAB-130

El modelo no cambia: se migra el esquema `eval` tal cual a la base `eval`. Esta tabla fija qué se conserva y qué se
comprueba.

## Entidades persistidas (esquema `eval`)

| Tabla | Clave | Contenido | Relaciones |
|---|---|---|---|
| `eval_run` | `id bigint GENERATED ALWAYS AS IDENTITY` (+ identificador de ejecución de texto, único) | Tipo, origen, `started_at`, `prompts` (jsonb array), `judge`, `evaluator`, `retrieval_config` (jsonb objeto), métricas globales | 1 — N `eval_result` |
| `eval_case` | `id bigint GENERATED ALWAYS AS IDENTITY` | Identidad del caso (`G###`), categoría, revisión (hash); **sin texto de pregunta ni respuesta** | 1 — N `eval_result` |
| `eval_result` | `id bigint GENERATED ALWAYS AS IDENTITY` | `run_ref`, `case_ref`, `scores` (jsonb objeto con valores 0..1) | FK a `eval_run(id)` y `eval_case(id)` |
| `flyway_schema_history` | — | V1 `baseline`, V2 `evaluation_history` | — |

Restricciones que deben sobrevivir: `jsonb_typeof` de `prompts`/`retrieval_config`/`judge`/`evaluator`/`scores`,
rango 0..1 de las puntuaciones, índices `eval_run_started_at_idx` y `eval_result_case_ref_idx`.

## Reglas de validación (sin cambios, en `EvalConstants`)

- `runId`: `^[A-Za-z0-9._-]{1,32}$`; `caseId`: `^G[0-9]{3}$`; commit: 40 hex; `sha256`: 64 hex.
- Historial: `limit` 1..500 (por defecto 100); comparación con `drop` por defecto `0.05`.
- Guardado: sin token o token incorrecto → `SLHD_EVAL_00001` (403); ejecución repetida → `00003` (409).

## Transiciones y reglas de estado

No hay máquina de estados: una ejecución es inmutable una vez guardada (se rechaza el mismo identificador).

## Datos que NO se crean ni migran

- Texto de preguntas, respuestas o contexto (ADR-0028).
- Configuración del asistente: se lee de `chat-service` en cada petición de `GET /api/eval/settings`; no se persiste en
  el servicio (aunque cada ejecución guarda su propia copia dentro de `eval_run`).

## Comprobaciones de la migración

| Comprobación | Criterio |
|---|---|
| Recuentos | `count(*)` igual en origen y destino para las tres tablas y el historial de Flyway |
| Contenido | `md5(string_agg(row::text, '' ORDER BY id))` por tabla igual |
| Secuencias | `last_value >= max(id)` en las tres |
| Origen | el esquema `eval` de `shoplab` queda sin cambios (mismos MD5 antes y después) |
