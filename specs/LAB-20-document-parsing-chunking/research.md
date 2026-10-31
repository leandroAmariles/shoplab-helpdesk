# Research: Parseo y chunking de documentos (LAB-20)

Decisiones que cierran los puntos abiertos del plan. Formato: decisión, motivo, alternativas descartadas.

## D-1. Forma de la petición: `multipart/form-data`

- **Decisión**: `POST /ingest` recibe `multipart/form-data` con la parte `file` (el documento, con su nombre de archivo) y
  dos campos opcionales, `chunk_size` y `chunk_overlap`, que sustituyen los valores por defecto del servicio solo para esa
  petición.
- **Motivo**: es la forma natural de enviar un PDF binario sin codificarlo; el cliente de `retrieval` (`WebClient`) la
  construye con `MultipartBodyBuilder`; el tipo se decide por la extensión y la firma del archivo, no por el
  `Content-Type`, que los clientes rellenan mal. Los campos opcionales permiten a la evaluación de F2 comparar tamaños de
  chunk sin redesplegar `ingest`.
- **Descartadas**: JSON con el contenido en base64 (+33 % de tamaño y memoria, sin ventaja); cuerpo binario crudo con el
  nombre en una cabecera (más difícil de describir en OpenAPI y de probar a mano).

## D-2. Respuesta: documento más lista ordenada de chunks

- **Decisión**: `200` con `{ "document": {name, title, format}, "chunks": [{ "text", "metadata": {document, section,
  position} }] }`. `position` va desde 0, es consecutiva y coincide con el orden de la lista. `section` es la ruta de
  encabezados unida por ` > ` (cadena vacía si no hay).
- **Motivo**: es literalmente lo que pide el criterio («chunks con metadatos documento, sección, posición»); `retrieval`
  copia `metadata` a `chunk.metadata` y `position` a `chunk.chunk_index` sin transformar.
- **Descartadas**: devolver solo textos (obliga a `retrieval` a reconstruir sección y posición); paginar la respuesta
  (un documento de 10 MB da unos miles de chunks; la complejidad no compensa en F1).

## D-3. Errores de `ingest`: JSON con código estable

- **Decisión**: cuerpo `{ "code", "message" }` y estos códigos y estados:

  | `code` | HTTP | Caso |
  |---|---|---|
  | `UNSUPPORTED_FORMAT` | 415 | extensión distinta de `.pdf` y `.md` |
  | `DOCUMENT_TOO_LARGE` | 413 | supera `INGEST_MAX_DOCUMENT_BYTES` |
  | `EMPTY_DOCUMENT` | 422 | vacío o solo espacios |
  | `NO_EXTRACTABLE_TEXT` | 422 | PDF sin capa de texto (escaneado) |
  | `INVALID_DOCUMENT` | 422 | extensión válida con contenido que no corresponde (`.pdf` sin firma `%PDF-`), PDF corrupto o con contraseña, Markdown que no es UTF-8 |
  | `INVALID_CHUNKING_CONFIG` | 422 | `chunk_overlap >= chunk_size` u otros valores fuera de rango |

  Un error inesperado devuelve `500` con `INTERNAL_ERROR`; nunca se responde éxito con lista vacía.
- **Motivo**: `retrieval` distingue «el documento no vale» (4xx → error de negocio para el administrador) de «el servicio
  falló» (5xx, tiempo de espera, sin conexión → error técnico). Coherente con la separación `BusinessException` /
  `TechnicalException` del backend (Constitución III).
- **Descartadas**: `application/problem+json` (RFC 9457): válido, pero el backend usa su propio `ErrorResponse`
  simple y `retrieval` solo necesita el código; un formato propio mínimo evita una dependencia más.

## D-4. Librerías de `ingest`

- **Decisión**: FastAPI + Uvicorn; `python-multipart` para la subida; `markdown-it-py` para Markdown;
  `pdfplumber` (sobre `pdfminer.six`) para PDF; `pydantic-settings` para la configuración. Pruebas: pytest, httpx
  (`TestClient`), pytest-cov, ruff; los PDF de prueba se generan con `fpdf2`, que ya usa `data/kb-tools`.
- **Motivo**: `markdown-it-py` y `pypdf` ya están en el repo (`data/kb-tools`) y dan tokens de encabezado fiables.
  Para PDF hace falta el tamaño de letra de cada línea para reconocer títulos; `pdfplumber` lo expone por carácter
  (`size`, `fontname`) con licencia MIT. `pypdf` solo extrae texto plano, sin tamaños.
- **Descartadas**: `PyMuPDF` (AGPL: obligaría a revisar la licencia de la imagen publicada); `unstructured` /
  `docling` (cientos de MB de dependencias y modelos para un problema que aquí se resuelve con reglas; Trivy y el
  tamaño de la imagen lo penalizarían); `pypdf` (sin tamaños de letra).

## D-5. Algoritmo de troceado

1. El parser devuelve **secciones**: `(ruta de encabezados, bloques de texto)` en orden de documento. Un bloque es un
   párrafo, una tabla, una lista o un bloque de código.
2. Por cada sección con texto, se arma el texto con su encabezado como primera línea y se trocea:
   - Si cabe en `chunk_size`, es un solo chunk.
   - Si no, se acumulan bloques hasta llenar el chunk; un bloque que por sí solo excede el tamaño se divide en frases
     (`. `, `? `, `! `, salto de línea) y, si una frase aún no cabe, por palabras, y como último recurso por caracteres.
3. **Solape**: cada chunk de una sección, salvo el primero, empieza con los últimos `chunk_overlap` caracteres del chunk
   anterior (exactos, sin ajustar a palabra, para que el criterio sea comprobable). El tamaño máximo **incluye** el
   solape, de modo que ningún chunk lo supera.
4. Una sección sin texto propio (solo encabezado) no genera chunk. El texto de una sección nunca se mezcla con el de otra
   (FR-006).
5. Tamaño en **caracteres**: el tokenizador depende del modelo de embeddings (ADR-0003) y aún no está fijado en el código.
   Cuando LAB-21 fije el modelo se puede cambiar la unidad sin cambiar el contrato.

- **Valores por defecto**: `chunk_size = 1000`, `chunk_overlap = 100`, `max_document_bytes = 10 MiB`; configurables por
  variables `INGEST_CHUNK_SIZE`, `INGEST_CHUNK_OVERLAP`, `INGEST_MAX_DOCUMENT_BYTES`. 1 000 caracteres (~250 tokens) es el
  rango habitual para políticas cortas con `bge-m3` (ventana de 8 192 tokens, muy por encima).
- **Descartadas**: troceado semántico por embeddings (necesita el modelo, que `ingest` no debe usar: LAB-21);
  solape medido en frases (no coincide con el criterio «solape configurable» en un número simple).

## D-6. Detección de títulos en PDF

- **Decisión**: se agrupan los caracteres en líneas; el **tamaño de cuerpo** es el tamaño de letra más frecuente (por
  número de caracteres). Una línea es título si su tamaño supera el de cuerpo en al menos 1 pt y tiene como máximo 120
  caracteres. Los tamaños distintos de títulos se ordenan de mayor a menor para asignar el nivel (el mayor es nivel 1).
  Si todas las líneas tienen el mismo tamaño no hay títulos y el documento se trocea por tamaño.
- **Motivo**: los PDF del corpus se generan con `data/kb-tools` (`fpdf2`), con tamaños distintos por nivel; la regla
  cubre ese caso y los PDF de texto corriente sin heurísticas frágiles. Se descartan líneas que son solo un número de
  página.
- **Riesgo**: PDF con títulos solo en negrita y mismo tamaño no se reconocen. Se asume (queda como troceado por tamaño)
  y se anota para cuando haya un PDF real que lo pida.
- **Descartadas**: usar el índice o marcadores del PDF (`/Outlines`; la mayoría de PDF no los trae); OCR (fuera de
  alcance, FR-019).

## D-7. Metadatos del documento y título

- **Decisión**: `ingest` calcula `title` (primer encabezado de nivel 1; en su defecto, el nombre del archivo sin
  extensión) y `format` (`markdown` | `pdf`). `retrieval` guarda `document.source = nombre del archivo`,
  `document.title = title` y `document.metadata = {"format": ...}`.
- **Motivo**: el nombre es la referencia única y estable del corpus (`data/kb/INDEX.md` usa el id del archivo); la
  reingesta del mismo nombre reemplaza (D-9).
- **Nombre normalizado**: `ingest` descarta cualquier ruta del nombre recibido (`a/b.md` y `..\x.md` pasan a `b.md` y
  `x.md`) y el caso de uso de `retrieval` rechaza nombres con `/` o `\`, de modo que `source` nunca cambia según desde
  dónde se suba el mismo archivo.
- **Descartadas**: hash del contenido como `source` (cada edición crearía un documento nuevo).

## D-8. Cliente de `retrieval` hacia `ingest`: puerto + `WebClient`

- **Decisión**: puerto de salida `DocumentParserPort` en `application`; adaptador `IngestWebClientAdapter` en
  `infrastructure/out` con `WebClient` (solo se permiten llamadas salientes con `WebClient`: ADR-0011, ArchUnit).
  Configuración `shoplab.retrieval.ingest.base-url` (por defecto `http://localhost:8000`), `timeout` (30 s) y
  `max-response-bytes` (32 MiB, porque el `WebClient` limita el búfer a 256 KiB por defecto).
- **Mapeo de errores**: 4xx de `ingest` → `BusinessException` con el código de retrieval equivalente (D-3); 5xx, tiempo de
  espera, conexión rechazada o cuerpo ilegible → `TechnicalException` (`INGEST_SERVICE_UNAVAILABLE` /
  `INGEST_SERVICE_FAILED`). Sin reintentos automáticos (supuesto del spec).
- **Observabilidad**: se propaga `X-Request-Id` (del contexto de Reactor, ya existente) para correlacionar logs de ambos
  servicios, y un `Timer` Micrometer `retrieval.ingest.parse` por resultado (`ok`, `rejected`, `failed`).

## D-9. Guardado en `retrieval`: transacción única con reemplazo

- **Decisión**: dentro de una transacción JDBC ejecutada con `BlockingCalls` (ADR-0011): `INSERT ... ON CONFLICT (source)
  DO UPDATE SET title, metadata, updated_at RETURNING id`; `DELETE FROM chunk WHERE document_id = ?`; inserción por lotes de
  los chunks nuevos con `embedding` y `embedding_model` en `NULL`.
- **Motivo**: cumple «todo o nada» (FR-012) y «sin duplicar» (FR-013); conserva el `id` del documento al reingerir, de modo
  que las referencias externas por identificador (citas de `chat`) no se rompen.
- **Descartadas**: borrar el documento y recrearlo (cambia el `id`, deja huérfanas las referencias); actualizar chunk a
  chunk por posición (deja sobrantes si el documento nuevo es más corto y complica el código sin ventaja).

## D-10. Migración `V3` para chunks sin vector

- **Decisión**: `V3__chunk_embedding_optional.sql` en `db/migration/retrieval`: quita `NOT NULL` de `chunk.embedding` y
  `chunk.embedding_model` y añade `CHECK ((embedding IS NULL) = (embedding_model IS NULL))`. El índice HNSW no cambia
  (pgvector no indexa filas con vector nulo).
- **Motivo**: LAB-20 guarda chunks antes de que LAB-21 calcule vectores; el `CHECK` mantiene la trazabilidad de LAB-19:
  nunca hay vector sin modelo ni modelo sin vector. Relaja el FR-003 de LAB-19; las pruebas de LAB-19 que exigían
  `embedding_model` obligatorio se actualizan al nuevo contrato.
- **Descartadas**: tabla aparte de «chunks pendientes» (dos tablas para el mismo concepto, copia al completar);
  vector de ceros como marcador (contamina la búsqueda por coseno).

## D-11. Contrato OpenAPI versionado y comprobado en CI

- **Decisión**: FastAPI genera el esquema; se versiona en `ingest/openapi.json`. Una prueba de `ingest` falla si
  `app.openapi()` difiere del archivo (se regenera con `uv run python -m app.openapi_export`). En el backend, una prueba
  lee ese archivo con Jackson y comprueba que los campos del DTO de respuesta y los de la petición coinciden con el
  esquema, y que el JSON de ejemplo del contrato se deserializa. Ambas corren en la CI existente (jobs `python` y
  `backend`; el filtro de rutas del job `backend` se amplía a `ingest/openapi.json`).
- **Motivo**: ADR-0015 prevé `shoplab-contracts` cuando haya servicios separados (F5); hasta entonces el contrato vive junto
  al servicio que lo implementa y lo verifican las dos mitades. Sin dependencia nueva en el backend.
- **Descartadas**: generar el cliente Java desde OpenAPI (plugin y código generado para un único endpoint);
  `swagger-parser` en las pruebas (dependencia pesada para comparar unos campos).

## D-12. Despliegue y entorno local

- **Local** (`infra/docker-compose.yml`): servicio `ingest` construido desde `./ingest`, puerto `8000`, sin dependencia
  ni variables de base de datos (lo comprueba `IngestHasNoDatabaseCredentialsTests`, que además rechaza la palabra
  «postgres» dentro del bloque, así que no lleva `depends_on`).
- **Dev** (`shoplab-infra`, rama propia desde `main`, PR propia): la sonda de `ingest` pasa de `tcpSocket` a `httpGet
  /health`, y `backend-config` recibe `SHOPLAB_RETRIEVAL_INGEST_BASE_URL=http://ingest:8000`. Los cambios de infraestructura
  de la historia se hacen en la historia (no se difieren).
- **Imagen**: el `Dockerfile` actual ya construye con `uv sync --frozen --no-dev` en cuanto existe `pyproject.toml`;
  basta con `uv.lock`, `[tool.uv] package = false` y un `.dockerignore`.

## D-13. Registro de la decisión (ADR)

- **Decisión**: ADR-0016 «Troceado por encabezados en `ingest` y contrato síncrono con `retrieval`»: resume D-1, D-2, D-5 y
  D-11 (decisiones de estrategia de recuperación que exige la Constitución VII). Estado: aceptado al fusionar.

## D-14. Observabilidad de `ingest` (Constitución VI)

- **Decisión**: `ingest` expone `GET /metrics` en formato Prometheus con `prometheus-client`: contador
  `ingest_requests_total{outcome}` (`ok`, `rejected`, `failed`) e histograma `ingest_duration_seconds`; las etiquetas no
  llevan datos del documento. Los logs son JSON con `X-Request-Id`. Se recoge desde el Prometheus local
  (`infra/observability/prometheus/prometheus.yml`, job `ingest` en `ingest:8000`) y, en dev, con un `ServiceMonitor` en
  `shoplab-infra` (copia del del backend).
- **Motivo**: la constitución exige logs, métricas y trazas por etapa; con esto `ingest` cumple logs y métricas dentro de la
  historia, en lugar de diferirlos (el scrapeo es un cambio de infraestructura, que se hace en la historia).
- **Trazas distribuidas**: el proyecto aún no tiene pila de trazado (ni el backend); se añadirán a ambos servicios
  juntas en una historia de observabilidad. Queda anotado en Complexity Tracking del plan.
- **Descartadas**: diferir las métricas a una historia de seguimiento (deja un servicio nuevo sin métricas y repite el
  trabajo de infraestructura); OpenTelemetry solo en `ingest` (trazas sin continuación en el backend no sirven).

## Puntos abiertos

Ninguno. Los supuestos del spec (valores por defecto, sin OCR, sin reintentos, subida del administrador en LAB-25) se
mantienen.
