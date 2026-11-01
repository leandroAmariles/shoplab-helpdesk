# Data Model: Respuestas aumentadas con los documentos (RAG)

Esta historia **no persiste datos ni cambia el esquema**. Los tipos siguientes viven en memoria durante una petición.

## `KnowledgeChunk` (retrieval/api, público)

Fragmento publicado por `retrieval` a otros módulos. No expone `SearchResult` ni identificadores internos.

| Campo | Tipo | Notas |
|---|---|---|
| `source` | `String` | nombre del archivo del documento |
| `section` | `String` | ruta de encabezados; vacía si no hay |
| `position` | `int` | posición del chunk en el documento |
| `text` | `String` | texto del chunk |
| `score` | `double` | similitud de coseno con la consulta (mayor = más cercano) |

Origen: `SearchResult` de LAB-21. Se omiten `chunkId` y `documentId` (no los necesita `chat`); las citas de la historia
siguiente usan `source`, `section` y `position`.

## `ContextFragment` (chat/domain/model)

Fragmento que `chat` entrega al modelo. Se construye en `KnowledgeSearchAdapter` desde `KnowledgeChunk`.

| Campo | Tipo | Validación |
|---|---|---|
| `source` | `String` | no nulo |
| `section` | `String` | no nulo (vacío permitido) |
| `position` | `int` | ≥ 0 |
| `text` | `String` | no vacío |
| `score` | `double` | — |

## `ContextOutcome` (chat/domain/model/enums)

`WITH_CONTEXT`, `NO_CONTEXT`, `FAILED`. Valor de la etiqueta `outcome` de la métrica de recuperación (`tagValue()` en
minúsculas, como `ChatOutcome`).

## `RagProperties` (chat/config)

| Campo | Defecto | Validación |
|---|---|---|
| `topK` | 4 | 1 ≤ valor ≤ 20 |
| `similarityThreshold` | 0.0 | 0.0 ≤ valor ≤ 1.0; 0 = desactivado |
| `maxContextCharacters` | 8000 | ≥ 500 |
| `contextTemplate` | plantilla en español con `{query}` y `{context}` | contiene ambos marcadores |
| `emptyContextTemplate` | plantilla en español | no vacía |

`ChatProperties.systemPrompt` pasa a tener por defecto el prompt fundamentado (ver contrato).

## Transformación de una petición

```text
mensaje ─validar─▶ KnowledgeContextPort.retrieve(mensaje, topK) ─▶ List<ContextFragment>
        ─▶ ContextSelector.select(…): umbral → duplicados → presupuesto ─▶ List<ContextFragment> (≤ topK)
        ─▶ ChatModelPort.streamAnswer(mensaje, fragmentos) ─▶ Flux<String> ─▶ eventos SSE (sin cambios)
```

Estados de la recuperación: `WITH_CONTEXT` (≥ 1 fragmento tras la selección), `NO_CONTEXT` (0 fragmentos; el modelo recibe
la plantilla de contexto vacío), `FAILED` (error tipado antes del primer token). No hay transiciones persistentes.
