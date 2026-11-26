# Feature Specification: LAB-113 catalog-service nace como servicio

**Feature Branch**: `feature/LAB-113-catalog-service`

**Created**: 2026-11-26

**Status**: Draft

**Input**: User description: "LAB-113 (F5-20) Como desarrollador, quiero el catálogo en su propio servicio desde el principio, para que la búsqueda de productos de F3 y las herramientas de F4 consuman su API y no un módulo del monolito. Criterios de aceptación: Repo `shoplab-catalog-service` creado desde la plantilla de LAB-103, con su base de datos y los datos de Olist del esquema `catalog` (productos, categorías, vendedores) migrados; API de búsqueda, precio y stock con su OpenAPI en `shoplab-contracts`; retrieval-service la usa para indexar productos (LAB-34) y la herramienta `searchProducts` (LAB-43) se publica en el servidor MCP de este servicio; El gateway de LAB-107 enruta `/api/catalog/**` al servicio; Desplegado en dev por el ApplicationSet de LAB-104. Depende de: LAB-103, LAB-104, LAB-106, LAB-107, LAB-59. Va antes de LAB-34 (búsqueda de productos), LAB-43 y LAB-57. Cambio por ADR-0029 (LAB-128 / formalmente ADR-0031): ya no se extrae en F5, nace como servicio al empezar F3. Publicar `catalog.product-changed` por outbox con Debezium pasa a LAB-57."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Repositorio, base de datos y modelo de catálogo de Olist (Priority: P1)

Como desarrollador del sistema ShopLab, quiero inicializar el repositorio `shoplab-catalog-service` a partir de la plantilla estándar con su propia base de datos PostgreSQL y los datos de Olist ya migrados, para contar con un servicio autónomo dueño exclusivo del dominio de catálogo e inventario.

**Why this priority**: Es el cimiento sobre el cual se construyen las APIs y herramientas. Sin repositorio operativo ni persistencia con datos reales de productos, ni `retrieval-service` (LAB-34) ni el asistente conversacional (LAB-43) pueden operar.

**Independent Test**: Clonar `shoplab-catalog-service`, ejecutar `./mvnw test` y levantar el servicio localmente contra una base PostgreSQL `catalog`. Verificar que Flyway aplica las migraciones y que la base cuenta con las categorías, vendedores y productos cargados con stock sintético determinista y precios de referencia calculados.

**Acceptance Scenarios**:

1. **Given** la plantilla `shoplab-service-template` (LAB-103), **When** se crea el repositorio `shoplab-catalog-service`, **Then** hereda los workflows reutilizables de CI/CD, configuración de calidad (Checkstyle, PMD, Qodana, JaCoCo ≥ 80%), Dockerfile multistage y versionado con `release-please`.
2. **Given** un servidor PostgreSQL, **When** arranca `catalog-service`, **Then** conecta a su base de datos propia `catalog` con credenciales exclusivas del rol `catalog`, sin permisos de acceso a esquemas de otros servicios (`chat`, `retrieval`, `eval`, `orders`).
3. **Given** las tablas del esquema `catalog` de Olist (`product_categories`, `products`, `sellers`), **When** se ejecuta la migración/carga inicial, **Then** se conservan los 32.951 productos y sus categorías, con stock sintético determinista (`olist-stock-v1`) y precio de referencia calculado a partir de ventas históricas.
4. **Given** una verificación de soberanía de datos (ADR-0015, ADR-0031), **When** se auditan las claves foráneas en la base de datos, **Then** 0 claves foráneas apuntan a tablas fuera del dominio `catalog` y ninguna consulta realiza `JOIN` con otros esquemas.

---

### User Story 2 - API REST de productos, categorías, precio y stock con contrato OpenAPI (Priority: P1)

Como consumidor del catálogo (`retrieval-service` para indexación de búsqueda semántica en LAB-34 o el frontend/backoffice), quiero una API REST pública documentada con OpenAPI en `shoplab-contracts`, para consultar productos, paginar el catálogo completo y verificar disponibilidad y precio en tiempo real.

**Why this priority**: Permite desacoplar a los consumidores del almacenamiento interno del catálogo. Es el contrato de red formal estipulado por la arquitectura (ADR-0031) que desbloquea directamente la historia LAB-34.

**Independent Test**: Ejecutar las pruebas de contrato de Spring Cloud Contract como productor en `catalog-service` contra la especificación `openapi/catalog/v1/catalog-api.yaml` de `shoplab-contracts`. Realizar peticiones HTTP a `GET /api/catalog/products` con filtros de paginación y `GET /api/catalog/products/{id}` validando la respuesta y esquemas JSON devueltos.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-contracts`, **When** se publica la versión que incluye `openapi/catalog/v1/catalog-api.yaml`, **Then** define los endpoints `GET /api/catalog/products`, `GET /api/catalog/products/{id}`, `GET /api/catalog/products/{id}/stock` y `GET /api/catalog/categories` con sus esquemas de respuesta y códigos de error tipados (`SLHD_CATALOG_*`).
2. **Given** `retrieval-service` indexando productos (LAB-34), **When** invoca `GET /api/catalog/products?page=0&size=100`, **Then** recibe una página ordenada determinísticamente con metadatos completos (ID, categoría en inglés/portugués, dimensiones, peso, precio de referencia y stock) y enlaces de paginación.
3. **Given** un ID de producto existente, **When** se solicita `GET /api/catalog/products/{id}`, **Then** devuelve status `200 OK` con la ficha técnica detallada, el precio de referencia y las unidades disponibles en inventario.
4. **Given** un ID de producto inexistente, **When** se solicita `GET /api/catalog/products/{id}`, **Then** devuelve status `404 Not Found` con código de error tipado `SLHD_CATALOG_00001` y cabecera de correlación `X-Request-Id`.
5. **Given** parámetros de consulta inválidos (ej. `size < 1` o `page < 0`), **When** se procesa la petición, **Then** devuelve status `422 Unprocessable Entity` con código `SLHD_CATALOG_00002` y detalle de validación.

---

### User Story 3 - Servidor MCP con herramientas de catálogo para el agente conversacional (Priority: P1)

Como agente conversacional de `chat-service` (en F4, historia LAB-43), quiero interactuar con el servidor Model Context Protocol (MCP) expuesto por `catalog-service` sobre HTTP/SSE, para invocar dinámicamente la herramienta `searchProducts` y consultar stock y precio sin acoplarme a la base de datos ni a librerías internas.

**Why this priority**: Cumple con el estándar de arquitectura fijado en ADR-0031 (punto 4) y la especificación de contrato MCP de LAB-128, permitiendo que el LLM ejecute tool-calling en red de forma reactiva y segura.

**Independent Test**: Enviar una petición JSON-RPC `tools/list` al endpoint `/mcp` de `catalog-service` y verificar la presencia de la herramienta `searchProducts` (o `search_products`). Ejecutar una llamada `tools/call` con parámetros de búsqueda y validar que el resultado contiene la información textual formateada de productos, precios y disponibilidad con `isError: false`.

**Acceptance Scenarios**:

1. **Given** un cliente MCP conectándose a `/mcp` (soporte SSE / JSON-RPC 2.0), **When** envía la petición `tools/list`, **Then** el servicio responde con el catálogo de herramientas disponibles, incluyendo `searchProducts` con su JSON Schema de argumentos (`query`, `category`, `in_stock_only`, `limit`).
2. **Given** una solicitud `tools/call` para `searchProducts` con término "reloj deportivo", **When** `catalog-service` ejecuta la búsqueda, **Then** responde en menos de 500 ms con una lista de productos coincidentes con precio de referencia y disponibilidad en stock.
3. **Given** una llamada `tools/call` para un producto sin stock o inexistente, **When** finaliza la ejecución, **Then** responde con `isError: false` y un mensaje claro para el LLM indicando la falta de disponibilidad o ausencia del producto en catálogo.
4. **Given** un fallo interno en la base de datos durante la ejecución de la herramienta MCP, **When** se procesa la llamada, **Then** responde con `isError: true`, código de error y sin exponer trazas internas ni credenciales.

---

### User Story 4 - Enrutamiento perimetral en shoplab-gateway (Priority: P2)

Como cliente externo o microservicio interno consumiendo a través de la infraestructura perimetral, quiero que el gateway enrute el prefijo `/api/catalog/**` hacia `catalog-service`, para acceder a la API de catálogo de manera unificada y transparente.

**Why this priority**: Habilita la entrada de tráfico de red hacia el nuevo servicio bajo la arquitectura de API Gateway estrangulador (ADR-0032, LAB-107).

**Independent Test**: Levantar `shoplab-gateway` configurando `SHOPLAB_GATEWAY_ROUTES_CATALOG_URI=http://localhost:8084` y enviar peticiones a `http://localhost:8080/api/catalog/products`. Comprobar que el gateway reenvía la petición al servicio, propaga `X-Request-Id` y `traceparent`, y devuelve la respuesta preservando cabeceras y códigos de estado.

**Acceptance Scenarios**:

1. **Given** la configuración de rutas de `shoplab-gateway`, **When** se define la ruta `catalog` con prefijo `/api/catalog`, **Then** cualquier petición a `/api/catalog/**` se redirige a la URI configurada en `SHOPLAB_GATEWAY_ROUTES_CATALOG_URI`.
2. **Given** una petición HTTP entrante con cabeceras de observabilidad (`X-Request-Id`, `traceparent`), **When** atraviesa el gateway, **Then** se propagan intactas a `catalog-service`.
3. **Given** `catalog-service` temporalmente no disponible, **When** entra una petición por `/api/catalog/**`, **Then** el gateway aplica el timeout configurado y responde `504 Gateway Timeout` o `503 Service Unavailable` sin degradar otras rutas activas (`/api/chat`, `/api/documents`, `/api/eval`).

---

### User Story 5 - Despliegue continuo en clúster dev mediante ApplicationSet (Priority: P2)

Como ingeniero de plataforma/DevOps, quiero que `catalog-service` se despliegue automáticamente en el entorno `dev` de Kubernetes mediante el ApplicationSet de Argo CD (LAB-104), para contar con observabilidad, políticas de red y gestión de secretos estandarizadas.

**Why this priority**: Garantiza que el servicio funcione en el entorno compartido de integración de acuerdo con los estándares de GitOps de la organización (ADR-0006, ADR-0008).

**Independent Test**: Ejecutar `kustomize build apps/catalog-service/overlays/dev` en `shoplab-infra`. Verificar que el ApplicationSet `shoplab-services-dev` detecta la nueva carpeta, sincroniza el Deployment, Service, ServiceMonitor, NetworkPolicy y ExternalSecret en el namespace `shoplab-dev`, y los health checks quedan en estado `Healthy`.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-infra`, **When** se crea la carpeta `apps/catalog-service/overlays/dev`, **Then** el ApplicationSet `shoplab-services-dev` genera automáticamente la aplicación Argo CD `catalog-service-dev`.
2. **Given** el Deployment de `catalog-service` en `dev`, **When** el pod arranca, **Then** sus sondas `livenessProbe` y `readinessProbe` apuntan a `/actuator/health/liveness` y `/actuator/health/readiness` respectivamente, reportando estado saludable.
3. **Given** las credenciales de base de datos en AWS Secrets Manager / LocalStack, **When** External Secrets sincroniza `catalog-service-db`, **Then** las credenciales se inyectan de forma segura sin exponer contraseñas en texto plano en los manifiestos de Git.
4. **Given** la NetworkPolicy del servicio, **When** se aplican las reglas en el clúster, **Then** solo el gateway, `retrieval-service` y `chat-service` tienen permiso de entrada al puerto 8080 de `catalog-service`, y solo `catalog-service` tiene acceso a su base de datos.

---

## Edge Cases

- **Paginación fuera de rango**: Si se solicita una página superior al total de páginas disponibles (`page > totalPages`), el servicio responde `200 OK` con una lista de elementos vacía y metadatos de paginación coherentes (`totalElements: 32951`, `content: []`).
- **Producto con stock 0**: Productos agotados deben ser listados en la API general con `stock: 0` y precio de referencia visible. La herramienta MCP `searchProducts` debe permitir filtrarlos si se pasa `in_stock_only: true`.
- **Búsqueda por texto con caracteres especiales**: Consultas que contengan comillas, caracteres Unicode, tildes o símbolos de puntuación no deben generar errores SQL ni excepciones no controladas; deben procesarse sanitizadas mediante consultas parametrizadas o índices trigram (`pg_trgm`).
- **Timeout en llamadas MCP desde chat-service**: Si una consulta de catálogo tarda más del umbral configurado (3 segundos), el cliente MCP en `chat-service` debe abortar la espera sin colapsar el streaming SSE del usuario, devolviendo un mensaje de degradación suave.
- **Arranque sin datos de Olist**: Si la base de datos se despliega limpia (ej. en un nuevo ambiente efímero), Flyway debe aplicar la estructura de tablas sin errores. Los endpoints de consulta deben responder `200 OK` con listas vacías en lugar de fallar con errores de tabla no encontrada.
- **Reinicio del pod durante indexación masiva (LAB-34)**: Paginación basada en orden determinista (`ORDER BY product_id ASC`) para asegurar que consumidores iterativos como `retrieval-service` puedan reanudar la lectura sin saltarse registros ni procesar duplicados.

---

## Requirements *(mandatory)*

### Functional Requirements

**Repositorio, arquitectura y persistencia**

- **FR-001**: Se MUST crear el repositorio `shoplab-catalog-service` a partir de la plantilla oficial `shoplab-service-template` (LAB-103) sin lógica de dominio monolítica previa (nace directamente como microservicio según ADR-0031).
- **FR-002**: El servicio MUST contar con su propia base de datos PostgreSQL `catalog` con un usuario/rol dedicado `catalog` y contraseña gestionada por secretos.
- **FR-003**: Se MUST aplicar la migración Flyway que define las tablas del dominio de catálogo (`product_categories`, `products`, `sellers`), respetando la convención de tipos de datos y restricciones del esquema de Olist establecido en LAB-17.
- **FR-004**: Se MUST disponer del script/procedimiento de carga idempotente de los datos de Olist para poblar categorías, vendedores y los ~32.951 productos con su stock sintético determinista (`olist-stock-v1`) y precio de referencia.
- **FR-005**: El servicio MUST NOT tener dependencias de compilación ni de base de datos cruzadas con otros microservicios (`chat-service`, `retrieval-service`, `eval-service`, `order-service`).

**API REST y contratos OpenAPI**

- **FR-006**: Se MUST publicar en `shoplab-contracts` el contrato OpenAPI normativo `openapi/catalog/v1/catalog-api.yaml` definiendo los endpoints de búsqueda, filtrado, detalle de producto y consulta de stock.
- **FR-007**: El servicio MUST verificar el contrato OpenAPI como productor mediante pruebas automatizadas de Spring Cloud Contract / rest-assured en su pipeline de CI.
- **FR-008**: `GET /api/catalog/products` MUST permitir filtrado por categoría (`category`), texto en título/categoría (`query`), disponibilidad en stock (`inStock`), con paginación (`page`, `size`) y ordenación determinista por `product_id`.
- **FR-009**: `GET /api/catalog/products/{id}` MUST devolver la información completa del producto (dimensiones, peso, categoría traducida, precio de referencia y unidades en stock).
- **FR-010**: `GET /api/catalog/products/{id}/stock` MUST ofrecer una consulta ligera de solo lectura para verificación rápida de stock y precio.
- **FR-011**: `GET /api/catalog/categories` MUST listar todas las categorías disponibles con su nombre en portugués e inglés.
- **FR-012**: Todas las respuestas de error MUST seguir el estándar corporativo `ErrorResponse` (`code`, `description`) con códigos tipados `SLHD_CATALOG_*` y propagación de `X-Request-Id`.

**Servidor Model Context Protocol (MCP)**

- **FR-013**: El servicio MUST implementar un servidor MCP conforme a la especificación de LAB-128 exponiendo transporte HTTP/SSE (`GET /mcp/sse` y `POST /mcp/messages` o `/mcp`).
- **FR-014**: El servidor MCP MUST exponer la herramienta `searchProducts` (y su alias `search_products`), permitiendo consultar productos, precios y disponibilidad con esquemas JSON Schema validados.
- **FR-015**: El servidor MCP MUST exponer la herramienta `getProductDetails` (y su alias `get_product_details`) para consulta directa de ficha técnica y stock por ID.
- **FR-016**: La respuesta de las herramientas MCP MUST estructurarse en formato de contenido textual descriptivo (`content: [{type: "text", text: "..."}]`) diseñado para interpretación directa por LLMs.

**Integración perimetral y despliegue**

- **FR-017**: `shoplab-gateway` (LAB-107) MUST configurar la ruta con prefijo `/api/catalog` enrutando hacia `catalog-service:8080`, propagando cabeceras de trazabilidad distribuidas.
- **FR-018**: Se MUST crear la configuración de despliegue en `shoplab-infra/apps/catalog-service/` para que el ApplicationSet de Argo CD (LAB-104) despliegue automáticamente el servicio en el namespace `shoplab-dev`.
- **FR-019**: El servicio MUST exponer endpoints de observabilidad `/actuator/health` (con sondas de liveness y readiness) y `/actuator/prometheus`.
- **FR-020**: El entorno local multi-repo (LAB-110) MUST incluir `catalog-service` en su Docker Compose con su base de datos propia.

**Fuera de alcance de esta historia**

- **FR-021**: La indexación semántica y vectorización de productos en pgvector pertenece a `retrieval-service` (LAB-34).
- **FR-022**: La orquestación del cliente MCP y el prompt de agente conversacional en tiempo de ejecución pertenece a `chat-service` (LAB-43).
- **FR-023**: La publicación asíncrona de eventos de cambio de catálogo (`catalog.product-changed`) mediante el patrón Outbox transaccional y Debezium/Kafka queda diferida a LAB-57 (según enmienda de ADR-0031 / LAB-128).
- **FR-024**: Funcionalidades de modificación transaccional de stock por compras o reservas corresponden a `order-service` en F4.

---

### Key Entities

- **Product**: Ficha de producto en catálogo (`product_id`, `category_name`, dimensiones, peso, `reference_price`, `stock`).
- **ProductCategory**: Categoría de producto con nombre original en portugués y traducción en inglés (`name`, `name_en`).
- **Seller**: Identificador y ubicación del vendedor del marketplace Olist (`seller_id`, ciudad, estado, código postal).
- **ProductCatalogPage**: Envoltorio de paginación que agrupa lista de productos, total de elementos, número de página y total de páginas.
- **MCPToolDefinition**: Declaración formal de la herramienta expuesta al agente de IA con su nombre, descripción y JSON Schema de parámetros.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El repositorio `shoplab-catalog-service` compila y pasa todas sus pruebas unitarias, de integración y de arquitectura con cobertura de código JaCoCo ≥ 80%.
- **SC-002**: Las 3 tablas de catálogo (`products`, `product_categories`, `sellers`) se crean correctamente en PostgreSQL mediante Flyway y contienen los 32.951 productos con 100% de consistencia en stock y precios.
- **SC-003**: El contrato OpenAPI publicado en `shoplab-contracts` es verificado exitosamente por el pipeline de `catalog-service` sin discrepancias de esquema.
- **SC-004**: `retrieval-service` (LAB-34) puede consultar de forma secuencial y paginada el 100% del catálogo a través de `GET /api/catalog/products` a una velocidad ≥ 200 productos/segundo en entorno local.
- **SC-005**: El endpoint MCP `/mcp` responde a `tools/list` y ejecuta `searchProducts` en menos de 500 ms devolviendo datos precisos de stock y precio.
- **SC-006**: El gateway de LAB-107 enruta peticiones a `/api/catalog/**` hacia `catalog-service` con 0 errores de reenvío y propagando la cabecera `X-Request-Id`.
- **SC-007**: El ApplicationSet de dev en `shoplab-infra` sincroniza la aplicación `catalog-service-dev` quedando en estado `Synced` y `Healthy` en Argo CD.
- **SC-008**: 0 líneas de código de catálogo añadidas al monolito modular `shoplab-helpdesk`, validando el cumplimiento estricto de ADR-0031.

---

## Assumptions

- **Nacimiento directo**: Siguiendo la decisión de ADR-0031 (LAB-128), el módulo de catálogo no se desarrolla en el monolito modular; `shoplab-catalog-service` nace directamente como un microservicio independiente.
- **Stock sintético**: Debido a que el dataset público de Olist no incluye existencias de inventario, se asume la fórmula determinista `olist-stock-v1` establecida en LAB-17 (1 de cada 10 productos con stock 0 y el resto entre 1 y 200).
- **Precio de referencia**: El precio de cada producto corresponde a la mediana de ventas históricas calculada a partir de los ítems vendidos en el dataset de Olist.
- **Sin autenticación de usuario final**: Al igual que los demás servicios en fase dev actual (ADR-0024), las APIs de lectura de catálogo son públicas y la seguridad perimetral se delega al gateway y NetworkPolicies.
- **Soberanía estricta**: Ninguna tabla de `catalog` referenciará claves foráneas hacia `orders` o clientes.

---

## Dependencies

- **LAB-103**: Plantilla de servicio (`shoplab-service-template`) y workflows reutilizables de GitHub Actions.
- **LAB-104**: ApplicationSet de Argo CD (`shoplab-infra`) para despliegue automatizado en `shoplab-dev`.
- **LAB-106 / LAB-108**: Estructura de contratos OpenAPI en `shoplab-contracts` y publicación de artefactos.
- **LAB-107**: API Gateway estrangulador (`shoplab-gateway`) para enrutamiento perimetral.
- **LAB-59**: Extracción previa de `chat-service` (estabilización de la arquitectura distribuida).
- **Bloquea a**: LAB-34 (indexación RAG de catálogo), LAB-43 (herramienta MCP para chat) y LAB-57 (outbox Debezium).
