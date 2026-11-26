# Research: LAB-113 catalog-service nace como servicio

Hallazgos e investigaciones arquitectónicas para el diseño de `shoplab-catalog-service`.

## D-1 Nacimiento directo vs extracción (ADR-0031 / LAB-128)

- **Contexto**: ADR-0001 y ADR-0015 preveían originalmente que todos los módulos se desarrollasen primero en el monolito modular y se extrajesen en F5.
- **Decisión**: Al cierre de F2, los módulos transaccionales (`catalog`, `orders`, `tickets`) estaban vacíos. Construirlos dentro del monolito para extraerlos semanas después representaba un doble coste de desarrollo, migración y refactorización. Mediante **ADR-0031** (formalización de LAB-128 / ADR-0029 en Jira), se decidió que `catalog-service` nace directamente como microservicio independiente al inicio de F3.
- **Consecuencia**: No se escribe código de catálogo en `shoplab-helpdesk/backend`. El módulo nace desacoplado con su propio ciclo de vida, versionado (`release-please`) y repositorio.
- **Patrón Outbox y Debezium**: La emisión de eventos de dominio `catalog.product-changed` hacia Kafka mediante Outbox y Debezium se pospone a **LAB-57**, permitiendo que LAB-113 se enfoque exclusivamente en la persistencia, API REST, servidor MCP y despliegue.

## D-2 Soberanía de datos y almacenamiento (ADR-0015, ADR-0031)

- **Decisión**: Base de datos dedicada `catalog` con usuario/rol `catalog` en PostgreSQL 16.
- **Sin claves foráneas externas**: Siguiendo el principio de soberanía de datos, `catalog` no tiene claves foráneas hacia `orders` ni clientes. Las referencias a productos desde órdenes (`order_items.product_id`) se manejan como IDs opacos de texto (`text`).
- **Sin pgvector**: A diferencia de `retrieval-service`, `catalog-service` no utiliza la extensión `pgvector` ni calcula embeddings. La búsqueda semántica y vectorización de productos pertenece a `retrieval-service` (LAB-34).

## D-3 Persistencia y carga del dataset Olist (LAB-17)

- **Hallazgo**: El dataset público Olist contiene 32.951 productos (`olist_products_dataset.csv`), categorías traducidas (`product_category_name_translation.csv`) y vendedores (`olist_sellers_dataset.csv`).
- **Stock sintético**: Olist no incluye stock real. LAB-17 estableció la fórmula determinista `olist-stock-v1` (1 de cada 10 productos en 0, el resto entre 1 y 200 generado determinísticamente a partir del hash del `product_id`).
- **Precio de referencia**: Calculado como la mediana de los precios de venta de cada producto en `order_items`.
- **Estrategia Flyway**: Las migraciones `V1__init_catalog_tables.sql` replican la estructura validada en `data/olist/schema/catalog/V2__olist_catalog_tables.sql`.

## D-4 API REST y estrategia de paginación para retrieval-service (LAB-34)

- **Requisito de LAB-34**: `retrieval-service` necesita leer el catálogo completo para generar chunks y calcular embeddings vectoriales para búsqueda en lenguaje natural ("zapatillas para lluvia").
- **Decisión**: `GET /api/catalog/products` debe soportar paginación determinista (`ORDER BY product_id ASC`) y filtro opcional por fecha de actualización (`updated_after`). El tamaño de página por defecto será 50 (máximo 200) para permitir lecturas batch eficientes sin saturar la memoria del servicio.
- **Contrato OpenAPI**: Publicado en `shoplab-contracts/openapi/catalog/v1/catalog-api.yaml`.

## D-5 Especificación y transporte del Servidor MCP (LAB-128, LAB-43)

- **Contexto**: LAB-128 fijó el estándar Model Context Protocol (MCP) sobre HTTP/SSE para comunicación entre `chat-service` (cliente) y los servicios de dominio (servidores).
- **Herramientas**:
  - `searchProducts` (`search_products`): Búsqueda por texto libre, categoría y filtro de stock disponible. Devuelve ficha técnica formateada y stock.
  - `getProductDetails` (`get_product_details`): Consulta directa por ID de producto.
- **Resiliencia**: Timeout de ejecución estricto de 3 segundos en el cliente MCP (`chat-service`). Si el servicio de catálogo excede el tiempo, el chat degrada suavemente sin interrumpir la conversación.

## D-6 Enrutamiento perimetral y GitOps

- **Gateway**: `shoplab-gateway` incorpora el prefijo `/api/catalog/**` redirigiendo a `http://catalog-service:8080`.
- **Despliegue dev**: Carpeta `shoplab-infra/apps/catalog-service/` con overlay `dev`, detectada automáticamente por el ApplicationSet de Argo CD (`shoplab-services-dev`).
