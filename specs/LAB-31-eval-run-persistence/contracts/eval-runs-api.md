# Contrato: API de guardado de ejecuciones de evaluación

Tres endpoints del módulo `eval`, pensados **solo para la herramienta de `eval/`**. No son una API de lectura del historial
(eso es LAB-32). Los errores son los tipados del proyecto, mapeados solo por `GlobalExceptionHandler`, con el cuerpo de error
estándar (`code`, `message`). Hoy el mensaje es fijo por código, así que el **422** añade un campo opcional **`detail`**
(solo cuando la excepción implementa `PublicDetail`, del módulo abierto `shared`) con el campo o el caso afectado, p. ej.
`results[12].scores.faithfulness: debe estar entre 0 y 1` o `case G044: …`. El `detail` nunca lleva texto de respuestas ni de
fragmentos. El resto de errores del proyecto no cambia: sin `detail`, el JSON es idéntico al de hoy.

## Autenticación

Todas las peticiones llevan `X-Eval-Token: <valor de shoplab.eval.persistence.token>`. Sin token configurado en el backend,
o con uno distinto, responde **403** `SLHD_EVAL_00001` y no toca la base. El token no se escribe en registros, errores ni
respuestas.

## `GET /api/eval/settings`

La instantánea de lo que el asistente usa **ahora** (la publica el módulo `chat` en su `api`).

```json
{
  "retrieval": { "top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000 },
  "prompts": [
    { "name": "chat-system", "version": 3, "source": "LANGFUSE" },
    { "name": "chat-small-talk", "version": 1, "source": "LANGFUSE" },
    { "name": "chat-context", "version": 2, "source": "LANGFUSE" },
    { "name": "chat-empty-context", "version": 1, "source": "LANGFUSE" }
  ]
}
```

- `source = DEFAULT` y `version = null` cuando Langfuse está apagado y se sirve el texto empaquetado.
- Los nombres son los que ya usa LAB-85; esta historia no los cambia.
- **200**; **403** sin token válido.

## `GET /api/eval/runs/{runId}`

Existencia de una ejecución. Sirve a la herramienta para comprobar, **antes de medir**, que el token vale, que la base y el
esquema responden y que el identificador está libre.

- **200** `{ "run_id": "…", "kind": "QUALITY", "started_at": "…", "recorded_at": "…" }` si existe (la herramienta lo trata como
  «identificador ya guardado»).
- **404** `SLHD_EVAL_00004` si no existe (la herramienta lo trata como «libre»).
- **503** `SLHD_EVAL_00005` si la base no está disponible; **403** sin token válido; **422** `SLHD_EVAL_00002` si el `runId`
  no cumple `^[A-Za-z0-9._-]{1,32}$`.

## `POST /api/eval/runs`

Guarda una ejecución **entera** en una transacción. `Content-Type: application/json`. Una ejecución completa pesa del orden de 100 KB; el tope es el límite de decodificación que
ya tiene el backend (11 MB), no se añade otro.

```json
{
  "run_id": "t031-1",
  "kind": "QUALITY",
  "started_at": "2026-11-21T14:02:11Z",
  "finished_at": "2026-11-21T14:11:03Z",
  "origin": "local",
  "commit_sha": "5fae385e1c0f9a8b7d6c5e4f3a2b1c0d9e8f7a6b",
  "dataset_hash": "9f2c…64 hex…",
  "cases_measured": 96,
  "cases_excluded": 24,
  "assistant_model": "llama3.2:3b",
  "embedding_model": "bge-m3",
  "prompts": [
    { "name": "chat-system", "version": 3, "source": "LANGFUSE", "pinned": false },
    { "name": "chat-context", "version": 2, "source": "LANGFUSE", "pinned": true }
  ],
  "judge": { "model": "judge", "underlying_model": "ollama_chat/qwen2.5:7b", "temperature": 0.0, "seed": 7,
             "max_input_chars": 24000 },
  "evaluator": { "library": "ragas", "library_version": "0.4.4", "judgment_instructions_version": "1" },
  "retrieval_config": { "top_k": 4, "similarity_threshold": 0.6, "max_context_characters": 8000 },
  "results": [
    {
      "case": {
        "id": "G001", "category": "payments",
        "question": "Mi boleto venció sin que lo pagara, ¿qué puedo hacer?",
        "reference_answer": "Si el boleto vence sin pago, el pedido pasa a cancelado. …",
        "expected_documents": ["payments-03-boleto-and-vouchers"]
      },
      "case_kind": "answered",
      "scores": { "faithfulness": 1.0, "answer_relevancy": 0.6768, "context_precision": 1.0 },
      "retrieved_documents": ["payments-05-faq-payments", "payments-03-boleto-and-vouchers"],
      "abstained": false,
      "trace_id": "815a0c4399777df9b9abe61cdcadb9f9"
    },
    {
      "case": { "id": "G071", "category": "payments", "question": "…", "reference_answer": "…",
                "expected_documents": [] },
      "case_kind": "unanswered",
      "scores": { "faithfulness": null },
      "retrieved_documents": [],
      "abstained": true,
      "trace_id": null
    }
  ]
}
```

Para `kind = RETRIEVAL`: no lleva `judge`, `evaluator` ni `prompts`; `retrieval_config` añade `"depth": 50` y `"ks": [1, 3, 5]`;
`scores` lleva `hit_at_1`, `hit_at_3`, `hit_at_5`, `recall_at_1`, …, `reciprocal_rank`; `abstained` y `trace_id` son `null`.

**Reglas**: se rechazan las claves desconocidas (no hay dónde poner el texto de una respuesta ni de un fragmento). Los
campos de texto del caso son los del dataset golden. Las validaciones están en `data-model.md`.

### Respuestas

| Código | Cuándo |
|---|---|
| **201** | Guardada. Cuerpo: `{ "run_id": "t031-1", "kind": "QUALITY", "results": 96, "new_case_revisions": 2 }` |
| **400** `SLHD_GENE_00002` | El cuerpo no es JSON legible o trae una clave desconocida (se decodifica sin tolerarlas) |
| **403** `SLHD_EVAL_00001` | Guardado desactivado o token incorrecto |
| **409** `SLHD_EVAL_00003` | Ya existe una ejecución con ese `run_id` (no se sobrescribe ni se duplica) |
| **422** `SLHD_EVAL_00002` | La ejecución no cumple las reglas; el mensaje nombra el campo o el caso afectado |
| **503** `SLHD_EVAL_00005` | La base no está disponible; no se guardó nada |

En cualquier error no 2xx **no queda ninguna fila** de esa ejecución.

## Códigos de error nuevos (`ResponseCodeEnum`)

| Enum | Código | HTTP |
|---|---|---|
| `EVAL_PERSISTENCE_DISABLED` | `SLHD_EVAL_00001` | 403 |
| `EVAL_RUN_INVALID` | `SLHD_EVAL_00002` | 422 |
| `EVAL_RUN_ALREADY_EXISTS` | `SLHD_EVAL_00003` | 409 |
| `EVAL_RUN_NOT_FOUND` | `SLHD_EVAL_00004` | 404 |
| `EVAL_STORE_UNAVAILABLE` | `SLHD_EVAL_00005` | 503 |

## Propiedades nuevas (`application.yml`)

```yaml
shoplab:
  eval:
    persistence:
      # Token que la herramienta de eval/ envía en X-Eval-Token. Vacío = el guardado está apagado (403).
      token: ${SHOPLAB_EVAL_PERSISTENCE_TOKEN:}
```

## Contrato de la herramienta (`eval/`)

| Opción / variable | Efecto |
|---|---|
| `SHOPLAB_EVAL_TOKEN` | El mismo valor que el token del backend. Sin él y sin `--save`, no se guarda |
| `--save` | Pide guardar; sin token es error de uso (código 2) |
| `--no-save` | Nunca guarda, aunque haya token |
| `--run-id` | Ya existe en `quality`; se añade a `retrieval` con la misma validación |
| `SHOPLAB_EVAL_ORIGIN` | Etiqueta de origen (`local` por defecto) |

Códigos de salida: **0** medida y, si se pidió, guardada; **1** medición inválida, deriva de la configuración del asistente o
guardado fallido (los CSV se escriben igual en este último caso); **2** uso incorrecto. Es la misma convención de LAB-28/29.
