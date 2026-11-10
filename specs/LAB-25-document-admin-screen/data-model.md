# Data Model: Pantalla de administración de documentos (LAB-25)

**Sin migraciones.** `retrieval.document` ya tiene todo lo necesario (`source`, `title`, `index_status`, `index_error`,
`indexed_at`, `updated_at`; V2 y V4) y los chunks se cuentan con un `JOIN`. Ningún esquema de otro módulo cambia.

## Entidades persistidas (existentes, solo lectura salvo la ya existente `replaceDocument`)

### Documento — `retrieval.document`

| Campo | Tipo | Uso en LAB-25 |
|---|---|---|
| `id` | uuid | identificador estable (se conserva al reemplazar) |
| `source` | text, único | nombre del archivo → `name` en la API |
| `title` | text | título extraído por `ingest` |
| `index_status` | `PENDING` / `INDEXED` / `ERROR` | estado mostrado |
| `index_error` | text, nulo | causa del último fallo (texto por defecto de `ResponseCodeEnum`, LAB-21) |
| `indexed_at` | timestamptz, nulo | fecha de indexación |
| `updated_at` | timestamptz | fecha de la última ingesta; orden de la lista |

Reglas ya impuestas por `document_index_state_chk`: `ERROR` ⇔ `index_error` no nulo; `INDEXED` ⇔ `indexed_at` no nulo.

### Chunk — `retrieval.chunk`

Solo se usa su recuento por `document_id` (incluye chunks aún sin vector: el recuento es el de chunks guardados, FR-006).

## Transiciones de estado (existentes, LAB-20/21)

```text
        subida (nuevo o reemplazo)                  vectores completos
(ninguno) ───────────────────────────► PENDING ──────────────────────────► INDEXED
                                          │  ▲                                  │
                                          │  └──────── subida otra vez ─────────┤
                                          │ fallo de embeddings                 │
                                          ▼                                     │
                                        ERROR ──────── subida otra vez ─────────┘
                                          └─ reindexado (indexPending(true)) ──► INDEXED
```

Subir un nombre existente vuelve el documento a `PENDING` (ya lo hace `UPSERT_DOCUMENT`).

## Tipos nuevos (no persistidos)

### Backend (`retrieval`)

| Tipo | Capa | Campos | Notas |
|---|---|---|---|
| `DocumentSummary` | `domain/model` | `id`, `name`, `title`, `status: IndexStatus`, `chunkCount`, `error`, `updatedAt`, `indexedAt` | proyección de solo lectura |
| `IngestedDocument` (modificado) | `domain/model` | + `boolean replaced` | `true` si el nombre ya existía |
| `DocumentCatalogPort` | `application/port/out` | `Flux<DocumentSummary> listDocuments()` | |
| `ListDocumentsServicePort` | `application/port/in` | `Flux<DocumentSummary> list()` | |
| `UploadProperties` | `config` | `maxBytes` | `shoplab.retrieval.upload.max-bytes` |

### Frontend (`features/documents`)

| Tipo | Campos |
|---|---|
| `DocumentSummary` | igual que la respuesta de la API (`updatedAt`/`indexedAt` como `string` ISO) |
| `UploadItem` | `id` (local), `file`, `name`, `size`, `state`, `percent`, `error?`, `replaced?`, `chunkCount?` |
| `UploadState` | `queued` → `sending` → `processing` → `done` ; ramas `failed`, `cancelled`, `rejected` (validación local) |

```text
queued ─► sending ─► processing ─► done
  │          │            │
  │          └──── cancelled / failed
  └─ rejected (validación local: formato o tamaño)
```

## Validaciones

- Formato local (ayuda de UX): extensión `.pdf` o `.md`/`.markdown`; el servidor decide en última instancia (415 / 422).
- Tamaño: `file.size ≤ 10 MiB` en el cliente; `max-bytes` en el servidor; `ingest` como última autoridad.
- Nombre: último segmento, no vacío, sin `/` ni `\` (caso de uso existente).
- Contenido no vacío (caso de uso existente) → `SLHD_RETR_00003`.
