# Contrato interno: indexado, reindexado y búsqueda en `retrieval` (LAB-21)

No hay endpoint HTTP nuevo. Este documento fija los puertos del módulo (lo que consumirá LAB-22/LAB-25), los puertos de
salida, la configuración y los errores. Firmas en notación Java abreviada; los tipos son los de
[data-model.md](../data-model.md).

## Puertos de entrada (`application/port/in`)

| Puerto | Método | Comportamiento |
|---|---|---|
| `IndexDocumentsServicePort` | `Mono<IndexingSummary> indexPending(boolean retryFailed)` | Calcula y guarda los vectores de los documentos `PENDING`; con `retryFailed = true` incluye también los `ERROR` (reintento sin reingerir). Idempotente: un documento `INDEXED` no se recalcula. Un fallo en un documento lo deja `ERROR` y continúa con los demás. Si ya hay una ejecución en curso devuelve `skipped = true`. |
| `ReindexDocumentsServicePort` | `Mono<IndexingSummary> reindex(boolean force)` | `force = false`: documentos con algún chunk de un `embedding_model` distinto del configurado o sin vector. `force = true`: todos. No llama a `ingest` ni modifica texto, posición ni metadatos. |
| `SearchChunksServicePort` | `Mono<List<SearchResult>> search(String query, Integer limit)` | Ver «Búsqueda». `limit` nulo usa el valor configurado; se acota a [1, 50]. |

Todos devuelven errores tipados (`HelpdeskException`); ninguno construye `ResponseEntity`.

## Puertos de salida (`application/port/out`)

| Puerto | Métodos | Notas |
|---|---|---|
| `EmbeddingPort` | `Mono<List<float[]>> embed(List<String> texts)`; `String modelName()` | Un vector de 1 024 valores por texto y en el mismo orden; el adaptador valida la dimensión y traduce errores. |
| `EmbeddingStorePort` | `Flux<PendingDocument> findDocuments(IndexScope scope, String model)`; `Mono<Boolean> saveEmbeddings(PendingDocument document, List<ChunkEmbedding> vectors, String model)`; `Mono<Void> markFailed(PendingDocument document, String reason)`; `Mono<List<SearchResult>> search(float[] query, String model, int limit)`; `Mono<Boolean> hasChunksFromOtherModels(String model)` | `hasChunksFromOtherModels` explica una búsqueda vacía tras un cambio de modelo (aviso en el log). `saveEmbeddings` devuelve `false` si `updated_at` ya no coincide (reingesta concurrente) y no escribe nada. |
| `EmbeddingMetricsPort` | `recordBatch(IndexOutcome, Duration)`, `recordDocument(IndexOutcome)`, `recordSearch(Duration)` | Etiquetas de valores fijos. |

## Búsqueda (SQL del adaptador)

```sql
SELECT c.id, c.document_id, d.source, c.metadata ->> 'section' AS section, c.chunk_index, c.content,
       1 - (c.embedding <=> ?::public.vector) AS score
FROM retrieval.chunk c
JOIN retrieval.document d ON d.id = c.document_id
WHERE c.embedding IS NOT NULL
  AND c.embedding_model = ?
ORDER BY c.embedding <=> ?::public.vector
LIMIT ?
```

El vector viaja como literal `[v1,v2,...]` (utilidad `VectorLiteral`). Reglas: consulta vacía o solo espacios →
`SEARCH_QUERY_INVALID` sin llamar al modelo; sin chunks elegibles → lista vacía; resultado ordenado por `score`
descendente.

## Guardado de vectores (atómico por documento)

```sql
-- dentro de una transacción
SELECT updated_at FROM retrieval.document WHERE id = ? FOR UPDATE;       -- distinto de la versión leída => abortar (false)
UPDATE retrieval.chunk SET embedding = ?::public.vector, embedding_model = ? WHERE id = ?;   -- en lote
UPDATE retrieval.document SET index_status = 'INDEXED', index_error = NULL, indexed_at = now() WHERE id = ?;
```

## Configuración

| Propiedad | Variable | Defecto | Efecto |
|---|---|---|---|
| `spring.ai.ollama.embedding.options.model` | `OLLAMA_EMBED_MODEL` | `bge-m3` | Modelo usado y nombre guardado en `chunk.embedding_model` |
| `spring.ai.ollama.base-url` | `OLLAMA_BASE_URL` | `http://localhost:11434` | Ya existe (LAB-11) |
| `shoplab.retrieval.embedding.batch-size` | `SHOPLAB_RETRIEVAL_EMBEDDING_BATCH_SIZE` | `16` | Textos por llamada al modelo (≥ 1) |
| `shoplab.retrieval.embedding.search-limit` | `SHOPLAB_RETRIEVAL_EMBEDDING_SEARCH_LIMIT` | `5` | Resultados por búsqueda si no se indica |
| `shoplab.retrieval.embedding.auto-index` | `SHOPLAB_RETRIEVAL_EMBEDDING_AUTO_INDEX` | `true` | Indexa tras cada ingesta y al arrancar; los tests lo desactivan |
| `shoplab.retrieval.embedding.reindex-on-startup` | `SHOPLAB_RETRIEVAL_EMBEDDING_REINDEX_ON_STARTUP` | `false` | `false`: solo pendientes; `true`: reindexa los de otro modelo; `force`: todos |

Un `batch-size` menor que 1 impide arrancar con un mensaje claro.

## Errores (`ResponseCodeEnum`)

| Constante | Código | HTTP | Cuándo |
|---|---|---|---|
| `EMBEDDING_SERVICE_UNAVAILABLE` | `SLHD_RETR_00006` | 503 | Sin conexión o tiempo de espera al modelo |
| `EMBEDDING_SERVICE_FAILED` | `SLHD_RETR_00007` | 502 | El modelo responde con error (incluye texto demasiado largo) o respuesta inconsistente |
| `EMBEDDING_DIMENSION_MISMATCH` | `SLHD_RETR_00008` | 502 | Un vector no tiene 1 024 valores |
| `SEARCH_QUERY_INVALID` | `SLHD_RETR_00009` | 422 | Consulta vacía o solo espacios |

Los tres primeros, durante la indexación, no se propagan: dejan el documento en `ERROR` con el mensaje del código en
`index_error`. En la búsqueda sí se propagan al llamante.

## Eventos

`DocumentIngestedEvent(UUID documentId, String source)` — publicado en proceso por `IngestDocumentUseCase` tras guardar el
documento; lo consume `IndexAfterIngestListener` en el mismo módulo. No cruza módulos.
