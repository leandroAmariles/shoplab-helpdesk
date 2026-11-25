# Data Model: LAB-129

Base `retrieval`, esquema `retrieval`, dueño: rol `retrieval`. La extensión `vector` vive en `public`.

## Tablas que se mueven sin cambios (V1–V4)

### document

| Campo | Tipo | Regla |
|---|---|---|
| `id` | uuid | PK |
| `source` | text | único |
| `title` | text | obligatorio |
| `metadata` | jsonb | objeto |
| `created_at`, `updated_at` | timestamptz | `updated_at` es la versión del contenido |
| `index_status` | text | `PENDING`, `INDEXED`, `ERROR` |
| `index_error` | text | presente si y solo si `ERROR` |
| `indexed_at` | timestamptz | presente si y solo si `INDEXED` |

### chunk

| Campo | Tipo | Regla |
|---|---|---|
| `id` | uuid | PK |
| `document_id` | uuid | FK a `document`, borrado en cascada |
| `chunk_index` | integer | ≥ 0; único por documento |
| `content` | text | no vacío |
| `embedding` | `public.vector(1024)` | nulo si y solo si `embedding_model` es nulo |
| `embedding_model` | text | no vacío |
| `metadata` | jsonb | objeto |
| `created_at` | timestamptz | |

Índices: HNSW coseno sobre `embedding`, GIN sobre `metadata`.

## Tabla nueva (V5__indexing_lock.sql)

### indexing_lock

| Campo | Tipo | Regla |
|---|---|---|
| `name` | text | PK; hoy solo `embeddings` |
| `owner` | text | identificador aleatorio de la instancia, no vacío |
| `locked_until` | timestamptz | calculado con `now()` de la base |

### Operaciones

| Operación | Condición | Resultado si no se cumple |
|---|---|---|
| Tomar | no hay fila, o `locked_until < now()` | la ejecución se omite (`skipped`) |
| Renovar (antes de cada lote) | `owner` es el propio | la ejecución se detiene sin guardar |
| Liberar | `owner` es el propio | no hace nada |

### Estados

```text
libre ──tomar──> tomado ──liberar──> libre
                   │ └──renovar──> tomado
                   └──caduca (instancia muerta)──> disponible para otra instancia
```

Búsquedas y subidas no consultan esta tabla (FR-014).

## Estado de un documento (sin cambios)

```text
(subida) ──> PENDING ──vectores guardados──> INDEXED
                │ └──fallo del modelo──> ERROR
INDEXED / ERROR ──reingesta──> PENDING
```
