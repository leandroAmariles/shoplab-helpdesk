# Contract Specification: Service Map Schema (Topología de 8 Servicios)

**Feature**: `LAB-128-early-extraction-service-map`  
**Date**: 2026-11-23  
**Status**: Draft  

Este contrato formaliza la especificación técnica de los 8 servicios que conforman el mapa de servicios de destino de ShopLab Helpdesk, requerido en Jira LAB-128 para documentarse en `docs/mapa-servicios.md`.

---

## 1. `chat-service` (Orquestador conversacional)

- **Repositorio**: `shoplab-chat-service`
- **Contexto**: Interacción con el usuario final mediante chat en streaming, memoria de sesión, orquestación de prompts y ejecución de herramientas de agente.
- **Esquema de base de datos**: `chat` en PostgreSQL (tablas `conversation`, `conversation_turn`, `feedback`).
- **APIs expuestas**:
  - `POST /api/chat`: Endpoint reactivo con Server-Sent Events (SSE) para streaming de respuestas.
  - `PUT /api/chat/feedback/{turnId}`: Registro de feedback 👍 / 👎 con comentario.
  - `GET /api/chat/feedback`: Consulta administrativa de feedbacks.
  - `GET /actuator/health`, `GET /actuator/prometheus`: Observabilidad y métricas.
- **Eventos Kafka**:
  - *Publica*:
    - `chat.turn.completed` (Avro / CloudEvent): Emitido al concluir un turno satisfactoriamente.
    - `chat.feedback.submitted` (Avro / CloudEvent): Emitido al registrarse una valoración.
  - *Consume*: Ninguno.
- **Dependencias**:
  - `retrieval-service` (vía HTTP / MCP).
  - `catalog-service`, `order-service`, `ticket-service` (vía cliente MCP en red).
  - Pasarela de modelos LiteLLM (vía OpenAI-compatible API).
  - PostgreSQL (esquema `chat`).

---

## 2. `retrieval-service` (Recuperación y búsqueda semántica)

- **Repositorio**: `shoplab-retrieval-service`
- **Contexto**: Ingesta interna, particionado, cálculo de embeddings y búsqueda híbrida (vectorial HNSW + BM25/full-text) sobre el corpus documental.
- **Esquema de base de datos**: `retrieval` en PostgreSQL (tablas `document`, `chunk` con extensión `pgvector`).
- **APIs expuestas**:
  - `POST /api/retrieval/search`: Búsqueda de fragmentos por similitud semántica e híbrida.
  - `POST /api/retrieval/documents`: Registro y carga de documentos de conocimiento.
  - `GET /api/retrieval/documents`: Listado y administración del corpus documental.
  - *Servidor MCP*: Herramienta `search_knowledge_base`.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `retrieval.document.indexed` (documento indexado y vectorizado).
  - *Consume*: `ingest.document.parsed` (documento procesado desde la herramienta de ingesta).
- **Dependencias**:
  - PostgreSQL (esquema `retrieval` + pgvector).
  - LiteLLM / Ollama (para generación de embeddings vectoriales).

---

## 3. `eval-service` (Gestión histórica de evaluación RAG)

- **Repositorio**: `shoplab-eval-service`
- **Contexto**: Registro inmutable de corridas de evaluación, métricas RAGAS/DeepEval y exposición de datos para la pantalla administrativa `admin-eval`.
- **Esquema de base de datos**: `eval` en PostgreSQL (tablas `eval_run`, `eval_case`).
- **APIs expuestas**:
  - `POST /api/eval/runs`: Registro de los resultados de una nueva corrida de evaluación ejecutada en CI.
  - `GET /api/eval/runs`: Listado histórico de corridas y evolución temporal de métricas.
  - `GET /api/eval/runs/{runId}`: Detalle de casos y métricas por pregunta.
  - `POST /api/eval/cases/promote`: Promoción de feedback negativo a caso golden.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `eval.run.completed` (corrida persistida y consolidada).
  - *Consume*: `eval.run.requested` (opcional, para disparar evaluación asíncrona).
- **Dependencias**:
  - PostgreSQL (esquema `eval`).
  - Consumido por el frontend Angular (`/admin-eval`) y por el CLI Python `shoplab-eval`.

---

## 4. `catalog-service` (Catálogo de productos e inventario)

- **Repositorio**: `shoplab-catalog-service`
- **Contexto**: Gestión de fichas de producto, categorías de la tienda Olist y disponibilidad de stock. Nace directamente como microservicio en F3.
- **Esquema de base de datos**: `catalog` en PostgreSQL (tablas `product`, `category`, `inventory`).
- **APIs expuestas**:
  - `GET /api/catalog/products`: Búsqueda y filtrado de productos con paginación.
  - `GET /api/catalog/products/{id}`: Detalle de producto.
  - *Servidor MCP*: Herramientas `search_products`, `get_product_details`.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `catalog.product.updated`, `catalog.stock.changed`.
  - *Consume*: Ninguno.
- **Dependencias**:
  - PostgreSQL (esquema `catalog`).

---

## 5. `order-service` (Gestión de pedidos y envíos)

- **Repositorio**: `shoplab-order-service`
- **Contexto**: Ciclo de vida de pedidos, líneas de compra, seguimiento logístico y estados de entrega. Nace directamente como microservicio en F4.
- **Esquema de base de datos**: `orders` en PostgreSQL (tablas `customer_order`, `order_item`, `shipment`).
- **APIs expuestas**:
  - `GET /api/orders/{id}`: Consulta de orden por identificador.
  - `GET /api/orders/{id}/tracking`: Historial de rastreo de envíos.
  - *Servidor MCP*: Herramientas `get_order_status`, `track_shipment`.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `orders.order.created`, `orders.order.shipped`, `orders.order.delivered`.
  - *Consume*: Ninguno.
- **Dependencias**:
  - PostgreSQL (esquema `orders`).

---

## 6. `ticket-service` (Mesa de ayuda e incidencias)

- **Repositorio**: `shoplab-ticket-service`
- **Contexto**: Creación, escalado, asignación y seguimiento de tickets de soporte cuando el bot no resuelve la necesidad del cliente. Nace directamente como microservicio en F4.
- **Esquema de base de datos**: `tickets` en PostgreSQL (tablas `support_ticket`, `ticket_message`).
- **APIs expuestas**:
  - `POST /api/tickets`: Creación de un nuevo ticket de soporte.
  - `GET /api/tickets/{id}`: Consulta de estado e hilo de mensajes.
  - `POST /api/tickets/{id}/messages`: Adición de respuestas por agente o cliente.
  - *Servidor MCP*: Herramientas `create_ticket`, `get_ticket_status`.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `tickets.ticket.created`, `tickets.ticket.escalated`, `tickets.ticket.resolved`.
  - *Consume*: `chat.turn.escalation` (solicitud de apertura de ticket originada en el bot).
- **Dependencias**:
  - PostgreSQL (esquema `tickets`).

---

## 7. `gateway` (API Gateway perimetral)

- **Repositorio**: `shoplab-gateway` (o componente de plataforma Spring Cloud Gateway / Envoy en `shoplab-infra`)
- **Contexto**: Puerta de enlace perimetral única de red. Gestiona enrutamiento, terminación TLS, CORS y propagación de cabeceras W3C `traceparent` y correlación `X-Request-Id`.
- **Esquema de base de datos**: Ninguno (*stateless*).
- **APIs expuestas**:
  - `/api/chat/**` -> Reenvío a `chat-service`.
  - `/api/retrieval/**` -> Reenvío a `retrieval-service`.
  - `/api/eval/**` -> Reenvío a `eval-service`.
  - `/api/catalog/**` -> Reenvío a `catalog-service`.
  - `/api/orders/**` -> Reenvío a `order-service`.
  - `/api/tickets/**` -> Reenvío a `ticket-service`.
  - `/` -> Frontend estático Angular.
- **Eventos Kafka**: Ninguno.
- **Dependencias**:
  - Servicios de backend (enrutamiento de red en el clúster `dev`).

---

## 8. `notification-service` (Notificaciones asíncronas)

- **Repositorio**: `shoplab-notification-service`
- **Contexto**: Envío de correos electrónicos, SMS o alertas transaccionales a clientes ante eventos de negocio del ecosistema.
- **Esquema de base de datos**: `notification` en PostgreSQL (tabla `notification_log` para idempotencia y auditoría).
- **APIs expuestas**:
  - `GET /api/notifications/status/{id}`: Consulta de entrega de alerta.
  - `GET /actuator/health`, `GET /actuator/prometheus`.
- **Eventos Kafka**:
  - *Publica*: `notification.delivery.sent`, `notification.delivery.failed`.
  - *Consume*: `orders.order.created`, `orders.order.shipped`, `tickets.ticket.created`, `tickets.ticket.resolved`.
- **Dependencias**:
  - Broker de eventos Kafka.
  - Proveedor externo de correo/mensajería (Mock / Mailhog en local).
  - PostgreSQL (esquema `notification`).
