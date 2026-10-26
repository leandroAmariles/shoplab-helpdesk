# ADR-0010: Módulo abierto `shared` para errores y correlación comunes

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

LAB-12 añade el primer endpoint de negocio (`POST /api/chat`). El backend no tenía todavía infraestructura de
errores: la guía de desarrollo exige un único `GlobalExceptionHandler`, un único `ResponseCodeEnum` y excepciones
tipadas, y `retrieval`, `tools` y `tickets` los necesitarán igual que `chat`. Spring Modulith trata cada paquete de
primer nivel como un módulo, de modo que dónde se coloquen estas clases es una decisión de fronteras (ADR-0001,
ADR-0004).

## Decisión

Crear el módulo `shared` (`com.shoplab.helpdesk.shared`), declarado `@ApplicationModule(type = OPEN)`, con:

- `exception/`: `ResponseCodeEnum` (códigos `SLHD_XXXX_NNNNN`), `HelpdeskException` y sus dos subtipos
  `BusinessException` y `TechnicalException`, y `ExceptionUtil`.
- `infrastructure/in/rest/`: `GlobalExceptionHandler` (único lugar que traduce excepciones a HTTP, siempre en JSON),
  `ErrorResponse` y `RequestIdFilter` (identificador de correlación `X-Request-Id`).

`shared` no depende de ningún módulo de negocio; los demás módulos pueden usar su contenido. `ModularityTests` pasa
a esperar cinco módulos: `chat`, `retrieval`, `tools`, `tickets` y `shared`.

Alternativas descartadas:

- **Dejarlo dentro de `chat`**: los demás módulos tendrían que depender de `chat` para lanzar errores, invirtiendo
  la dependencia natural.
- **Paquete raíz `com.shoplab.helpdesk`**: no es un módulo, pero mezcla infraestructura con la clase `Application`
  y la frontera no la verifica nadie.
- **Un módulo cerrado con interfaces con nombre (`@NamedInterface`)**: más ceremonia de la que justifican cuatro
  clases estables y sin lógica de negocio.

## Consecuencias

- Cualquier módulo nuevo lanza `BusinessException`/`TechnicalException` con un código del catálogo único y no
  construye respuestas de error HTTP.
- El catálogo `ResponseCodeEnum` es compartido: añadir un código toca `shared`. Si crece mucho o se extrae algún
  módulo a servicio (F4/F5), se reparte por módulo en un ADR nuevo.
- Un módulo abierto no impone aislamiento hacia dentro: la disciplina de que `shared` no contenga lógica de negocio
  la mantiene la revisión de código, no `ModularityTests`.
- Los errores siempre se devuelven como `application/json`, incluso a clientes `text/event-stream`, porque la
  negociación de contenido respondería 406 a un cliente de streaming.
