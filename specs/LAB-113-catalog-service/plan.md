# Implementation Plan: LAB-113 catalog-service nace como servicio

**Branch**: `feature/LAB-113-catalog-service` | **Date**: 2026-11-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/LAB-113-catalog-service/spec.md`

## Summary

Crear el microservicio independiente `shoplab-catalog-service` desde la plantilla estándar (`shoplab-service-template`, LAB-103), dueño exclusivo del esquema y base de datos `catalog` con los datos de Olist (32.951 productos, categorías y vendedores) ya migrados con stock sintético determinista y precio de referencia. Publicar su contrato OpenAPI en `shoplab-contracts`, implementar la API REST de búsqueda, detalle y stock (consumida por `retrieval-service` en LAB-34 para indexación) y un servidor Model Context Protocol (MCP) con la herramienta `searchProducts` (consumida por `chat-service` en LAB-43). Configurar el enrutamiento `/api/catalog/**` en `shoplab-gateway` (LAB-107) y desplegar el servicio en el clúster `dev` mediante el ApplicationSet de Argo CD (LAB-104) en `shoplab-infra`.

A diferencia de los servicios extraídos del monolito (`retrieval-service`, `chat-service`, `eval-service`), `catalog-service` nace directamente como un servicio autónomo en F3 en cumplimiento de la decisión arquitectónica ADR-0031 (LAB-128).

## Technical Context

**Language/Version**: Java 21; Bash y PowerShell para scripts de automatización e infraestructura.

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux reactivo), Flyway, R2DBC / JDBC para persistencia PostgreSQL, Micrometer Prometheus, Spring Cloud Contract 5.0.3, Spring AI / MCP Server (o adaptador JSON-RPC 2.0 sobre WebFlux SSE), `shoplab-contracts` (generación de modelos y contratos).

**Storage**: PostgreSQL 16. Base de datos dedicada `catalog` con usuario `catalog`. Esquema `catalog` con tablas `product_categories`, `products` y `sellers`. Sin extensión `pgvector` (la búsqueda vectorial vive en `retrieval-service`).

**Testing**: JUnit 5, Reactor Test, Testcontainers PostgreSQL, WireMock, BlockHound, Spring Cloud Contract (productor), ArchUnit, Checkstyle, PMD, JaCoCo ≥ 80%.

**Target Platform**: Docker Compose en entorno local multi-repo (LAB-110); Kubernetes (`shoplab-dev`) en dev con Argo CD ApplicationSet (LAB-104).

**Project Type**: Servicio web reactivo + modificaciones coordinadas en 5 repositorios:
1. `shoplab-catalog-service` (nuevo repositorio)
2. `shoplab-contracts` (nuevo contrato `openapi/catalog/v1/catalog-api.yaml`)
3. `shoplab-infra` (`apps/catalog-service/` overlays/dev y K8s base)
4. `shoplab-gateway` (ruta `/api/catalog/**`)
5. `shoplab-helpdesk` (documentación de specs, compose local y datasets)

**Performance Goals**:
- Paginación de catálogo: `GET /api/catalog/products` < 100 ms para tamaño de página 100.
- Consulta de detalle/stock: `GET /api/catalog/products/{id}` < 20 ms.
- Ejecución de herramienta MCP: `tools/call` `searchProducts` < 500 ms.

**Constraints**:
- Soberanía estricta de datos (ADR-0015, ADR-0031): 0 FKs cruzadas con otros esquemas.
- Cero librerías comunes de dominio (ADR-0010): solo contratos OpenAPI y MCP.
- Stock sintético determinista `olist-stock-v1` y precio de referencia mediano (LAB-17).
- Errores tipados con prefijo corporativo `SLHD_CATALOG_*`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Antes de Fase 0 | Tras Fase 1 | Cómo se cumple |
|---|---|---|---|
| I. Monolito modular primero | PASS | PASS | Enmendado por ADR-0031 (LAB-128): módulos vacíos (`catalog`, `orders`) nacen directamente como servicios en F3/F4 en lugar de construirse en el monolito. |
| II. Hexagonal | PASS | PASS | `shoplab-catalog-service` se estructura en `domain`, `application/port`, `infrastructure/adapter` (REST, MCP, DB). |
| III. Identificadores en inglés, errores tipados | PASS | PASS | Tablas, clases y campos en inglés. Códigos de error tipados `SLHD_CATALOG_*` con `GlobalExceptionHandler`. |
| IV. Pruebas y cobertura ≥ 80 % | PASS | PASS | Pruebas unitarias, de integración con Testcontainers y verificación de contrato OpenAPI; JaCoCo configurado en pom.xml con umbral 80%. |
| V. RAG guiado por evaluación | PASS | PASS | Expone API para que `retrieval-service` indexe en LAB-34 y herramienta MCP para que `chat-service` consulte en LAB-43. |
| VI. Observabilidad y secretos | PASS | PASS | Filtro de correlación `X-Request-Id`, métricas Actuator Prometheus, credenciales de BD por External Secrets y `.env`. |
| VII. Simplicidad y decisiones documentadas | PASS | PASS | Nace sin Kafka/Debezium inicial (diferido a LAB-57); endpoints REST y MCP directos y ligeros. |

## Project Structure & Architecture

### Diagrama de interacción

```mermaid
flowchart TD
    subgraph Clientes Externos y Frontend
        Browser["Navegador / Frontend"]
    end

    subgraph Perímetro
        GW["shoplab-gateway (:8080)<br/>Ruta: /api/catalog/**"]
    end

    subgraph Servicios Internos
        CS["chat-service (:8080)<br/>(LAB-43 / F4)"]
        RS["retrieval-service (:8080)<br/>(LAB-34 / F3)"]
        CAT["shoplab-catalog-service (:8080)<br/>(LAB-113 / F3)"]
    end

    subgraph Persistencia
        DB[(PostgreSQL :5432<br/>Base: catalog<br/>Esquema: catalog)]
    end

    Browser -->|HTTP /api/catalog/**| GW
    GW -->|Proxy HTTP| CAT
    CS -->|MCP tools/call: searchProducts<br/>HTTP/SSE /mcp| CAT
    RS -->|HTTP GET /api/catalog/products<br/>(Paginación indexación)| CAT
    CAT -->|R2DBC / JDBC| DB
```

### Repositorios involucrados

```text
shoplab-catalog-service/                  # NUEVO REPO (generado de shoplab-service-template)
├── src/main/java/com/shoplab/catalog/
│   ├── api/                              # Controladores REST (ProductController, CategoryController)
│   ├── mcp/                              # Servidor MCP (McpServerController, ProductMcpTools)
│   ├── application/                      # Casos de uso (SearchProductsUseCase, GetProductStockUseCase)
│   ├── domain/                           # Entidades (Product, ProductCategory, Seller, Money, Stock)
│   ├── infrastructure/                   # Adaptadores de BD (PostgresProductRepository, etc.)
│   └── shared/                           # Errores SLHD_CATALOG_*, X-Request-Id, ResponseWrapper
├── src/main/resources/
│   ├── db/migration/catalog/             # V1__init_catalog_tables.sql
│   └── application.yml
├── src/test/java/...                     # Tests unitarios, integración y contratos
├── pom.xml, Dockerfile, mvnw
└── .github/workflows/{ci,release,release-please}.yml

shoplab-contracts/                        # REPO EXISTENTE
└── openapi/catalog/v1/catalog-api.yaml   # Contrato OpenAPI normativo

shoplab-infra/                            # REPO EXISTENTE
└── apps/catalog-service/
    ├── base/                             # deployment.yaml, service.yaml, externalsecret.yaml, etc.
    └── overlays/dev/                     # kustomization.yaml, networkpolicy.yaml

shoplab-gateway/                          # REPO EXISTENTE
└── src/main/resources/application.yml    # Registro ruta catalog: /api/catalog
```

## Fases de Implementación

### Fase 1: Creación del repositorio, persistencia y migración Flyway
1. Crear el repositorio `shoplab-catalog-service` a partir de `shoplab-service-template`.
2. Adaptar el esquema DDL Flyway `V1__init_catalog_tables.sql` para PostgreSQL (tablas `products`, `product_categories`, `sellers`).
3. Configurar script de carga de datos de Olist con stock determinista y precio de referencia.
4. Validar compilación, análisis estático (Checkstyle, PMD) y pruebas básicas de persistencia con Testcontainers.

### Fase 2: Contrato OpenAPI y Endpoints REST de Catálogo
1. Redactar y publicar en `shoplab-contracts` la especificación `openapi/catalog/v1/catalog-api.yaml`.
2. Implementar controladores reactivos `ProductController` y `CategoryController`.
3. Implementar paginación determinista para soportar la indexación secuencial de `retrieval-service` (LAB-34).
4. Implementar endpoints de detalle y consulta ligera de stock/precio.
5. Configurar pruebas de contrato como productor (Spring Cloud Contract) con cobertura ≥ 80%.

### Fase 3: Implementación del Servidor Model Context Protocol (MCP)
1. Implementar endpoint `/mcp` con soporte SSE y recepción de mensajes JSON-RPC 2.0 según el estándar LAB-128.
2. Definir e implementar la herramienta `searchProducts` (`search_products`) con JSON Schema para argumentos.
3. Definir e implementar la herramienta `getProductDetails` (`get_product_details`).
4. Implementar manejo de excepciones y formato de respuesta con texto legible por LLM.
5. Pruebas de integración del protocolo MCP validando `tools/list` y `tools/call`.

### Fase 4: Enrutamiento en Gateway y Despliegue GitOps en Dev
1. Actualizar `shoplab-gateway` para registrar la ruta `catalog` con prefijo `/api/catalog`.
2. Crear manifiestos K8s en `shoplab-infra/apps/catalog-service/` (`base/` y `overlays/dev/`).
3. Configurar ExternalSecrets para credenciales de PostgreSQL `catalog`.
4. Verificar sincronización automática en Argo CD a través de `ApplicationSet`.
5. Incorporar `catalog-service` en el Docker Compose del entorno local multi-repo (LAB-110).

### Fase 5: Validación y Cierre
1. Ejecutar pruebas de carga/paginación simulando el consumo de `retrieval-service`.
2. Validar invocación de herramientas MCP con cliente simulado de `chat-service`.
3. Generar evidencia de pruebas y actualizar mapa de servicios y matriz de trazabilidad.
