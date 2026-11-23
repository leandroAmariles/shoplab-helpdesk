# Research & Technical Decisions: ADR de extracción temprana y mapa de servicios

**Feature**: `LAB-128-early-extraction-service-map`  
**Date**: 2026-11-23  
**Status**: Completed  

Este documento recoge las alternativas evaluadas, las decisiones tomadas y la justificación técnica de los aspectos clave de la especificación funcional [spec.md](spec.md) para registrar la decisión arquitectónica de extracción temprana de servicios y el mapa de servicios de destino.

---

## R1: Numeración del ADR y trazabilidad histórica

### Contexto y problema
En el texto de la historia de usuario en Jira (`LAB-128`) se tituló la decisión como «ADR-0029 Extracción temprana de servicios». Sin embargo, al inspeccionar el repositorio en `docs/adr/`, se constata que:
- `0029` ya está asignado a [0029-lectura-del-historial-de-evaluacion.md](../../docs/adr/0029-lectura-del-historial-de-evaluacion.md) (LAB-32).
- `0030` ya está asignado a [0030-feedback-de-respuestas-y-promocion-a-golden.md](../../docs/adr/0030-feedback-de-respuestas-y-promocion-a-golden.md) (LAB-33).

Conforme a las reglas del repositorio ([docs/adr/README.md](../../docs/adr/README.md)), la numeración es estrictamente secuencial de 4 dígitos, un número nunca se reutiliza y no puede haber colisiones entre ADRs aceptados.

### Alternativas evaluadas
1. **Forzar `ADR-0029` renombrando los ADRs existentes**: Requeriría reescribir `0029`, `0030`, actualizar PRs, commits y referencias históricas ya fusionadas en `main`. Violación directa de la regla de inmutabilidad de ADRs aceptados.
2. **Asignar el siguiente número libre disponible (`ADR-0031`)**: Respeta el estándar inmutable del repositorio, añade `0031-extraccion-temprana-de-servicios.md` e incluye en su Contexto una nota explícita de equivalencia y trazabilidad con Jira LAB-128.

### Decisión
Se adopta la **Alternativa 2 (`ADR-0031`)**. El nuevo ADR se titulará:  
`docs/adr/0031-extraccion-temprana-de-servicios.md` con:
- Estado: `aceptado`
- Sustituye parcialmente a: `ADR-0001` y `ADR-0015`
- Nota explícita: Registra formalmente la decisión de Jira LAB-128 (F5-21).

En [0001-monolito-modular-primero.md](../../docs/adr/0001-monolito-modular-primero.md) y [0015-limites-de-contexto-y-repositorios.md](../../docs/adr/0015-limites-de-contexto-y-repositorios.md), el estado pasa a:  
`aceptado (parcialmente sustituido por ADR-0031)`, preservando íntegro su contexto histórico.

---

## R2: Calendario de extracción temprana (S19–S24) y nacimiento de servicios

### Contexto y problema
ADR-0001 estipulaba que las fases F0 a F3 se mantenían como un monolito modular con Spring Modulith, postergando la extracción a F4 (cuando llegasen los agentes y Kafka) o a F5 (ADR-0015, repositorios por servicio).
En el estado actual del proyecto (finalización de F2):
- El módulo `chat` cuenta con ~5.600 líneas de código y lógica compleja de streaming SSE, memoria y feedback.
- El módulo `retrieval` cuenta con ~2.400 líneas de ingesta, embeddings vectoriales y búsqueda sobre pgvector.
- El módulo `eval` cuenta con ~1.400 líneas de persistencia histórica y APIs administrativas.
- Los módulos `orders`, `catalog` y `tickets` son meros esqueletos de paquetes vacíos en `backend/src/main/java/com/shoplab/helpdesk/` sin tablas ni lógica.

Si se mantiene el plan original de construir `catalog`, `orders` y `tickets` dentro del monolito en F3/F4 para luego extraerlos en F5, el equipo asumiría un doble coste innecesario:
1. Diseñar migraciones Flyway monolíticas, cableado Modulith y eventos en proceso.
2. Inmediatamente después, extraer el código a repositorios separados, cambiar eventos locales por Kafka y adaptar accesos.

### Alternativas evaluadas
1. **Mantener el monolito hasta F5**: Seguir la letra estricta de ADR-0001 y ADR-0015. Genera deuda técnica y trabajo duplicado de refactorización masiva.
2. **Extracción temprana híbrida (S19–S24)**:
   - Extraer los módulos maduros (`retrieval`, `chat`, `eval`) a repositorios y servicios dedicados en la ventana de Sprints 19 a 24.
   - Hacer que los servicios vacíos (`catalog`, `order`, `ticket`) **nazcan directamente como microservicios independientes** en sus respectivas fases (F3 y F4), evitando su construcción monolítica.

### Decisión
Se adopta la **Alternativa 2**. Esta estrategia minimiza la refactorización, acelera el aprendizaje distribuido sobre módulos ya probados y estabilizados, y permite que las nuevas capacidades de negocio nazcan con arquitectura limpia y repositorios independientes.

---

## R3: Topología del mapa de servicios (8 servicios)

### Contexto y problema
La descomposición del sistema requiere un mapa unificado que fije sin ambigüedad la titularidad de los datos, las APIs, los eventos y las dependencias.

### Definición de los 8 servicios

| Servicio | Repositorio | Esquema DB | APIs expuestas | Eventos Kafka (Produce / Consume) | Dependencias clave |
|---|---|---|---|---|---|
| **`chat-service`** | `shoplab-chat-service` | `chat` | REST (`/api/chat/**`), SSE (`/api/chat`), Actuator | Produce: `chat.turn.completed`, `chat.feedback.submitted`<br>Consume: — | `retrieval-service`, `catalog-service`, `order-service`, `ticket-service` (vía MCP), LiteLLM, PostgreSQL (`chat`) |
| **`retrieval-service`** | `shoplab-retrieval-service` | `retrieval` | REST (`/api/retrieval/**`), Servidor MCP (`search_knowledge_base`), Actuator | Produce: `retrieval.document.indexed`<br>Consume: `ingest.document.parsed` | PostgreSQL (`retrieval` + pgvector), Ollama / LiteLLM (embeddings) |
| **`eval-service`** | `shoplab-eval-service` | `eval` | REST (`/api/eval/**`), Actuator | Produce: `eval.run.completed`<br>Consume: `eval.run.requested` | PostgreSQL (`eval`), consumido por `admin-eval` y runner Python |
| **`catalog-service`** | `shoplab-catalog-service` | `catalog` | REST (`/api/catalog/**`), Servidor MCP (`search_products`, `get_product_details`), Actuator | Produce: `catalog.product.updated`, `catalog.stock.changed`<br>Consume: — | PostgreSQL (`catalog`) |
| **`order-service`** | `shoplab-order-service` | `orders` | REST (`/api/orders/**`), Servidor MCP (`get_order_status`, `track_shipment`), Actuator | Produce: `orders.order.created`, `orders.order.updated`<br>Consume: — | PostgreSQL (`orders`) |
| **`ticket-service`** | `shoplab-ticket-service` | `tickets` | REST (`/api/tickets/**`), Servidor MCP (`create_ticket`, `get_ticket_status`), Actuator | Produce: `tickets.ticket.created`, `tickets.ticket.escalated`<br>Consume: `chat.turn.escalation` | PostgreSQL (`tickets`) |
| **`gateway`** | `shoplab-gateway` (o Spring Cloud Gateway / Envoy) | — (Stateless) | Reverse Proxy (`/api/**`), TLS, CORS, Routing | Produce: —<br>Consume: — | Enruta a los servicios de backend y frontend |
| **`notification-service`** | `shoplab-notification-service` | `notification` | REST interno (`/api/notifications/**`), Actuator | Produce: `notification.sent`<br>Consume: `orders.order.*`, `tickets.ticket.*` | Kafka broker, Mail / SMS Provider, PostgreSQL (`notification`) |

### Reglas de frontera y soberanía de datos
1. **Un esquema y base de datos por servicio**: Cero claves foráneas entre esquemas; cero queries con `JOIN` que crucen fronteras.
2. **Identificadores opacos**: Cualquier referencia cruzada se almacena únicamente como identificador escalar (`String` / `UUID`).

---

## R4: Protocolo MCP (Model Context Protocol) para herramientas de agente

### Contexto y problema
En el monolito modular (ADR-0015), las herramientas de agente se anotaban con `@Tool` en el subpaquete `api` de cada módulo (`orders/api`, `catalog/api`, `tickets/api`, `retrieval/api`) y Spring AI en el módulo `chat` las descubría y enlazaba por reflexión en memoria.
Al desacoplar los módulos en servicios independientes con repositorios separados:
- No es admisible que `chat-service` incluya como dependencia Maven el código de los otros servicios.
- No es deseable reinventar un protocolo propietario de RPC sobre REST que obligue a `chat` a implementar serialización, deserialización y metadatos ad-hoc para cada herramienta.

### Alternativas evaluadas
1. **REST RPC propietario (`POST /api/internal/tools/{toolName}`)**: Requiere diseñar y mantener un protocolo propio, versionar DTOs en `shoplab-contracts` para cada herramienta y acoplar el orquestador a contratos rígidos.
2. **gRPC / Protocol Buffers**: Eficiente a nivel binario, pero rígido para la definición dinámica de esquemas de herramientas LLM (JSON Schema) y con mayor coste operativo.
3. **Model Context Protocol (MCP)**: Estándar abierto respaldado por la industria diseñado específicamente para conectar modelos de lenguaje con fuentes de datos y herramientas estructuradas. Cada servicio expone sus capacidades como un servidor MCP con esquemas JSON Schema declarativos; el LLM en `chat-service` descubre e invoca herramientas dinámicamente sobre la red (HTTP/SSE o Stream).

### Decisión
Se adopta **Model Context Protocol (MCP)**.
- **Servicios dueños (`retrieval`, `catalog`, `order`, `ticket`)**: Actúan como **Servidores MCP**, exponiendo el catálogo de herramientas y ejecutando la lógica contra su base de datos.
- **`chat-service`**: Actúa como **Cliente MCP**, resolviendo las herramientas mediante conexiones remotas y transmitiendo la respuesta del servidor MCP al prompt del modelo.
- **Resiliencia**: Si un servidor MCP no responde o está en mantenimiento, `chat-service` captura el error con timeout, manteniendo la conversación fluida e informando amablemente al usuario de la degradación del canal específico.

---

## R5: Tratamiento del módulo `shared` (ADR-0010) y plantilla de servicio

### Contexto y problema
ADR-0010 definió el módulo `shared` en el monolito como un módulo abierto para alojar `ResponseCodeEnum`, excepciones (`BusinessException`, `TechnicalException`), `GlobalExceptionHandler` y `RequestIdFilter`.
En arquitecturas de microservicios, compartir librerías de código genera un acoplamiento encubierto que termina convirtiendo los servicios en un «monolito distribuido».

### Alternativas evaluadas
1. **Crear una librería Maven común (`shoplab-common-lib`)**: Se distribuye por GitHub Packages. Rápidamente acumula clases utilitarias, DTOs y lógica compartida, forzando despliegues coordinados.
2. **Sin librería compartida; copia desde la plantilla de servicio (`LAB-103`)**: No existe librería común en Maven. Los contratos de red se comparten mediante paquetes de contratos versionados generados exclusivamente desde especificaciones OpenAPI/Avro (`shoplab-contracts`, LAB-108). La infraestructura técnica base (manejo de excepciones, correlación, logging) se genera inicialmente como plantilla base desde `LAB-103`.

### Decisión
Se adopta la **Alternativa 2**. Se prohíbe tajantemente la creación de librerías comunes con lógica o entidades de dominio. Cada servicio es un proyecto autosuficiente que gestiona sus propios códigos de error (`<SERVICE>_...`) y adapta sus excepciones a su contexto.

---

## R6: Separación entre `eval-service` (Java) y `shoplab-eval` (Python)

### Contexto y problema
En el monorepo actual conviven dos elementos relacionados con la evaluación:
- El módulo `backend/eval` (Java): desarrollado en LAB-31 y LAB-32 para persistir ejecuciones y casos de evaluación en PostgreSQL (`eval.eval_run`, `eval.eval_case`), y exponer endpoints para la pantalla `admin-eval`.
- La carpeta `eval/` (Python): desarrollada en LAB-27 y sucesivas con `shoplab_eval`, RAGAS, DeepEval y el dataset golden (`golden.jsonl`).

### Decisión
Se delimitan formalmente dos repositorios con responsabilidades complementarias pero no solapadas:
1. **`shoplab-eval-service` (Java)**:
   - Extraído de `backend/eval`.
   - Titular único del esquema `eval` en PostgreSQL.
   - Expone la API REST consumida por la consola web `admin-eval` para consulta de métricas, historial y revisión de respuestas negativas para promoción.
2. **`shoplab-eval` (Python)**:
   - Repositorio independiente administrado con `uv`.
   - Contiene el dataset golden de referencia (`golden.jsonl`) y los tests de evaluación automática ejecutados en los workflows de CI.
   - En CI, tras correr las métricas RAGAS/DeepEval contra el backend, invoca la API REST de `shoplab-eval-service` para persistir los resultados históricos.
