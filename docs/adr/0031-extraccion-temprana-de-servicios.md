# ADR-0031: Extracción temprana de servicios

- Estado: aceptado
- Fecha: 2026-11-23
- Sustituye a: ADR-0001 (parcialmente, en el calendario de extracción), ADR-0015 (parcialmente, en el momento de repositorios por servicio)

## Contexto

ADR-0001 fijó como principio el monolito modular con Spring Modulith para las fases F0 a F3, prometiendo
que a partir de F4 los módulos se extraerían a servicios y los eventos pasarían a Kafka. Posteriormente,
ADR-0015 ratificó la soberanía de datos (un esquema PostgreSQL por módulo, sin claves foráneas cruzadas)
y estableció que desde F5 se aplicaría la política de un repositorio por servicio.

Al cierre de la fase F2 (S18), el backend modular ha crecido de forma marcadamente asimétrica:
- El módulo `chat` cuenta con ~5.600 líneas de código con streaming SSE, gestión de sesiones y feedback.
- El módulo `retrieval` cuenta con ~2.400 líneas con búsqueda híbrida, vectorización pgvector y gestión de documentos.
- El módulo `eval` cuenta con ~1.400 líneas con persistencia de corridas y APIs administrativas.
- Por el contrario, los módulos de negocio transaccional `orders`, `catalog` y `tickets` permanecen como
  paquetes prácticamente vacíos, sin tablas en base de datos ni lógica implementada.

Construir `catalog`, `orders` y `tickets` dentro del monolito durante F3 y F4 para luego tener que extraerlos
en F5 significaría pagar un doble coste de desarrollo, migración y refactorización. Además, mantener el monolito
mientras el volumen de código de `chat` y `retrieval` sigue escalando eleva el acoplamiento de despliegue
y retrasa el aprendizaje de la operativa distribuida.

*Nota de trazabilidad*: Esta decisión responde a la historia de Jira **LAB-128 (F5-21)** (titulada originalmente
en Jira como «ADR-0029»). Siguiendo la regla estricta de numeración secuencial e inmutable de este repositorio
(donde `0029` y `0030` fueron asignados previamente a LAB-32 y LAB-33), la decisión adopta formalmente el
número **ADR-0031**.

## Decisión

1. **Extracción temprana en S19–S24**: Extraer los módulos maduros (`retrieval`, `chat` y `eval`) a repositorios
   y servicios independientes a lo largo de los Sprints 19 a 24, inmediatamente tras la finalización de F2.
2. **Nacimiento directo de servicios en F3/F4**: Los módulos que hoy están vacíos no se construirán dentro del
   monolito. `catalog-service` nacerá directamente como microservicio independiente en F3; `order-service` y
   `ticket-service` nacerán como microservicios independientes en F4.
3. **Mapa de servicios de destino**: Se publica en `docs/mapa-servicios.md` la topología oficial de los 8 servicios
   del sistema: `chat-service`, `retrieval-service`, `eval-service`, `catalog-service`, `order-service`,
   `ticket-service`, `gateway` y `notification-service`. Para cada uno se fija su esquema PostgreSQL exclusivo,
   APIs expuestas, eventos Kafka y dependencias técnicas.
4. **Herramientas de agente por Model Context Protocol (MCP)**: Se traslada a la red la regla de encapsulamiento
   de `@Tool` en la `api` de ADR-0015. Los servicios dueños de datos (`catalog`, `order`, `ticket`, `retrieval`)
   exponen sus herramientas como servidores MCP sobre HTTP/SSE con esquemas JSON Schema; `chat-service` actúa
   como cliente MCP en red, sin importar dependencias ni lógica de dominio de otros servicios.
5. **Destino del módulo `shared` (ADR-0010) y plantilla de servicio**: Se prohíbe la creación de librerías comunes
   de Maven que contengan lógica o entidades de dominio (evitando el antipatrón de «monolito distribuido»). Los
   contratos se comparten exclusivamente a través del repositorio `shoplab-contracts` (LAB-108). La infraestructura
   técnica base (jerarquía de excepciones, filtro de correlación `X-Request-Id`, envoltorio de respuestas y logging
   seguro) se replica inicialmente en cada nuevo servicio a partir del arquetipo de servicio de **LAB-103**. Cada
   servicio gestiona sus propios códigos de error tipados (`<SERVICIO>_...`).
6. **Separación de evaluación**: El módulo Java `eval` se extrae al repositorio `shoplab-eval-service` para la
   persistencia inmutable de corridas y APIs de `admin-eval`. El repositorio `shoplab-eval` permanece dedicado a la
   herramienta de evaluación automática en Python con `uv` (RAGAS, DeepEval) y al dataset golden (`eval/golden.jsonl`).

## Consecuencias

- **Sustituye parcialmente a ADR-0001**: Queda sin efecto el calendario de esperar a F4 para iniciar la extracción;
  la descomposición se adelanta a S19 tras F2. F0–F2 se consolidan como las únicas fases operadas puramente como monolito.
- **Sustituye parcialmente a ADR-0015**: La política de repositorios independientes se anticipa desde S19 para los
  servicios extraídos y los nuevos, en lugar de esperar a F5. Se mantiene intacta la regla de soberanía de datos
  (un esquema por servicio, sin joins ni claves foráneas inter-servicio).
- **Habilitación de historias de plataforma**: Desbloquea de forma inmediata `LAB-108` (publicación de paquetes versionados
  en `shoplab-contracts`) y `LAB-103` (plantilla de servicio y workflows reutilizables).
- **Despliegues desacoplados**: Cada servicio extraído dispone de su propio pipeline de CI/CD, sus propias versiones
  gobernadas por `release-please` y su entrada independiente en el clúster `dev` de `shoplab-infra`.
- **Cero librerías comunes de dominio**: Los equipos y servicios interactúan únicamente a través de contratos de red
  (OpenAPI y MCP) y eventos tipados (Avro/Kafka), garantizando la total autonomía de evolución de cada microservicio.
