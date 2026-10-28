# ingest

Servicio de parseo y troceado de documentos de ShopLab Helpdesk (FastAPI, Python 3.12). Recibe un PDF o un Markdown y
devuelve sus chunks con metadatos (documento, sección, posición). **No guarda nada** y no tiene acceso a ninguna base de
datos: el módulo `retrieval` del backend lo llama y es quien guarda el resultado (ADR-0015, ADR-0016).

## API

| Ruta | Qué hace |
|---|---|
| `POST /ingest` | `multipart/form-data` con `file` (`.pdf` o `.md`) y, opcionales, `chunk_size` y `chunk_overlap`. Devuelve `{document, chunks[{text, metadata{document, section, position}}]}`. |
| `GET /health` | Sonda de salud: `{"status":"ok"}`. |
| `GET /metrics` | Métricas en formato Prometheus (`ingest_requests_total{outcome}`, `ingest_duration_seconds`). |

El contrato completo está en [`openapi.json`](openapi.json). Errores: `{code, message}` con `UNSUPPORTED_FORMAT` (415),
`DOCUMENT_TOO_LARGE` (413), `EMPTY_DOCUMENT`, `NO_EXTRACTABLE_TEXT`, `INVALID_DOCUMENT` e `INVALID_CHUNKING_CONFIG`
(422) e `INTERNAL_ERROR` (500). Ver `specs/LAB-20-document-parsing-chunking/`.

## Configuración

| Variable | Defecto | Significado |
|---|---|---|
| `INGEST_CHUNK_SIZE` | `1000` | Tamaño máximo de un chunk, en caracteres, solape incluido (mínimo 100). |
| `INGEST_CHUNK_OVERLAP` | `100` | Caracteres que un chunk repite del anterior de su sección (menor que el tamaño). |
| `INGEST_MAX_DOCUMENT_BYTES` | `10485760` | Tamaño máximo del documento (10 MiB). |

El servicio no arranca si el solape no es menor que el tamaño.

## Cómo trocea

- **Markdown:** los encabezados delimitan secciones; tablas, listas y bloques de código se mantienen enteros. El
  *front matter* YAML del inicio se descarta.
- **PDF:** el texto se lee en orden de página; el tamaño de letra más frecuente es el cuerpo y una línea corta
  (hasta 120 caracteres) con al menos 1 pt más es un título. Sin títulos reconocibles se trocea por tamaño.
- Un chunk nunca mezcla dos secciones. Una sección larga se corta en párrafos, líneas, frases, palabras y, al final,
  caracteres. El encabezado de la sección abre su primer chunk.

Límites: sin OCR (un PDF escaneado se rechaza con `NO_EXTRACTABLE_TEXT`), solo PDF con texto y de una columna, y
Markdown en UTF-8.

## Desarrollo

```bash
uv sync --frozen
uv run uvicorn app.main:app --port 8000   # arrancar
uv run ruff check .                       # estilo (líneas de 121 caracteres)
uv run pytest                             # pruebas con cobertura mínima del 80 %
uv run pytest --no-cov tests/test_api.py  # una prueba suelta, sin el umbral de cobertura
uv run python -m app.openapi_export       # regenerar openapi.json tras cambiar la API
```

`tests/test_openapi_contract.py` falla si `openapi.json` no coincide con el servicio, y la prueba de contrato del
backend (`IngestContractTests`) falla si el cliente de `retrieval` no coincide con `openapi.json`.
