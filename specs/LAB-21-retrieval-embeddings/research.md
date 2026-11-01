# Research: Embeddings calculados solo en retrieval (LAB-21)

Decisiones que cierran los puntos abiertos del plan y de los supuestos del spec. Formato: decisión, motivo, alternativas
descartadas.

## D-1. Puerto propio sobre el `EmbeddingModel` de Spring AI

- **Decisión**: `EmbeddingPort` (puerto de salida) con `embed(List<String>) : Mono<List<float[]>>` y `modelName()`. Su
  adaptador `SpringAiEmbeddingAdapter` usa el `EmbeddingModel` autoconfigurado por `spring-ai-starter-model-ollama`
  (ya en el `pom.xml`), ejecuta la llamada bloqueante con `BlockingCalls` y valida que cada vector tenga 1 024 valores.
- **Motivo**: la constitución exige acceso a embeddings detrás de un puerto (II) y los casos de uso no pueden depender de
  Spring AI. Los tests de CI usan un doble determinista del puerto, sin servidor de modelos.
- **Descartadas**: inyectar `EmbeddingModel` en los casos de uso (acopla aplicación a Spring AI y a Ollama); un cliente
  HTTP propio a Ollama (reimplementa lo que Spring AI ya da).

## D-2. Una sola fuente para el nombre del modelo

- **Decisión**: el modelo se configura una vez, en `spring.ai.ollama.embedding.options.model` (por defecto
  `${OLLAMA_EMBED_MODEL:bge-m3}`, la misma variable que ya usa Ollama en compose y GitOps). `EmbeddingProperties` lee esa
  propiedad como `modelName` y el adaptador lo devuelve en `modelName()`; ese valor es el que se guarda en
  `chunk.embedding_model` y el que filtra la búsqueda.
- **Motivo**: evita que el modelo realmente invocado y el nombre registrado diverjan. Cambiar de modelo = cambiar una
  variable (SC-003).
- **Descartadas**: dos propiedades (modelo de Spring AI y nombre a guardar) que pueden desincronizarse; leer el nombre de
  la respuesta del modelo (no siempre lo devuelve).

## D-3. Estado del documento en `retrieval.document` (migración V4)

- **Decisión**: `V4__document_index_status.sql` añade a `document`: `index_status text NOT NULL DEFAULT 'PENDING'` con
  `CHECK` en (`PENDING`, `INDEXED`, `ERROR`), `index_error text` (causa legible, solo con `ERROR`, controlado por un
  `CHECK`) e `indexed_at timestamptz`. Rellena los documentos existentes: `INDEXED` si todos sus chunks tienen vector,
  `PENDING` en otro caso. No toca el índice HNSW ni otras migraciones.
- **Motivo**: es un atributo del documento; LAB-25 lo leerá tal cual. Una columna evita un join y es transaccional con
  el guardado del vector.
- **Descartadas**: tabla de estado aparte (complejidad sin beneficio); derivar el estado en consulta de
  «¿todos los chunks tienen vector?» (no puede representar `ERROR` ni su causa).

## D-4. Cálculo fuera de la transacción, guardado atómico con comprobación optimista

- **Decisión**: por documento: (1) leer sus chunks y su `updated_at` en una consulta corta; (2) calcular los vectores en
  lotes sin transacción abierta; (3) en una transacción corta, bloquear la fila del documento (`SELECT ... FOR UPDATE`),
  comprobar que `updated_at` no cambió y actualizar todos los chunks y el estado a `INDEXED`. Si `updated_at` cambió
  (reingesta concurrente) el resultado se descarta y el documento sigue `PENDING`. Si el cálculo falla, una transacción
  breve marca `ERROR` con la causa; los vectores previos no se tocan.
- **Motivo**: no retiene una conexión de base de datos durante llamadas lentas al modelo; da el todo o nada por documento
  (FR-006, FR-013) y descarta vectores de chunks reemplazados (FR-016, caso límite de reingesta). La reingesta de LAB-20
  actualiza `updated_at` y borra/reinserta chunks, así que la comprobación es suficiente.
- **Descartadas**: transacción única alrededor de la llamada al modelo (conexión retenida segundos o minutos);
  bloqueo consultivo de larga duración; versión de contenido con hash (más código que `updated_at`).

## D-5. Una sola ejecución a la vez dentro del proceso

- **Decisión**: los casos de uso de indexar y reindexar comparten un cerrojo no bloqueante en memoria
  (`AtomicBoolean`). Si ya hay una ejecución en curso, la nueva se omite y lo registra (indexar) o devuelve un resumen
  «omitida» (reindexar explícito). La comprobación optimista de D-4 mantiene la consistencia aunque se salte el cerrojo.
- **Motivo**: hay una sola instancia del monolito hasta F5 (Constitución I); evita calcular dos veces el mismo documento
  (caso límite «ejecuciones simultáneas») sin infraestructura. Al extraer el módulo se sustituirá por un cerrojo en base
  de datos (`FOR UPDATE SKIP LOCKED`), anotado en ADR-0017.
- **Descartadas**: `pg_advisory_lock` desde ya (más SQL y conexiones retenidas para un caso que hoy no existe).

## D-6. Disparo: evento tras la ingesta y barrido al arrancar

- **Decisión**: `IngestDocumentUseCase` publica un `DocumentIngestedEvent` en proceso al guardar; `IndexAfterIngestListener`
  lo recibe y lanza `index` sin esperar su resultado (el error se registra, el documento queda `ERROR`). Al arrancar, un
  `ApplicationRunner` indexa los documentos `PENDING` (recupera ingestas interrumpidas, sin tocar los `ERROR`) y, si
  `shoplab.retrieval.embedding.reindex-on-startup` vale `true` (o `force`), ejecuta el reindexado correspondiente.
- **Motivo**: la ingesta no se acopla al servidor de modelos (si cae, el documento igualmente se guarda; supuesto del
  spec) y el reindexado queda disponible sin REST: cambiar la variable de modelo y reiniciar con
  `reindex-on-startup=true` es el «comando» de reindexado; además el caso de uso es invocable desde pruebas y desde
  LAB-25.
- **Descartadas**: encadenar el cálculo dentro de la ingesta (la hace depender del servidor de modelos); `@Scheduled`
  periódico (un reintento automático de `ERROR` repetiría fallos indefinidamente); un endpoint REST (es LAB-25).

## D-7. Reindexado: tres ámbitos sobre un único algoritmo

- **Decisión**: `IndexScope` = `PENDING` (documentos `PENDING`; con reintento, también `ERROR`), `OTHER_MODEL`
  (además, los que tienen chunks con `embedding_model` distinto del configurado) y `ALL` (fuerza todo). `IndexDocumentsUseCase`
  usa `PENDING`; `ReindexDocumentsUseCase` usa `OTHER_MODEL` por defecto o `ALL` con `force`. Ambos comparten la rutina
  «calcular y guardar un documento» (D-4) y devuelven un `IndexingSummary` (documentos y chunks procesados, fallidos con
  causa, omitidos).
- **Motivo**: completar pendientes y cambiar de modelo son el mismo problema; un algoritmo evita divergencias. Como cada
  documento se sustituye entero y de una vez, nunca mezcla modelos (FR-013) y, mientras tanto, la búsqueda filtra por
  modelo (D-8).
- **Descartadas**: reindexar chunk a chunk (permite mezclar modelos dentro de un documento).

## D-8. Búsqueda vectorial mínima filtrada por modelo

- **Decisión**: `SearchChunksUseCase` rechaza consultas vacías sin llamar al modelo, calcula el embedding con
  `EmbeddingPort` y consulta
  `WHERE c.embedding IS NOT NULL AND c.embedding_model = :model ORDER BY c.embedding <=> :query::public.vector LIMIT :limit`
  (coseno, índice HNSW de LAB-19), devolviendo texto, documento, sección, posición y puntuación (`1 - distancia`). El límite
  por defecto es 5 y es configurable. Detalle en [contracts/embedding-indexing.md](contracts/embedding-indexing.md).
- **Motivo**: es exactamente lo que piden los criterios; filtros por metadatos, híbrida y citas son historias
  posteriores (FR-020).
- **Riesgo conocido**: un filtro por modelo después del recorrido HNSW puede devolver menos filas que el límite cuando
  conviven modelos. Con el corpus actual (cientos de chunks) es irrelevante; si crece se activa el escaneo iterativo de
  pgvector (`hnsw.iterative_scan`) o un índice parcial por modelo. Se anota en ADR-0017, sin implementarlo.
- **Descartadas**: búsqueda híbrida ahora; mapear la búsqueda a un endpoint (LAB-25 y siguientes).

## D-9. Configuración y despliegue

- **Decisión**: propiedades `shoplab.retrieval.embedding.{batch-size=16, search-limit=5, reindex-on-startup=false}` y la
  del modelo de D-2; el tiempo de espera lo da el cliente de Ollama de Spring AI. En local, `infra/docker-compose.yml` ya
  levanta Ollama y descarga `bge-m3`: sin cambios. En `shoplab-infra`, `OLLAMA_EMBED_MODEL` existe en la configuración de
  Ollama; hay que comprobar que el `ConfigMap` del backend también la reciba (si no, el valor por defecto `bge-m3`
  coincide y no es bloqueante). Cualquier cambio se hace en una rama y PR propios de ese repositorio, dentro de esta
  historia.
- **Motivo**: cambiar de modelo debe ser solo configuración (SC-003).

## D-10. Errores tipados

- **Decisión**: cuatro códigos nuevos en `ResponseCodeEnum` (formato `SLHD_RETR_NNNNN`):
  `EMBEDDING_SERVICE_UNAVAILABLE` (`00006`, 503: sin conexión o tiempo de espera), `EMBEDDING_SERVICE_FAILED` (`00007`,
  502: error del modelo, incluido texto demasiado largo), `EMBEDDING_DIMENSION_MISMATCH` (`00008`, 502: el vector no
  tiene 1 024 valores) y `SEARCH_QUERY_INVALID` (`00009`, 422). La causa que se guarda en `index_error` es el mensaje del
  código, sin texto del documento.
- **Motivo**: distingue fallo técnico del modelo de entrada inválida (Constitución III) y permite que LAB-25 muestre la
  causa sin filtrar contenido.

## D-11. Observabilidad

- **Decisión**: `Timer retrieval.embedding.batch` (etiquetas `outcome` = `ok`/`failed`), contador
  `retrieval.embedding.documents` (etiqueta `outcome` = `indexed`/`failed`/`skipped`) y `Timer retrieval.search` en el
  mismo adaptador de métricas que usa LAB-20 (`MicrometerEmbeddingMetrics`, etiquetas de valores fijos). Los logs llevan
  identificadores y recuentos, nunca texto de chunks ni de consultas (Constitución VI).
