# Research: Pantalla de administración de documentos (LAB-25)

Todas las incógnitas del Technical Context quedan resueltas aquí. Cada decisión parte de lo que ya existe en el repo.

## R1 — Dónde vive el endpoint de subida y listado

- **Decisión**: dos operaciones REST en el módulo `retrieval`, un controlador nuevo `DocumentController`
  (`infrastructure/in/rest`): `POST /api/documents` (multipart, parte `file`) y `GET /api/documents`.
- **Razón**: el criterio de aceptación lo pide ("Endpoint REST de subida en el módulo `retrieval`"). `retrieval` es el
  único dueño de su esquema (ADR-0015) y ya tiene los casos de uso `IngestDocumentServicePort` e
  `IndexDocumentsServicePort`. El controlador solo arma el comando y llama al puerto de entrada (Constitución II).
- **Alternativas**: un módulo `admin` nuevo (viola ADR-0001/0015: tendría que leer tablas de `retrieval` o usar su
  `api`; complejidad sin beneficio en F1). Exponer solo `retrieval.api` a otro módulo: no hay consumidor interno.

## R2 — Lectura del multipart sin bloquear

- **Decisión**: `@RequestPart("file") FilePart`; el contenido se acumula con `DataBufferUtils.join(content, maxBytes)`
  y se libera cada buffer. El nombre es `FilePart.filename()` reducido a su último segmento. Sin acceso a disco propio.
- **Razón**: el backend es WebFlux (ADR-0011). `IngestDocumentCommand` ya recibe `byte[]` (LAB-20) y `ingest` limita a
  10 MiB, así que el documento cabe en memoria. `join` con límite corta el flujo si se excede (`DataBufferLimitException`)
  → `DOCUMENT_TOO_LARGE` sin leer el resto.
- **Alternativas**: guardar a fichero temporal (E/S bloqueante extra, sin necesidad hasta F4); streaming directo a
  `ingest` (rompe el contrato síncrono de ADR-0016, que recibe el `byte[]`).

## R3 — Límite de tamaño

- **Decisión**: propiedad `shoplab.retrieval.upload.max-bytes` (por defecto 10 485 760 = `INGEST_MAX_DOCUMENT_BYTES`
  de `ingest`). El backend la aplica antes de llamar a `ingest`; el frontend la conoce por una constante
  equivalente y valida antes de enviar. Se alinean `spring.webflux.multipart.max-in-memory-size` y
  `max-disk-usage-per-part` con ese valor + margen del multipart.
- **Razón**: FR-004/FR-008 piden rechazar sin guardar nada y avisar antes de enviar. `ingest` sigue siendo la autoridad
  (413 → `SLHD_RETR_00002`); el límite del backend evita mover bytes inútiles.
- **Riesgo**: el ingress (repo `shoplab-infra`) puede tener `client_max_body_size` por debajo de 10 MiB (el valor por
  defecto de nginx es 1 MiB) y devolvería 413 sin cuerpo del contrato. Hay que revisar/ajustar la anotación del
  Ingress del backend en el repo de infra; es parte de la historia (memoria: "Cambios de infra dentro de la historia").
  El frontend trata un 413 sin cuerpo igual que `DOCUMENT_TOO_LARGE`.

## R4 — Nombre de archivo y validación de formato

- **Decisión**: se aplica la validación que ya existe en `IngestDocumentUseCase` (nombre sin `/` ni `\`, contenido no
  vacío). El controlador toma solo el último segmento del nombre (`Path`-style, sin tocar disco) antes de armar el
  comando; formato y contenido los decide `ingest`.
- **Razón**: no duplicar reglas de LAB-20; un solo origen de verdad para formatos admitidos.
- **Alternativa**: validar extensión en el backend (divergiría de `ingest`). La pantalla filtra por `.pdf`/`.md` solo como
  ayuda de UX (atributo `accept`) y confía en el servidor.

## R5 — Distinguir "nuevo" de "reemplazado"

- **Decisión**: `IngestedDocument` gana `boolean replaced`. En `UPSERT_DOCUMENT` se añade
  `RETURNING id, (xmax = 0) AS inserted`; `replaced = !inserted`. La respuesta HTTP es `201 Created` si es nuevo y
  `200 OK` si reemplaza; el cuerpo también trae `replaced`.
- **Razón**: es la señal exacta sin una consulta extra ni carrera entre dos subidas (misma sentencia).
- **Alternativas**: consultar existencia antes (carrera y un viaje más); inferirlo comparando `created_at`/`updated_at`
  (frágil con relojes y subidas en el mismo milisegundo).

## R6 — Modelo del listado

- **Decisión**: puerto de salida nuevo `DocumentCatalogPort.listDocuments()` con `JdbcDocumentCatalogAdapter`
  (`BlockingCalls.stream`): un `SELECT` con `LEFT JOIN` agregado para contar chunks, ordenado por `updated_at DESC, id`.
  Puerto de entrada `ListDocumentsServicePort`. Campos: `id`, `name` (= `source`), `title`, `status`
  (`IndexStatus`), `chunkCount`, `error` (= `index_error`), `updatedAt`, `indexedAt`.
- **Razón**: separa lectura (consulta) de escritura (`KnowledgeBasePort`) sin tocar el adaptador existente y mantiene
  `JdbcKnowledgeBaseAdapter` enfocado (principio de simplicidad). Sin paginación: la base de F1 es de decenas de
  documentos (SC-004: 100). Si crece, se pagina con una historia propia.
- **Alternativas**: añadir `list()` a `KnowledgeBasePort` (mezcla escritura y lectura); paginar ya (YAGNI).

## R7 — Mensaje de error del documento en español

- **Decisión**: `index_error` guarda hoy `ResponseCodeEnum.getDefaultMessage()` (inglés, sin datos internos, LAB-21). La API
  lo devuelve tal cual en `error`; el frontend lo traduce con un diccionario de los tres textos conocidos
  (`EMBEDDING_SERVICE_UNAVAILABLE`, `EMBEDDING_SERVICE_FAILED`, `EMBEDDING_DIMENSION_MISMATCH`) y, si no coincide, muestra
  «Falló la indexación del documento».
- **Razón**: no se cambia el esquema ni lo que escribe LAB-21; el texto crudo nunca se pinta como mensaje principal.
- **Alternativa**: guardar un código estable en la base (migración + cambio en LAB-21): mejor a largo plazo, fuera de
  alcance; se anota como mejora.

## R8 — Disparo de embeddings y refresco de estado

- **Decisión**: no se llama a `IndexDocumentsServicePort` desde el controlador. `IngestDocumentUseCase` ya publica
  `DocumentIngestedEvent` y `IndexAfterIngestListener` inicia la indexación (con reintento si hay una en curso). La
  respuesta de la subida sale con el documento en `PENDING`.
- **Razón**: FR-002 (no esperar ni invalidar la ingesta por embeddings) ya está cubierto por LAB-21; duplicarlo
  causaría doble indexación. Si `shoplab.retrieval.embedding.auto-index=false`, el documento queda pendiente (por diseño).
- **Refresco**: el frontend consulta `GET /api/documents` cada 3 s **solo mientras haya documentos `PENDING`**, con
  parada al salir de la pantalla y tope de 10 min; backoff a 10 s tras 5 errores consecutivos (FR-011).
- **Alternativas**: SSE/WebSocket (más superficie; "sin tiempo real en F1" en la spec); polling fijo permanente
  (carga inútil).

## R9 — Progreso de la subida y cancelación en Angular

- **Decisión**: `HttpClient.post(..., formData, { reportProgress: true, observe: 'events' })`. `HttpEventType.UploadProgress`
  alimenta el porcentaje; al llegar al 100 % se pasa a «procesando» hasta `HttpResponse`. Cancelar = `unsubscribe`
  (aborta la petición). La cola procesa 1 archivo a la vez (decisión de la spec: secuencial o baja concurrencia).
- **Razón**: `fetch` (que usa el chat por SSE) no expone progreso de subida; `HttpClient` sí. El interceptor
  `apiErrorInterceptor` actual abre un *snackbar* genérico en cualquier error de `/api`: la pantalla necesita mostrar la
  causa concreta, así que el interceptor se amplía para leer el cuerpo `ErrorResponse` (`code`, `message`) y exponerlo en
  `ApiError`, y se evita el *snackbar* duplicado cuando el error tiene un código de negocio que la pantalla ya pinta.
- **Cancelación y servidor**: cancelar corta el cliente; si el servidor ya ingirió, el documento queda (consistente: o
  está todo o nada, ADR-0016). La pantalla lo refleja al refrescar la lista.
- **Alternativas**: `XMLHttpRequest` a mano (más código); `fetch` + `ReadableStream` de subida (no es estándar en todos los
  navegadores).

## R10 — Control de acceso

- **Decisión**: ninguno en esta historia. Sin autenticación en F1 y un solo entorno (ADR-0008); la operación queda
  abierta, igual que `/api/chat`. Se anota en la Consecuencias del ADR nuevo como deuda antes de exponer fuera de dev.
- **Riesgo**: cualquiera con acceso a la URL puede subir documentos al corpus. Aceptado para F1 (Keycloak está en el
  roadmap de seguridad).

## R11 — Métricas y registros

- **Decisión**: `DocumentUploadMetricsPort` (o ampliar `IngestMetricsPort`): `retrieval.upload` (timer) con etiqueta
  `outcome` ∈ {`ok`, `rejected`, `failed`}; gauge `retrieval.documents` con etiqueta `status`. Sin nombres ni texto
  de documentos. Registro INFO: nombre, resultado, chunks, `requestId`.
- **Razón**: FR-014 y Constitución VI. `retrieval.ingest.parse` (LAB-20) ya cubre el tramo `ingest`; esta métrica mide la
  operación completa de subida.
- **Nota**: el nombre del archivo ya aparece en registros de LAB-20 (`source`); no se considera dato personal en el corpus
  del proyecto, pero no se usa como etiqueta de métrica (cardinalidad).

## R12 — ADR y documentación

- **Decisión**: ADR-0021 «Subida y administración de documentos»: multipart sobre WebFlux, límite, reemplazo, estado por
  consulta, sin autenticación. Actualizar `docs/modulos.md` y `docs/modulos/module-retrieval.adoc`; `docs/base-conocimiento.md`
  gana el flujo de subida desde la pantalla.
- **Razón**: Constitución VII (decisión arquitectónica documentada) y consistencia con ADR-0016/0017/0020.

## Resumen de incógnitas

| Incógnita | Resuelta en |
|---|---|
| ¿Dónde vive la API? | R1 |
| ¿Cómo se lee el multipart en WebFlux? | R2 |
| ¿Qué límite de tamaño y qué pasa con el ingress? | R3 |
| ¿Cómo se sabe si fue reemplazo? | R5 |
| ¿Cómo se lista con recuento de chunks? | R6 |
| ¿Cómo se muestra la causa en español? | R7 |
| ¿Quién dispara los embeddings? | R8 |
| ¿Cómo se mide el progreso? | R9 |
