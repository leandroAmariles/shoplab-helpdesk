# Requirements Checklist: LAB-113 catalog-service nace como servicio

Checklist de verificación de requerimientos y criterios de aceptación para la especificación LAB-113.

## Criterios de Aceptación de Jira

- [ ] **CA-1: Creación del repositorio y persistencia**
  - [ ] Repositorio `shoplab-catalog-service` creado a partir de `shoplab-service-template` (LAB-103).
  - [ ] Hereda flujos de CI (`ci.yml`), release (`release-please.yml`) y configuración de calidad.
  - [ ] Base de datos PostgreSQL dedicada `catalog` con usuario/rol `catalog`.
  - [ ] Migraciones Flyway aplicadas para `product_categories`, `products` y `sellers`.
  - [ ] Datos de Olist migrados/cargados (32.951 productos, categorías traducidas, vendedores).
  - [ ] Stock sintético determinista `olist-stock-v1` y precios de referencia calculados.

- [ ] **CA-2: Contrato OpenAPI y APIs de catálogo**
  - [ ] Contrato `openapi/catalog/v1/catalog-api.yaml` publicado en `shoplab-contracts`.
  - [ ] Endpoints implementados:
    - [ ] `GET /api/catalog/products` (búsqueda, filtros y paginación determinista).
    - [ ] `GET /api/catalog/products/{id}` (detalle completo de ficha técnica).
    - [ ] `GET /api/catalog/products/{id}/stock` (consulta ligera de stock y precio).
    - [ ] `GET /api/catalog/categories` (listado de taxonomía de categorías).
  - [ ] `retrieval-service` puede paginar el 100% de productos para indexación semántica (LAB-34).
  - [ ] Códigos de error tipados corporativos (`SLHD_CATALOG_*`) y correlación con `X-Request-Id`.
  - [ ] Verificación de contrato como productor (Spring Cloud Contract / JaCoCo ≥ 80%).

- [ ] **CA-3: Servidor Model Context Protocol (MCP)**
  - [ ] Protocolo MCP sobre HTTP/SSE implementado según especificación LAB-128.
  - [ ] Endpoint de descubrimiento `tools/list` funcional.
  - [ ] Herramienta `searchProducts` (o `search_products`) expuesta con JSON Schema validado (LAB-43).
  - [ ] Herramienta `getProductDetails` expuesta con JSON Schema validado.
  - [ ] Respuestas estructuradas en `content` textual con `isError` claro para el LLM.
  - [ ] Tiempo de respuesta inferior a 500 ms y degradación ante errores.

- [ ] **CA-4: Enrutamiento perimetral en Gateway**
  - [ ] Ruta `catalog` configurada en `shoplab-gateway` con prefijo `/api/catalog`.
  - [ ] Variable de entorno `SHOPLAB_GATEWAY_ROUTES_CATALOG_URI` apunta a `catalog-service:8080`.
  - [ ] Propagación bidireccional de cabeceras (`X-Request-Id`, `traceparent`).

- [ ] **CA-5: Despliegue en Dev mediante ApplicationSet**
  - [ ] Manifiestos K8s creados en `shoplab-infra/apps/catalog-service/` (`base/` y `overlays/dev/`).
  - [ ] ApplicationSet `shoplab-services-dev` crea y sincroniza `catalog-service-dev`.
  - [ ] ExternalSecret sincroniza credenciales de PostgreSQL `catalog-service-db`.
  - [ ] NetworkPolicy restringe tráfico entrante y saliente según arquitectura.
  - [ ] Sondas de salud `/actuator/health` configuradas y reportando estado saludable.

## Principios Arquitectónicos y Restricciones (Constitución)

- [ ] **Cero código en monolito**: Ninguna clase de catálogo se añade a `shoplab-helpdesk` (ADR-0031).
- [ ] **Soberanía estricta de datos**: 0 claves foráneas o consultas cruzadas hacia esquemas externos (ADR-0015).
- [ ] **Cero librerías comunes de dominio**: La integración con otros servicios es 100% por red (OpenAPI y MCP).
- [ ] **Observabilidad estándar**: Métricas `/actuator/prometheus`, logs estructurados JSON Logstash.
