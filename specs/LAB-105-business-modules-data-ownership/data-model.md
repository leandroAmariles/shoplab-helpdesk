# Data Model: Módulos por negocio con dueño de datos

Esta historia no crea tablas de negocio; modela **propiedad de datos** y la estructura que la hace verificable.

## Módulos y esquemas

| Módulo | Esquema | Carpeta de migraciones | Posee datos hoy | Llega con |
|---|---|---|---|---|
| `chat` | `chat` | `db/migration/chat` | no (memoria de conversación después) | historia de memoria de F1 |
| `retrieval` | `retrieval` | `db/migration/retrieval` | no | LAB-19 (su texto pasa de «esquema kb» a «esquema retrieval») |
| `orders` | `orders` | `db/migration/orders` | no | LAB-17 |
| `catalog` | `catalog` | `db/migration/catalog` | no | LAB-17 |
| `tickets` | `tickets` | `db/migration/tickets` | no | historias de F3 |
| `shared` | — | — | no es dueño de datos | — |

> **Aclaración 2026-10-27**: el esquema del módulo `retrieval` se llama `retrieval` (opción A); LAB-19 ajusta su texto
> de «esquema kb». Módulo, esquema y carpeta coinciden siempre (FR-015), sin excepciones.

## Entidades de infraestructura

### `ModuleSchema`
- **name** (texto, obligatorio): nombre del módulo = nombre del esquema = último segmento de la carpeta de migraciones.
- Validación: `^[a-z][a-z0-9_]*$`; único entre todos los beans `ModuleSchema`; el nombre `public` está prohibido.
  Un nombre inválido es un error de programación del cableado y lanza `IllegalArgumentException`.
- Un bean por módulo con datos, declarado en `<modulo>/config`.

### `ModuleSchemaMigrator`
- Entrada: lista de `ModuleSchema` y un `DataSource`.
- Efecto: por cada módulo (orden alfabético) crea el esquema si falta y aplica sus migraciones con su propio historial
  `<name>.flyway_schema_history`.
- Fallo: si una migración falla, el arranque aborta indicando el módulo; las migraciones de módulos anteriores quedan
  aplicadas (cada una es independiente).
- Sin Postgres accesible o con una migración fallida, el arranque aborta con `ModuleMigrationException` (subtipo de
  `TechnicalException`, código `DATABASE_UNAVAILABLE` o `DATABASE_MIGRATION_FAILED`); el mensaje nombra el módulo
  afectado y conserva la causa original. No se traduce a HTTP: ocurre antes de que el servidor atienda peticiones.

## Reglas de integridad entre módulos

- Una referencia a un dato de otro módulo es un **identificador sin clave foránea** (p. ej. `customer_id`,
  `order_id`).
- La existencia del referido se comprueba en la capa `application` consultando la API del módulo dueño, nunca con
  `JOIN` ni FK.
- Un identificador puede quedar huérfano si el dueño borra el dato; el módulo referente lo trata como «no
  encontrado» (error tipado), no como corrupción.

## Reglas verificables (resumen)

| Regla | Dónde se verifica | Contrato |
|---|---|---|
| Sin FK ni referencias a esquema ajeno | Lint de SQL + catálogo de Postgres | [schema-isolation](contracts/schema-isolation.md) |
| Dominio sin Spring AI/Kafka/JPA/Reactor | ArchUnit D1 | [architecture-rules](contracts/architecture-rules.md) |
| Capas y paquetes permitidos | ArchUnit D2, D3 | ídem |
| `@Tool` solo en `api` | ArchUnit D4 | ídem |
| Límites entre módulos | `ApplicationModules.verify()` | [module-structure](contracts/module-structure.md) |
