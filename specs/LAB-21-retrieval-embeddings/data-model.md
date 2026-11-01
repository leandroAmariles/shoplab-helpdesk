# Data Model: Embeddings calculados solo en retrieval (LAB-21)

## 1. Esquema `retrieval` (cambios de esta historia)

Sin tablas nuevas. `chunk` no cambia (V3 ya dejó `embedding` y `embedding_model` opcionales como pareja). `document` gana
estado.

### Migración `V4__document_index_status.sql` (esbozo)

```sql
ALTER TABLE document ADD COLUMN index_status text NOT NULL DEFAULT 'PENDING'
    CHECK (index_status IN ('PENDING', 'INDEXED', 'ERROR'));
ALTER TABLE document ADD COLUMN index_error text;
ALTER TABLE document ADD COLUMN indexed_at timestamptz;
ALTER TABLE document ADD CONSTRAINT document_index_state_chk CHECK (
    (index_status = 'ERROR') = (index_error IS NOT NULL)
    AND (index_status = 'INDEXED') = (indexed_at IS NOT NULL));

-- Existing documents: indexed when every chunk already has its vector.
UPDATE document d SET index_status = 'INDEXED', indexed_at = now()
WHERE EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id AND c.embedding IS NULL);
```

(Esbozo; el SQL definitivo lleva el comentario de cabecera como V1 a V3. Un documento sin chunks queda `PENDING`.) El
índice `chunk_embedding_hnsw_idx` y la dimensión 1 024 no cambian.

### Estados del documento

| Estado | Significado | Entra desde | Sale a |
|---|---|---|---|
| `PENDING` | tiene chunks sin vector o acaba de ingerirse/reingerirse | alta o reingesta (LAB-20) | `INDEXED`, `ERROR` |
| `INDEXED` | todos sus chunks tienen vector del modelo con que se indexó | cálculo correcto | `PENDING` (reingesta), `ERROR` (reindexado fallido) |
| `ERROR` | el último cálculo falló; `index_error` explica la causa | cálculo fallido | `INDEXED` (reintento correcto), `PENDING` (reingesta) |

Reglas:

- La reingesta (`replaceDocument`) fija `index_status = 'PENDING'`, `index_error = NULL`, `indexed_at = NULL` y actualiza
  `updated_at`, en la misma transacción que reemplaza los chunks.
- El paso a `INDEXED` y la escritura de todos los vectores ocurren en una sola transacción (atómico por documento).
- El paso a `ERROR` no modifica vectores: un reindexado fallido conserva los anteriores completos.
- `updated_at` es el testigo optimista: cambia con cada reingesta; el guardado de vectores solo procede si coincide con
  el leído antes de calcular.

## 2. Módulo `retrieval` (dominio y aplicación)

Registros inmutables en `domain/model`, sin dependencias de Spring, JDBC ni Reactor (regla ArchUnit de ADR-0015).

| Modelo | Campos | Notas |
|---|---|---|
| `PendingDocument` | `documentId: UUID`, `source`, `version: Instant` (su `updated_at`), `chunks: List<ChunkToEmbed>` | unidad de trabajo del cálculo |
| `ChunkToEmbed` | `chunkId: UUID`, `position`, `text` | el texto solo vive en memoria durante el cálculo |
| `ChunkEmbedding` | `chunkId: UUID`, `vector: float[]` | `vector.length == 1024` (validado en el adaptador) |
| `SearchResult` | `chunkId`, `documentId`, `source`, `section`, `position`, `text`, `score: double` | `score = 1 - distancia coseno`, en [-1, 1] (en la práctica [0, 1]) |
| `IndexingSummary` | `documents: int`, `chunks: int`, `failed: List<IndexingFailure>`, `skipped: boolean` | resultado de indexar o reindexar |
| `IndexingFailure` | `source`, `code: String` | solo el código del error, sin texto |

| Enum | Valores | Notas |
|---|---|---|
| `IndexStatus` | `PENDING`, `INDEXED`, `ERROR` | `externalValue` = literal de la columna |
| `IndexScope` | `PENDING`, `RETRY`, `OTHER_MODEL`, `ALL` | qué documentos recorre una ejecución (`RETRY` = pendientes y en error) |
| `ReindexMode` | `NONE`, `CHANGED_MODEL`, `ALL` | valor de `reindex-on-startup` (`false`, `true`, `force`) |
| `IndexOutcome` | `INDEXED`, `FAILED`, `SKIPPED` | etiqueta de métrica |

Constantes nuevas (`RetrievalConstants`): `EMBEDDING_DIMENSIONS = 1024`, nombres de las métricas y de sus etiquetas.

## 3. Correspondencia de lectura y escritura

| Operación | Origen | Destino |
|---|---|---|
| Leer pendientes | `document` + `chunk` donde `embedding IS NULL` (o modelo distinto, según el ámbito) | `PendingDocument` |
| Guardar vectores | `ChunkEmbedding` + modelo configurado | `chunk.embedding`, `chunk.embedding_model`; `document.index_status = 'INDEXED'`, `indexed_at = now()` |
| Marcar error | código de error | `document.index_status = 'ERROR'`, `index_error = <mensaje del código>` |
| Buscar | embedding de la consulta + modelo configurado | `SearchResult` (chunks con `embedding IS NOT NULL` y mismo modelo) |

El texto, la posición y los metadatos de `chunk` no se modifican nunca (FR-017).

## 4. Fuera de alcance de datos

Cambiar la dimensión del vector, índices parciales por modelo, histórico de reindexados y estado por chunk.
