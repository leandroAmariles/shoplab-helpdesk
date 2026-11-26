# Validation & Test Strategy: LAB-113

Estrategia de validación y recolección de evidencias para el cumplimiento de los criterios de aceptación de LAB-113.

## Matriz de Cobertura de Criterios

| Criterio | Tipo de Prueba | Herramienta | Evidencia Requerida |
|---|---|---|---|
| Repositorio creado desde plantilla | Inspección de repositorio y CI | GitHub Actions | Workflows de CI (`ci.yml`) y release (`release-please.yml`) ejecutándose en verde. |
| Persistencia y migración de Olist | Integración de BD | Testcontainers / pg_isready | Verificación de 32.951 productos en `products` con stock consistente. |
| OpenAPI en `shoplab-contracts` | Validación de contrato | Spring Cloud Contract | Pruebas de contrato productor en verde; artefacto `catalog-api.yaml` publicado. |
| Paginación determinista para LAB-34 | Rendimiento y paginación | JUnit 5 / Gatling | Recorrido paginado completo sin duplicados ni saltos de registros. |
| Servidor MCP (`searchProducts`) | Integración JSON-RPC | WebTestClient / WireMock | Llamada `tools/call` exitosa con tiempo de respuesta < 500 ms y formato textual legible. |
| Enrutamiento en Gateway | Integración perimetral | `shoplab-gateway` tests | Petición a `/api/catalog/**` redirigida con cabecera `X-Request-Id` propagada. |
| Despliegue en Dev | GitOps K8s | Argo CD CLI / Kustomize | Estado de aplicación `Synced` y `Healthy` en el namespace `shoplab-dev`. |

## Procedimiento de Validación E2E

1. **Persistencia**: Ejecutar consulta SQL de verificación:
   ```sql
   SELECT count(*) AS total_products,
          count(*) FILTER (WHERE stock = 0) AS out_of_stock,
          count(DISTINCT category_name) AS total_categories
   FROM products;
   ```
2. **Paginación determinista**: Realizar peticiones consecutivas a páginas `0` y `1` y comprobar que no existe ningún `productId` repetido entre ambas páginas.
3. **MCP Tool Calling**: Probar invocación con argumentos válidos, argumentos con producto agotado y producto inexistente, verificando que `isError` y el mensaje devuelto cumplen el contrato.
4. **GitOps**: Ejecutar `kustomize build apps/catalog-service/overlays/dev` en `shoplab-infra` sin advertencias ni errores de validación de esquemas.

## Evidencias (2026-11-26, local)

| Comprobación | Resultado |
|---|---|
| `./mvnw -o clean verify` en `shoplab-catalog-service` | BUILD SUCCESS: 16 pruebas, Checkstyle, PMD y umbral de JaCoCo (94,8 % de líneas en la medición intermedia) |
| Generación de DTOs Java y cliente npm en `shoplab-contracts` | Compila; pureza de contratos correcta |
| `kubectl kustomize apps/catalog-service/overlays/dev` | Renderiza Deployment, Service, ServiceMonitor, ExternalSecret y 2 NetworkPolicy |
| `scripts/migrate-catalog.sh` | 73 categorías, 32.951 productos y 3.095 vendedores, origen = destino |
| Compose con `CATALOG_IMAGE` local | Todos los servicios `healthy` |
| `GET /api/catalog/products?size=1` por el gateway | 200, `totalElements` 32951 |
| `tools/call searchProducts` (`watches`) | `isError: false`, 0,11 s |

Revisión previa a crear el repositorio: `getProductDetails` escribía `nullxnullxnull cm` en productos sin medidas;
corregido con su prueba (`./mvnw -o clean verify`: BUILD SUCCESS, 16 pruebas). Repositorio
`leandroAmariles/shoplab-catalog-service` creado desde la plantilla, privado; la implementación aún no está subida.

No ejecutado: Qodana, `make check` de infra (falta kubeconform), prueba de alertas (falta promtool), rendimiento (SC-004)
y despliegue en dev (SC-007).

## Desviaciones respecto de la especificación

- Olist tiene 73 categorías cargadas (71 traducidas y 2 que solo aparecen en productos), no 71.
- No hay títulos de producto: «reloj deportivo» no devuelve resultados. `query` busca palabras en el ID y la categoría
  en portugués o inglés; la búsqueda en lenguaje natural es de LAB-34.
- El gateway no cambia de código (FR-017 se cumple por configuración de despliegue).
- Puerto local 8085: el 8084 que cita la especificación ya es de `eval-service`.
