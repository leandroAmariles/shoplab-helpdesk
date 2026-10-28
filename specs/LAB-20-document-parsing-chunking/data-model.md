# Data Model: Parseo y chunking de documentos (LAB-20)

## 1. Servicio `ingest` (sin estado: sin tablas)

Modelos en memoria, creados por petición y descartados al responder.

| Modelo | Campos | Reglas |
|---|---|---|
| `ParsedSection` | `path: list[str]` (encabezados), `blocks: list[str]` | `path` vacía si no hay encabezados; `blocks` sin cadenas vacías |
| `Chunk` | `text: str`, `document: str`, `section: str`, `position: int` | `text` no vacío y de longitud ≤ `chunk_size`; `section` = `path` unida por ` > `; `position` desde 0, consecutiva |
| `IngestResult` | `name: str`, `title: str`, `format: "markdown" \| "pdf"`, `chunks: list[Chunk]` | `chunks` nunca vacía (si no hay texto, error `EMPTY_DOCUMENT` o `NO_EXTRACTABLE_TEXT`) |
| `ChunkingConfig` | `chunk_size: int`, `chunk_overlap: int` | `chunk_size ≥ 100`; `0 ≤ chunk_overlap < chunk_size` |

Transiciones: no hay estado; `bytes → ParsedSection* → Chunk* → IngestResult`.

## 2. Módulo `retrieval` (dominio y aplicación)

| Modelo | Campos | Notas |
|---|---|---|
| `IngestDocumentCommand` | `name`, `content: byte[]` | entrada del caso de uso; `name` no vacío |
| `ParsedDocument` | `name`, `title`, `format`, `chunks: List<ParsedChunk>` | salida del puerto `DocumentParserPort` |
| `ParsedChunk` | `position`, `text`, `section` | sin identidad; se convierte a fila al guardar |
| `IngestedDocument` | `documentId: UUID`, `source`, `title`, `chunkCount` | resultado del caso de uso |

Registros inmutables en `domain/model`; sin dependencias de Spring, JDBC ni Reactor (regla ArchUnit de ADR-0015).

## 3. Esquema `retrieval` (cambios de esta historia)

Tablas de LAB-19 (`document`, `chunk`) sin cambios de estructura salvo `V3`.

### Migración `V3__chunk_embedding_optional.sql`

```sql
ALTER TABLE chunk ALTER COLUMN embedding DROP NOT NULL;
ALTER TABLE chunk ALTER COLUMN embedding_model DROP NOT NULL;
ALTER TABLE chunk ADD CONSTRAINT chunk_embedding_pair_chk
    CHECK ((embedding IS NULL) = (embedding_model IS NULL));
```

(Esbozo; el SQL definitivo lleva el comentario de cabecera como los de V1/V2.) El índice `chunk_embedding_hnsw_idx` no
cambia. La dimensión 1024 y la restricción `btrim(embedding_model) <> ''` se conservan.

### Correspondencia respuesta de `ingest` → filas

| Origen (respuesta) | Destino |
|---|---|
| `document.name` | `document.source` (único) |
| `document.title` | `document.title` |
| `{"format": document.format}` | `document.metadata` |
| `chunks[i].metadata.position` | `chunk.chunk_index` |
| `chunks[i].text` | `chunk.content` |
| `chunks[i].metadata` (`document`, `section`, `position`) | `chunk.metadata` |
| (ausente) | `chunk.embedding`, `chunk.embedding_model` = `NULL` hasta LAB-21 |

### Reglas de persistencia

- Reingesta de un `source` existente: se actualizan `title`, `metadata` y `updated_at`; se conserva `document.id`; se
  borran todos los chunks anteriores y se insertan los nuevos, en la misma transacción.
- Fallo en cualquier punto: la transacción se revierte; el estado previo queda intacto.
- Un chunk sin vector no participa en la búsqueda (criterio añadido a LAB-21: la búsqueda filtra `embedding IS NOT NULL`).

### Estado implícito de un documento (para LAB-25)

`pendiente` = existe algún chunk con `embedding IS NULL`; `indexado` = todos los chunks tienen vector. «Error» no se guarda
en esta historia (una ingesta fallida no deja filas); su representación la decide LAB-25.

## 4. Errores nuevos de `retrieval` (`ResponseCodeEnum`)

| Constante | Código | HTTP | Origen |
|---|---|---|---|
| `DOCUMENT_UNSUPPORTED_FORMAT` | `SLHD_RETR_00001` | 415 | `ingest` → `UNSUPPORTED_FORMAT` |
| `DOCUMENT_TOO_LARGE` | `SLHD_RETR_00002` | 413 | `ingest` → `DOCUMENT_TOO_LARGE` |
| `DOCUMENT_INVALID` | `SLHD_RETR_00003` | 422 | `EMPTY_DOCUMENT`, `NO_EXTRACTABLE_TEXT`, `INVALID_DOCUMENT`, `INVALID_CHUNKING_CONFIG` |
| `INGEST_SERVICE_UNAVAILABLE` | `SLHD_RETR_00004` | 503 | sin conexión o tiempo de espera |
| `INGEST_SERVICE_FAILED` | `SLHD_RETR_00005` | 502 | 5xx o respuesta ilegible de `ingest` |
