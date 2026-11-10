# Contrato: API de documentos (`/api/documents`)

Dos operaciones nuevas del módulo `retrieval`. No cambia ninguna operación existente (`/api/chat`, `POST /ingest` de
`ingest`). Errores con el contrato vigente: `{ "code": "SLHD_...", "message": "..." }` (`ErrorResponse`), mapeados solo en
`GlobalExceptionHandler`. Cada respuesta lleva la cabecera `X-Request-Id` (`RequestIdFilter`).

## 1. `POST /api/documents` — subir un documento

`Content-Type: multipart/form-data`

| Parte | Tipo | Reglas |
|---|---|---|
| `file` | archivo | Obligatoria. PDF o Markdown. Nombre = nombre original reducido a su último segmento. Tamaño ≤ `shoplab.retrieval.upload.max-bytes` (10 MiB) |

### Respuesta correcta

`201 Created` si el documento es nuevo; `200 OK` si reemplaza a uno del mismo nombre. El cuerpo es el mismo:

```json
{
  "id": "6f1c1c1e-0d6b-4e57-9a6d-3b9a7f0f4a11",
  "name": "politica-devoluciones.md",
  "title": "Política de devoluciones",
  "status": "PENDING",
  "chunkCount": 14,
  "replaced": false
}
```

| Campo | Tipo | Notas |
|---|---|---|
| `id` | UUID | se conserva al reemplazar |
| `name` | cadena | `retrieval.document.source` |
| `title` | cadena | título extraído |
| `status` | `PENDING` | siempre `PENDING` al responder: los embeddings se calculan después (ADR-0017) |
| `chunkCount` | entero ≥ 1 | chunks guardados |
| `replaced` | booleano | `true` si ya existía el nombre |

### Errores

| Situación | HTTP | Código | Origen |
|---|---|---|---|
| Parte `file` ausente, no es multipart o cuerpo ilegible | 400 | `SLHD_GENE_00002` | existente |
| Nombre vacío o con separadores, contenido vacío | 422 | `SLHD_RETR_00003` | existente (`DOCUMENT_INVALID`) |
| Formato no admitido | 415 | `SLHD_RETR_00001` | existente (de `ingest`) |
| Tamaño por encima del límite | 413 | `SLHD_RETR_00002` | existente (backend o `ingest`) |
| Sin texto extraíble, corrupto, no se puede trocear | 422 | `SLHD_RETR_00003` | existente (de `ingest`) |
| `ingest` no disponible | 503 | `SLHD_RETR_00004` | existente |
| `ingest` falla | 502 | `SLHD_RETR_00005` | existente |
| Base de datos no disponible | 503 | `SLHD_DBAS_00001` | existente |

No se añaden códigos de error nuevos. Si falla cualquier paso, **no se guarda nada** (transacción de LAB-20).

### Efectos

1. Se ingiere (troceado y guardado, `replaceDocument`).
2. Se publica `DocumentIngestedEvent`; `IndexAfterIngestListener` inicia los embeddings sin que la respuesta espere.
3. Se registra la métrica `retrieval.upload{outcome}` y un log INFO con nombre, resultado, chunks y `requestId`.

## 2. `GET /api/documents` — listar documentos

### Respuesta

`200 OK`, orden `updatedAt` descendente (empate por `id`). Lista vacía = `[]`.

```json
[
  {
    "id": "6f1c1c1e-0d6b-4e57-9a6d-3b9a7f0f4a11",
    "name": "politica-devoluciones.md",
    "title": "Política de devoluciones",
    "status": "INDEXED",
    "chunkCount": 14,
    "error": null,
    "updatedAt": "2026-11-10T15:02:11.482Z",
    "indexedAt": "2026-11-10T15:02:19.003Z"
  },
  {
    "id": "0a3e5a9b-4b2a-4a70-8c64-1d0c2a6f9b77",
    "name": "envios.pdf",
    "title": "Envíos",
    "status": "ERROR",
    "chunkCount": 9,
    "error": "The embedding model is not available",
    "updatedAt": "2026-11-10T14:40:00.100Z",
    "indexedAt": null
  }
]
```

| Campo | Tipo | Notas |
|---|---|---|
| `status` | `PENDING` \| `INDEXED` \| `ERROR` | tal cual `index_status` |
| `chunkCount` | entero ≥ 0 | chunks guardados del documento |
| `error` | cadena o `null` | solo con `ERROR`; texto por defecto del código de LAB-21 (inglés, sin datos internos). La pantalla lo traduce |
| `updatedAt` / `indexedAt` | ISO-8601 UTC | `indexedAt` nulo salvo `INDEXED` |

### Errores

| Situación | HTTP | Código |
|---|---|---|
| Base de datos no disponible | 503 | `SLHD_DBAS_00001` |

## 3. Reglas transversales

- **Sin autenticación en F1** (ADR-0008); la operación queda abierta como `/api/chat`. Deuda registrada en ADR-0021.
- **Sin texto de documentos** en registros ni etiquetas de métricas (Constitución VI); el cuerpo de `GET` no incluye chunks.
- **Compatibilidad**: la respuesta es aditiva; campos nuevos futuros se añaden sin romper clientes.
- **Ingress**: el límite de cuerpo del Ingress del backend debe ser ≥ `max-bytes` + margen multipart (ver `research.md` R3).
