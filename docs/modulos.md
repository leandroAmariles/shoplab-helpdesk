# Módulos del backend

El backend es un monolito modular (Spring Modulith, [ADR-0001](adr/0001-monolito-modular-primero.md)) dividido por
contexto de negocio. Cada módulo es dueño de sus datos y las reglas se verifican en CI
([ADR-0015](adr/0015-limites-de-contexto-y-repositorios.md)).

| Módulo | Contexto | Esquema | Migraciones |
|---|---|---|---|
| `chat` | Conversación con el asistente (streaming SSE) | `chat` | `db/migration/chat` |
| `retrieval` | Recuperación sobre la base de conocimiento | `retrieval` | `db/migration/retrieval` |
| `orders` | Pedidos | `orders` | `db/migration/orders` |
| `catalog` | Catálogo de productos | `catalog` | `db/migration/catalog` |
| `tickets` | Tickets de soporte | `tickets` | `db/migration/tickets` |
| `shared` | Errores y correlación comunes (módulo abierto, sin datos) | — | — |

## Estructura de un módulo

```text
<modulo>/
├── api/             lo que publica a otros módulos: herramientas del agente (@Tool) y tipos públicos
├── domain/          modelo, constantes, enums, excepciones; sin Spring AI, Kafka, JPA ni Reactor
├── application/     puertos y casos de uso; aquí sí se usan Mono y Flux
├── infrastructure/  adaptadores (REST, Ollama, JDBC, métricas); aísla lo bloqueante
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

Flyway ejecuta cada migración con `search_path` limitado al esquema del módulo, y la extensión `vector` vive en
`public`: una migración que use pgvector califica el tipo y la clase de operadores (`public.vector(1024)`,
`public.vector_cosine_ops`). El detalle del diseño está en `specs/LAB-19-retrieval-schema-ownership/`.
