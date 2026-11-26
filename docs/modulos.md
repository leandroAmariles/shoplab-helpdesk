# Módulos del backend

El backend es un monolito modular (Spring Modulith, [ADR-0001](adr/0001-monolito-modular-primero.md)) dividido por
contexto de negocio. Cada módulo es dueño de sus datos y las reglas se verifican en CI

([ADR-0015](adr/0015-limites-de-contexto-y-repositorios.md)). El mapa de extracción temprana hacia microservicios
se describe en [mapa-servicios.md](mapa-servicios.md) ([ADR-0031](adr/0031-extraccion-temprana-de-servicios.md)).


| Módulo | Contexto | Esquema | Migraciones |
|---|---|---|---|
| `chat` | Conversación con el asistente (streaming SSE) | `chat` | `db/migration/chat` |
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

## Base de conocimiento (fuera del monolito)

Desde LAB-129 la base de conocimiento vive en `shoplab-retrieval-service`, con su propia base de datos
([ADR-0033](adr/0033-extraccion-de-retrieval-service.md)): la ingesta, los embeddings, la búsqueda y
`/api/documents` son suyos. `chat` la consulta por HTTP desde `HttpKnowledgeSearchClient`, con el cliente generado de
`shoplab-contracts`, y traduce la respuesta a su propio `ContextFragment`
(`SHOPLAB_RETRIEVAL_CLIENT_BASE_URL`, `SHOPLAB_RETRIEVAL_CLIENT_TIMEOUT`). El esquema `retrieval` de la base del
monolito quedó sin uso.

## Datos de `chat`

**Memoria de la conversación (LAB-24, ADR-0020) y feedback de respuestas (LAB-33, ADR-0030).** El esquema `chat` tiene la tabla `conversation_turn` (una fila por turno:
pregunta, respuesta completa, `turn_id` UUID público, `delivered_documents` y `trace_id`, por `conversation_id`), que solo lee y escribe `chat` a través de `ConversationMemoryPort`, y la tabla `feedback` (`V3__answer_feedback.sql`, relación 1:1 con `conversation_turn` mediante `turn_ref UNIQUE`, valoración `UP`/`DOWN`, comentario opcional de hasta 500 caracteres y campos de promoción a caso golden `promoted_case_id`, `promoted_case_jsonl` y `promoted_at`), gestionada por `FeedbackStorePort` (`JdbcFeedbackAdapter`).
Contienen texto libre del cliente: no se registra ni se mide en logs o etiquetas de métricas, y todavía no tienen caducidad ni borrado.

**Citas (LAB-23, ADR-0019).** El título legible del documento llega con cada resultado de la búsqueda; `chat` lo
entrega al cliente en el evento `sources` junto a la sección y el texto del fragmento.

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
