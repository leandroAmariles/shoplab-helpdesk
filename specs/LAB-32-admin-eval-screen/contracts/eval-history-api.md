# Contrato: API de lectura del historial de evaluación

Tres endpoints de **solo lectura** del módulo `eval`, pensados para la pantalla `admin-eval`. No son los de guardado de LAB-31
(`/api/eval/runs`, con token): estos **no llevan ni exigen token** y el backend no aceptaría escritura por ellos. Los errores son
los tipados del proyecto, con el cuerpo estándar (`code`, `message`) y el campo opcional `detail` (solo en 422, con el
parámetro afectado y nunca con texto de respuestas ni de fragmentos, como LAB-31). Los nombres de los campos van en
`snake_case`, como los de LAB-31.

**Garantías comunes**

- Solo se ven ejecuciones **completas** (una ejecución se guarda entera o no existe, LAB-31).
- Un valor vacío es `null`, nunca `0`.
- Ningún campo contiene el texto de una respuesta del asistente ni el de un fragmento recuperado, ni claves, tokens ni
  direcciones internas. La lista de campos de abajo es cerrada.
- Con la persistencia de guardado apagada (sin token configurado) la lectura **sigue funcionando** y devuelve una lista vacía.

## `GET /api/eval/history/runs`

Las ejecuciones de un tipo, con sus medias del conjunto completo, para la gráfica y la lista.

| Parámetro | Obligatorio | Reglas |
|---|---|---|
| `kind` | sí | `QUALITY` o `RETRIEVAL` |
| `origin` | no | formato de origen de LAB-31; filtra la lista, **no** las facetas |
| `limit` | no | 1–500, por defecto 100: las más recientes |

```json
{
  "kind": "QUALITY",
  "shown": 3,
  "total": 3,
  "total_all_origins": 5,
  "origins": [ { "origin": "dev", "count": 3 }, { "origin": "local", "count": 2 } ],
  "runs": [
    {
      "run_id": "t031-1",
      "kind": "QUALITY",
      "started_at": "2026-11-21T14:02:11Z",
      "finished_at": "2026-11-21T14:11:03Z",
      "origin": "dev",
      "commit_sha": "5fae385e1c0f9a8b7d6c5e4f3a2b1c0d9e8f7a6b",
      "assistant_model": "chat",
      "cases_measured": 96,
      "prompts": [ { "name": "chat-system", "version": 3, "source": "LANGFUSE", "pinned": false } ],
      "metrics": {
        "faithfulness":      { "mean": 0.7134, "n": 96 },
        "answer_relevancy":  { "mean": 0.8120, "n": 71 },
        "context_precision": { "mean": null,   "n": 0 }
      }
    }
  ]
}
```

- `runs` va en **orden cronológico ascendente** (la más reciente al final; desempate por `run_id`) y contiene las `limit` más
  recientes que cumplen `kind` y `origin`.
- `shown = runs.length`; `total` = las que cumplen los filtros; `total_all_origins` = las del tipo sin `origin`. «Ocultas por el
  filtro» = `total_all_origins − total`; «sin mostrar por el rango» = `total − shown`.
- `origins` cuenta las ejecuciones del tipo por origen, ordenadas por `count` descendente.
- Una métrica que la ejecución no tiene **no figura** en `metrics`; una que no se pudo calcular figura con `mean = null, n = 0`.
- **200**; **422** `SLHD_EVAL_00002` (`kind` ausente o desconocido, `limit` fuera de rango, `origin` con formato inválido);
  **503** `SLHD_EVAL_00005` si la base no responde.

## `GET /api/eval/history/runs/{runId}`

Una ejecución con todo lo necesario para el detalle: parámetros, resumen y resultados con el caso tal como se midió.

```json
{
  "run": {
    "run_id": "t031-1", "kind": "QUALITY",
    "started_at": "2026-11-21T14:02:11Z", "finished_at": "2026-11-21T14:11:03Z", "recorded_at": "2026-11-21T14:11:04Z",
    "origin": "dev", "commit_sha": null, "dataset_hash": "9f2c…64 hex…",
    "cases_measured": 96, "cases_excluded": 24,
    "assistant_model": null, "embedding_model": "nomic-embed-text",
    "prompts": [ { "name": "chat-system", "version": 3, "source": "LANGFUSE", "pinned": true } ],
    "judge": { "model": "judge", "underlying_model": "qwen3:8b", "temperature": 0.0, "seed": 7, "max_input_chars": 6000 },
    "evaluator": { "library": "ragas", "library_version": "0.3.5", "judgment_instructions_version": "2" },
    "retrieval_config": { "top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000 }
  },
  "summary": [
    { "scope": "all",     "cases": 96, "metrics": { "faithfulness": { "mean": 0.7134, "n": 96 } } },
    { "scope": "returns", "cases": 18, "metrics": { "faithfulness": { "mean": 0.6900, "n": 18 } } }
  ],
  "results": [
    {
      "case_id": "G044",
      "revision": "c1a5…64 hex…",
      "category": "returns",
      "question": "¿Cuántos días tengo para devolver un producto?",
      "reference_answer": "Tienes 30 días naturales desde la entrega…",
      "expected_documents": [ "politica-devoluciones" ],
      "case_kind": "answered",
      "scores": { "faithfulness": 0.2, "answer_relevancy": null, "context_precision": 0.5 },
      "retrieved_documents": [ "politica-envios", "politica-devoluciones" ],
      "abstained": false,
      "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
      "trace_url": "http://localhost:3001/trace/4bf92f3577b34da6a3ce929d0e0e4736"
    }
  ]
}
```

- `summary` incluye `all` y una entrada por categoría con casos. `cases − n` de una métrica es el número de casos «sin
  puntuar» en ella (FR-008).
- `commit_sha` y `assistant_model` pueden ser `null`: se muestran como «desconocido».
- `judge` y `evaluator` son `null` en `RETRIEVAL`; `retrieval_config` lleva además `depth` y `ks` en `RETRIEVAL`.
- `trace_url` solo aparece con plantilla configurada y `trace_id` presente; `trace_id` es `null` en recuperación.
- **200**; **404** `SLHD_EVAL_00004` si no existe; **422** `SLHD_EVAL_00002` si el `runId` no cumple `^[A-Za-z0-9._-]{1,32}$`;
  **503** `SLHD_EVAL_00005`.

## `GET /api/eval/history/compare`

La comparación de dos ejecuciones **del mismo tipo** para una métrica.

| Parámetro | Obligatorio | Reglas |
|---|---|---|
| `base` | sí | `run_id` de la ejecución de referencia |
| `target` | sí | `run_id` de la ejecución a comparar; distinto de `base` |
| `metric` | sí | nombre de una métrica que tenga alguna de las dos |
| `drop` | no | 0–1, por defecto `0.05`: lo que tiene que cambiar una métrica para contar como empeoramiento o mejora |

```json
{
  "base":   { "run_id": "t031-1", "kind": "QUALITY", "started_at": "2026-11-21T14:02:11Z", "origin": "dev" },
  "target": { "run_id": "t031-2", "kind": "QUALITY", "started_at": "2026-11-22T10:00:00Z", "origin": "dev" },
  "metric": "faithfulness",
  "drop": 0.05,
  "metrics": [
    { "metric": "faithfulness", "base": { "mean": 0.7134, "n": 96 }, "target": { "mean": 0.6800, "n": 96 }, "delta": -0.0334 }
  ],
  "parameters": [
    { "field": "prompts.chat-system.version", "base": "3", "target": "4", "affects_comparability": false },
    { "field": "judge.temperature", "base": "0.0", "target": "0.2", "affects_comparability": true }
  ],
  "warnings": [ "judge.temperature" ],
  "counts": { "worse": 4, "better": 1, "same": 85, "no_value": 3, "not_comparable": 1, "only_base": 1, "only_target": 1 },
  "cases": [
    { "case_id": "G044", "category": "returns", "question": "¿Cuántos días tengo para devolver un producto?",
      "status": "WORSE", "base_value": 0.8, "target_value": 0.2, "delta": -0.6 }
  ]
}
```

- `status` ∈ `WORSE`, `BETTER`, `SAME`, `NO_VALUE`, `NOT_COMPARABLE`, `ONLY_BASE`, `ONLY_TARGET` (reglas en
  [research.md](../research.md) R9). `delta = target_value − base_value`, solo en `WORSE`, `BETTER` y `SAME`; los valores de
  `NOT_COMPARABLE` y de `ONLY_*` se omiten (no se afirma nada de ellos).
- `cases` contiene la unión de los `case_id` de las dos ejecuciones, ordenada por `delta` ascendente (los peores primero) y
  después por `case_id`; los casos sin delta van al final.
- `metrics` lista todas las métricas que tiene alguna de las dos ejecuciones; `delta` es `null` si alguna no tiene media.
- La lista de `WORSE` es **idéntica** a la de `eval/queries/07-regressions.sql` con los mismos `run_a = base`, `run_b = target`,
  `metric` y `drop` (FR-019).
- **200**; **404** `SLHD_EVAL_00004` si alguna no existe; **422** `SLHD_EVAL_00002` (parámetros inválidos, `base = target`,
  métrica que ninguna tiene); **422** `SLHD_EVAL_00006` si las ejecuciones son de tipos distintos; **503** `SLHD_EVAL_00005`.

## Errores

| Código | HTTP | Cuándo |
|---|---|---|
| `SLHD_EVAL_00002` | 422 | parámetro inválido (con `detail`) — ya existe (LAB-31) |
| `SLHD_EVAL_00004` | 404 | la ejecución no existe — ya existe (LAB-31) |
| `SLHD_EVAL_00005` | 503 | base de datos o esquema no disponible — ya existe (LAB-31) |
| `SLHD_EVAL_00006` | 422 | las dos ejecuciones no son del mismo tipo — **nuevo** |

`SLHD_EVAL_00001` (403, token) **no** aparece aquí: la lectura no lo usa.

## Configuración

| Propiedad | Variable | Por defecto | Qué hace |
|---|---|---|---|
| `shoplab.eval.history.trace-url-template` | `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE` | `http://localhost:3001/trace/{traceId}` | Plantilla del enlace a la traza de Langfuse. Vacía = sin `trace_url`. Debe ser `http(s)` y contener `{traceId}` una vez; si no, el arranque falla |
