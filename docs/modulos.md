# Módulos del backend

El backend es un monolito modular (Spring Modulith, [ADR-0001](adr/0001-monolito-modular-primero.md)) dividido por
contexto de negocio. Cada módulo es dueño de sus datos y las reglas se verifican en CI
([ADR-0015](adr/0015-limites-de-contexto-y-repositorios.md)). El mapa de extracción temprana hacia microservicios
se describe en [mapa-servicios.md](mapa-servicios.md) ([ADR-0031](adr/0031-extraccion-temprana-de-servicios.md)).

| Módulo | Contexto | Esquema | Migraciones |
|---|---|---|---|
| `chat` | Conversación con el asistente (streaming SSE) | `chat` | `db/migration/chat` |
| `retrieval` | Recuperación sobre la base de conocimiento | `retrieval` | `db/migration/retrieval` |
| `orders` | Pedidos | `orders` | `db/migration/orders` |
| `catalog` | Catálogo de productos | `catalog` | `db/migration/catalog` |
| `tickets` | Tickets de soporte | `tickets` | `db/migration/tickets` |
| `eval` | Historial de las ejecuciones de evaluación (LAB-31) y su lectura para la pantalla `admin-eval` (LAB-32, [`admin-eval.md`](admin-eval.md)) | `eval` | `db/migration/eval` |
| `shared` | Errores y correlación comunes (módulo abierto, sin datos) | — | — |

## Estructura de un módulo

```text
<modulo>/
├── api/             lo que publica a otros módulos: herramientas del agente (@Tool) y tipos públicos
├── domain/          modelo, constantes, enums, excepciones; sin Spring AI, Kafka, JPA ni Reactor
├── application/     puertos y casos de uso; aquí sí se usan Mono y Flux
├── infrastructure/  adaptadores (REST, gateway de modelos, JDBC, métricas); aísla lo bloqueante
└── config/          cableado, propiedades y el `ModuleSchema` del módulo
```

## Reglas que fallan en CI

- `domain` no importa Spring AI, Kafka, JPA ni Reactor; `application` no usa `infrastructure`; las `@Tool` solo viven
  en `api`; un módulo usa de otro únicamente su `api` (`ModuleArchitectureTests`, `ModularityTests`).
- Ningún script SQL de un módulo toca el esquema de otro, y ninguna clave foránea cruza esquemas
  (`SchemaIsolationTests`, `ModuleSchemasIntegrationTests`).
- Las referencias entre módulos son identificadores, nunca claves foráneas ni `JOIN`.

## Diagramas y fichas

Los diagramas (PlantUML) y las fichas de cada módulo están en [`modulos/`](modulos/). **Se generan desde el código**
y no se editan a mano:

```bash
cd backend && ./mvnw test -Dtest=ModularityTests   # regenera docs/modulos/ y hay que commitear el resultado
```

CI falla si `docs/modulos/` no coincide con lo que genera el código.

## Añadir un módulo o una tabla

1. Crear el paquete con sus capas y un `@ApplicationModule` en su `package-info.java`.
2. Declarar su esquema: un bean `ModuleSchema` en `<modulo>/config` y `db/migration/<modulo>/V1__baseline.sql`.
3. Las tablas nuevas van en una migración `V<n>__<descripcion>.sql` de ese módulo, solo sobre su propio esquema.
4. Regenerar `docs/modulos/` (comando de arriba).

## Datos de `retrieval`

`retrieval.document` (un archivo fuente del corpus) y `retrieval.chunk` (un fragmento con su vector de 1024
dimensiones, el modelo de embeddings que lo generó y metadatos JSONB) los crea
`db/migration/retrieval/V2__knowledge_base_tables.sql` (LAB-19). Solo `retrieval` los lee y escribe; el resto de
módulos guarda, como mucho, el `id` de un documento o chunk. `ingest` no tiene credenciales de base de datos: entrega
su salida a `retrieval`.

**Ingesta (LAB-20).** El caso de uso `IngestDocumentServicePort` de `retrieval` envía el documento (PDF o Markdown) a
`POST /ingest` del servicio `ingest` (`shoplab.retrieval.ingest.*`; contrato en `ingest/openapi.json`, ADR-0016) y
guarda el resultado en una transacción: una fila en `document` por origen (la reingesta conserva su `id` y reemplaza
sus chunks) y una por chunk en `chunk`. Los chunks se guardan **sin vector**: `V3__chunk_embedding_optional.sql` hace
opcionales `embedding` y `embedding_model` (siempre los dos a la vez) y la historia de embeddings (LAB-21) los completa;
mientras tanto no participan en la búsqueda, que filtra `embedding IS NOT NULL`.

**Embeddings (LAB-21, ADR-0017).** `retrieval` es el único que calcula embeddings, con el `EmbeddingModel` de Spring AI
detrás de `EmbeddingPort`; `ingest` no conoce ningún modelo. `V4__document_index_status.sql` añade a `document` el
estado de indexado (`PENDING`, `INDEXED`, `ERROR` con `index_error`). Tras una ingesta el documento queda `PENDING` y
`IndexDocumentsServicePort.indexPending(retryFailed)` calcula los vectores por lotes y los guarda por documento, todo o
nada, con el nombre del modelo real (`shoplab.retrieval.embedding.model-name` / `SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME`; el backend pide al gateway el nombre lógico `embeddings`). Para cambiar de
modelo se cambia esa variable y se arranca con `shoplab.retrieval.embedding.reindex-on-startup=true` (o `force`), o se
llama a `ReindexDocumentsServicePort.reindex(force)`; no se vuelve a llamar a `ingest`. `SearchChunksServicePort`
calcula el embedding de la consulta y busca solo entre chunks con vector del modelo configurado. Ajustes en
`shoplab.retrieval.embedding.*` (`batch-size`, `search-limit`, `auto-index`, `reindex-on-startup`).

**Consumo desde `chat` (LAB-22, ADR-0018).** `retrieval.api` publica `KnowledgeSearchApi` (y `KnowledgeChunk`), la búsqueda
por similitud entre los chunks del modelo configurado. `chat` es el único consumidor: la llama desde su adaptador de salida
`KnowledgeSearchAdapter` y la traduce a su propio `ContextFragment`; no accede a las tablas ni a clases internas de
`retrieval`.

**Memoria de la conversación (LAB-24, ADR-0020) y feedback de respuestas (LAB-33, ADR-0030).** El esquema `chat` tiene la tabla `conversation_turn` (una fila por turno:
pregunta, respuesta completa, `turn_id` UUID público, `delivered_documents` y `trace_id`, por `conversation_id`), que solo lee y escribe `chat` a través de `ConversationMemoryPort`, y la tabla `feedback` (`V3__answer_feedback.sql`, relación 1:1 con `conversation_turn` mediante `turn_ref UNIQUE`, valoración `UP`/`DOWN`, comentario opcional de hasta 500 caracteres y campos de promoción a caso golden `promoted_case_id`, `promoted_case_jsonl` y `promoted_at`), gestionada por `FeedbackStorePort` (`JdbcFeedbackAdapter`).
Contienen texto libre del cliente: no se registra ni se mide en logs o etiquetas de métricas, y todavía no tienen caducidad ni borrado.

**Citas (LAB-23, ADR-0019).** `KnowledgeChunk` lleva también `title`, el título legible del documento (columna
`document.title`, sin migración); `chat` lo entrega al cliente en el evento `sources` junto a la sección y el texto del
fragmento.

**Subida y lista de documentos (LAB-25, ADR-0021).** `retrieval` publica `POST /api/documents` (multipart, parte
`file`, hasta `shoplab.retrieval.upload.max-bytes` = 10 MiB) y `GET /api/documents` en `DocumentController`. La subida
reutiliza `IngestDocumentServicePort` (responde `201` si el documento es nuevo y `200` si reemplaza a uno del mismo
nombre) y los embeddings los dispara el evento de siempre; el documento queda `PENDING`. La lista sale de
`ListDocumentsServicePort` → `DocumentCatalogPort` (`JdbcDocumentCatalogAdapter`): estado, causa del último error y
chunks guardados por documento, el más reciente primero. Métricas `retrieval.upload{outcome}` y
`retrieval.documents{status}`. La pantalla es `/documentos` del frontend.

Flyway ejecuta cada migración con `search_path` limitado al esquema del módulo, y la extensión `vector` vive en
`public`: una migración que use pgvector califica el tipo y la clase de operadores (`public.vector(1024)`,
`public.vector_cosine_ops`). El detalle del diseño está en `specs/LAB-19-retrieval-schema-ownership/`.

## Datos de `eval`

`eval.eval_run` (una ejecución de la medición de recuperación o de calidad, con prompts y versiones, modelos, juez y
configuración de recuperación), `eval.eval_case` (una **revisión** de un caso del dataset golden) y `eval.eval_result` (lo que
un caso dio en una ejecución, con las puntuaciones en un `jsonb`) los crea `db/migration/eval/V2__evaluation_history.sql`
(LAB-31). Solo `eval` los lee y escribe. **La herramienta Python de `eval/` no tiene credenciales de base de datos**: entrega
cada ejecución entera a `POST /api/eval/runs` con un token (`shoplab.eval.persistence.token`, apagado por defecto) y el módulo la
guarda en una transacción, o no la guarda. Lee de `chat` solo lo que éste publica en su `api` (`AssistantSettingsApi`: la
configuración de recuperación y la versión servida de cada prompt). Nunca guarda el texto de una respuesta ni de un fragmento.
Decisión: [ADR-0028](adr/0028-persistencia-de-ejecuciones-de-evaluacion.md); cómo se usa y se lee, en
[`eval/README.md`](../eval/README.md).
