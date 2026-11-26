---
description: "Task list for LAB-113 catalog-service implementation"
---

# Tasks: LAB-113 catalog-service nace como servicio

**Input**: Design documents from `/specs/LAB-113-catalog-service/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Obligatorias (Constitución IV). Cada prueba nueva se escribe antes del código y se ve fallar; cobertura JaCoCo ≥ 80% requerida en el nuevo repositorio.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[USn]**: Historia de usuario de la especificación a la que pertenece la tarea
- **MANUAL**: Tarea realizada por la persona propietaria del entorno o credenciales
- **APROBACIÓN**: Publicación de artefactos externos, releases o cambios en infraestructura compartida
- **VERIFICAR**: Validación de supuestos arquitectónicos o precondiciones

**Rutas relativas**:
- Repositorio nuevo: `shoplab-catalog-service/` (con `SVC` = `shoplab-catalog-service/src/main/java/com/shoplab/catalog` y `SVCT` = `shoplab-catalog-service/src/test/java/com/shoplab/catalog`)
- Contratos: `shoplab-contracts/`
- Infraestructura: `shoplab-infra/`
- Gateway: `shoplab-gateway/`
- Monolito/Documentación: `shoplab-helpdesk/`

---

## Phase 1: Setup

- [x] T001 Crear la rama `feature/LAB-113-catalog-service` desde `origin/main` en `shoplab-contracts/`, `shoplab-infra/` y `shoplab-gateway/` — *gateway no necesita rama: ver T029*
- [x] T002 [P] VERIFICAR en `shoplab-service-template` que la plantilla de servicio compila, genera artefactos y posee los workflows reutilizables de CI/CD (`ci.yml`, `release.yml`, `release-please.yml`)
- [x] T003 [P] VERIFICAR en `shoplab-gateway/src/main/resources/application.yml` la estructura de rutas existentes (`/api/chat`, `/api/documents`, `/api/eval`) y los prefijos de configuración por variables de entorno
- [x] T004 [P] VERIFICAR en `shoplab-infra/apps/` el arquetipo `_template` para el alta de nuevos servicios bajo Argo CD ApplicationSet (`shoplab-services-dev`)
- [x] T005 [P] Inicializar el archivo de evidencias en `specs/LAB-113-catalog-service/validation.md`

---

## Phase 2: Foundational (Persistencia y Dependencias Base)

**⚠️ CRITICAL**: Base de datos y configuración compartida requerida antes de implementar la lógica de negocio.

- [x] T006 [P] Configurar el contenedor PostgreSQL en `shoplab-helpdesk/infra/docker-compose.yml` para crear el usuario y base de datos `catalog` mediante `infra/postgres/catalog-db-init.sh`
- [x] T007 [P] VERIFICAR que la resolución de dependencias de `shoplab-contracts` y GitHub Packages funciona en proyectos limpios con `.mvn/ci-settings.xml`

**Checkpoint**: Infraestructura local lista para el nuevo repositorio.

---

## Phase 3: User Story 1 - Repositorio, base de datos y modelo de catálogo de Olist (Priority: P1) 🎯 MVP

**Goal**: `shoplab-catalog-service` creado, con esquema `catalog` en PostgreSQL, migraciones Flyway y datos de Olist (32.951 productos) cargados con stock sintético determinista `olist-stock-v1` y precios calculados.

**Independent Test**: Levantar `shoplab-catalog-service` contra PostgreSQL local; verificar que Flyway aplica `V1__init_catalog_tables.sql` y las consultas SQL devuelven los 32.951 productos y sus 71 categorías.

### Repositorio y persistencia

- [x] T008 [US1] APROBACIÓN Crear el repositorio `shoplab-catalog-service` a partir de `shoplab-service-template` con GitHub CLI (`gh repo create`) — *creado privado; ajustes de merge sin aplicar*
- [ ] T009 [US1] MANUAL Configurar secretos de repositorio (`DEPLOY_APP_PRIVATE_KEY`, `RELEASE_PLEASE_TOKEN`) en `shoplab-catalog-service`
- [x] T010 [US1] Crear la migración Flyway `shoplab-catalog-service/src/main/resources/db/migration/catalog/V1__init_catalog_tables.sql` con las tablas `product_categories`, `products` y `sellers` según [data-model.md](data-model.md)
- [x] T011 [P] [US1] Copiar a `SVC/shared/` la infraestructura base no de negocio desde el arquetipo: `GlobalExceptionHandler`, `ErrorResponse`, `ResponseCodeEnum` (`SLHD_CATALOG_*`), `RequestIdFilter` y `DatabaseConfig`
- [x] T012 [P] [US1] Crear entidades de dominio `Product`, `ProductCategory` y `Seller` en `SVC/domain/`
- [x] T013 [US1] Implementar repositorios de persistencia R2DBC / JDBC en `SVC/infrastructure/persistence/` (`PostgresProductRepository`, `PostgresCategoryRepository`)
- [x] T014 [US1] Adaptar el script de carga de Olist (`shoplab-helpdesk/data/olist/`) o crear un loader Flyway/SQL reproducible para cargar las 71 categorías, 32.951 productos y 3.095 vendedores con stock sintético `olist-stock-v1` y mediana de precios — *copia desde shoplab.catalog con scripts/migrate-catalog.sh*
- [x] T015 [P] [US1] Test de integración con Testcontainers PostgreSQL en `SVCT/infrastructure/persistence/PostgresProductRepositoryTest.java` validando consultas y restricciones CHECK — *cubierto en CatalogApiIntegrationTest*

**Checkpoint**: Base de datos de catálogo operativa con datos reales de Olist y 0 dependencias cruzadas con el monolito.

---

## Phase 4: User Story 2 - API REST de productos, categorías, precio y stock con contrato OpenAPI (Priority: P1)

**Goal**: Exponer API REST documentada en `shoplab-contracts` para búsqueda, filtrado, paginación determinista (para `retrieval-service` en LAB-34) y consulta de stock.

**Independent Test**: Ejecutar pruebas de contrato productor Spring Cloud Contract en `shoplab-catalog-service` y verificar respuestas a `GET /api/catalog/products?page=0&size=50` y `GET /api/catalog/products/{id}`.

### Contrato y API REST

- [ ] T016 [US2] Redactar y publicar el contrato OpenAPI normativo en `shoplab-contracts/openapi/catalog/v1/catalog-api.yaml` definiendo esquemas para `ProductPageResponse`, `ProductDetailResponse`, `ProductStockResponse` y `CategoryResponse` — *redactado y compila; falta publicarlo*
- [ ] T017 [US2] APROBACIÓN Generar release de `shoplab-contracts` e importar la versión actualizada en `shoplab-catalog-service/pom.xml`
- [x] T018 [US2] Implementar `ProductService` y casos de uso en `SVC/application/` (`GetProductsPageUseCase`, `GetProductDetailUseCase`, `GetProductStockUseCase`) con ordenamiento determinista por `product_id`
- [x] T019 [US2] Implementar `ProductController` en `SVC/api/ProductController.java` con los endpoints `GET /api/catalog/products`, `GET /api/catalog/products/{id}` y `GET /api/catalog/products/{id}/stock`
- [x] T020 [P] [US2] Implementar `CategoryController` en `SVC/api/CategoryController.java` con el endpoint `GET /api/catalog/categories`
- [x] T021 [P] [US2] Configurar contratos Spring Cloud Contract en `shoplab-catalog-service/src/test/resources/contracts/catalog/` y verificar como productor con `WebTestClient`
- [x] T022 [P] [US2] Pruebas de integración en `SVCT/api/ProductControllerIntegrationTest.java` verificando paginación sin solapamiento, filtros de categoría y códigos `SLHD_CATALOG_00001` (404) y `SLHD_CATALOG_00002` (422) — *cubierto en CatalogApiIntegrationTest*
- [x] T023 [P] [US2] Prueba ArchUnit en `SVCT/architecture/CatalogArchitectureTest.java` que garantice 0 dependencias con `com.shoplab.helpdesk` ni con otros servicios

**Checkpoint**: API REST de catálogo funcional, contract-tested y lista para ser consumida por `retrieval-service` (LAB-34).

---

## Phase 5: User Story 3 - Servidor MCP con herramientas de catálogo para el agente conversacional (Priority: P1)

**Goal**: Servidor Model Context Protocol (MCP) en `shoplab-catalog-service` exponiendo la herramienta `searchProducts` y `getProductDetails` sobre HTTP/SSE para `chat-service` (LAB-43).

**Independent Test**: Enviar peticiones JSON-RPC 2.0 a `/mcp` (`tools/list` y `tools/call`) y comprobar la respuesta estructurada y textual en menos de 500 ms.

### Implementación MCP

- [x] T024 [US3] Implementar controlador y transporte MCP en `SVC/mcp/McpServerController.java` soportando SSE en `GET /mcp/sse` y mensajes JSON-RPC 2.0 en `POST /mcp/messages` (y `POST /mcp`) según LAB-128
- [x] T025 [US3] Implementar la herramienta `searchProducts` (`search_products`) en `SVC/mcp/tools/SearchProductsTool.java` con su JSON Schema de parámetros (`query`, `category`, `in_stock_only`, `limit`)
- [x] T026 [P] [US3] Implementar la herramienta `getProductDetails` (`get_product_details`) en `SVC/mcp/tools/GetProductDetailsTool.java`
- [x] T027 [US3] Implementar formateador de texto en `SVC/mcp/format/McpTextFormatter.java` generando respuestas naturales con stock, precio y categoría para interpretación del LLM
- [x] T028 [P] [US3] Pruebas de integración en `SVCT/mcp/McpServerIntegrationTest.java` validando `tools/list`, invocación `tools/call`, degradación con productos inexistentes (`isError: false`) y manejo de excepciones

**Checkpoint**: Herramientas MCP de catálogo disponibles en red para su consumo por agentes inteligentes.

---

## Phase 6: User Story 4 - Enrutamiento perimetral en shoplab-gateway (Priority: P2)

**Goal**: Configurar `shoplab-gateway` para enrutar el tráfico `/api/catalog/**` hacia `catalog-service`.

**Independent Test**: Enviar petición HTTP a `http://localhost:8080/api/catalog/products` a través del gateway y verificar reenvío exitoso hacia el puerto del servicio con propagación de `X-Request-Id`.

### Configuración de Gateway

- [x] T029 [US4] Actualizar `shoplab-gateway/src/main/resources/application.yml` agregando la ruta `catalog` con prefijo `/api/catalog` — *sin cambios en el gateway: la ruta se añade con SHOPLAB_GATEWAY_ROUTES_CATALOG_PREFIX y _URI (LAB-107)*
- [x] T030 [US4] Prueba de integración en `shoplab-gateway/src/test/java/com/shoplab/gateway/CatalogGatewayRouteTest.java` verificando reenvío hacia WireMock y propagación de `X-Request-Id` y `traceparent` — *ya lo cubre EnvironmentRouteTest del gateway*

**Checkpoint**: Tráfico perimetral redirigido limpiamente al microservicio de catálogo.

---

## Phase 7: User Story 5 - Despliegue continuo en clúster dev mediante ApplicationSet (Priority: P2)

**Goal**: Crear manifiestos K8s en `shoplab-infra` para que Argo CD despliegue automáticamente `catalog-service` en el clúster de dev.

**Independent Test**: Ejecutar `kustomize build apps/catalog-service/overlays/dev` en `shoplab-infra` y comprobar que no hay errores de sintaxis y el ApplicationSet genera la aplicación `catalog-service-dev`.

### GitOps e Infraestructura

- [x] T031 [US5] Crear manifiestos base en `shoplab-infra/apps/catalog-service/base/` (`deployment.yaml`, `service.yaml`, `servicemonitor.yaml`, `externalsecret.yaml`, `kustomization.yaml`)
- [x] T032 [US5] Crear overlay en `shoplab-infra/apps/catalog-service/overlays/dev/` (`kustomization.yaml`, `networkpolicy.yaml`, configuración de variables de entorno y secreto `catalog-service-db`)
- [x] T033 [US5] Actualizar `shoplab-infra/apps/gateway/overlays/dev/kustomization.yaml` configurando `SHOPLAB_GATEWAY_ROUTES_CATALOG_URI=http://catalog-service:8080`
- [x] T034 [P] [US5] Incorporar `catalog-service` y su base de datos en `shoplab-helpdesk/infra/docker-compose.yml` para el entorno multi-repo local (LAB-110)
- [x] T035 [US5] Validar renderizado de manifiestos con `kustomize build apps/catalog-service/overlays/dev` — *con kubectl kustomize*

**Checkpoint**: Servicio integrado en GitOps para despliegue automatizado en Kubernetes.

---

## Phase 8: Polish & Validation

- [ ] T036 Ejecutar prueba de rendimiento y paginación determinista sobre los 32.951 productos simulando el consumidor `retrieval-service` (LAB-34) — *no ejecutada*
- [x] T037 Ejecutar verificación completa de calidad en `shoplab-catalog-service` (`mvn clean verify`, Checkstyle, PMD, Qodana, JaCoCo ≥ 80%) — *`pipeline / verify` en verde con Qodana en catalog-service #5*
- [x] T038 APROBACIÓN Crear Pull Requests coordinados en `shoplab-catalog-service`, `shoplab-contracts`, `shoplab-gateway` y `shoplab-infra` — *catalog-service #5, contracts #19, infra #56 y helpdesk #203; gateway sin cambios*
- [ ] T039 Actualizar matriz de servicios en `shoplab-helpdesk/docs/mapa-servicios.md` marcando `catalog-service` como activo — *marcado como pendiente de despliegue*
- [x] T040 Completar las evidencias de validación en `specs/LAB-113-catalog-service/validation.md`
