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
`leandroAmariles/shoplab-catalog-service` creado desde la plantilla (público).

CI de los PRs (catalog-service #5, contracts #19, infra #56, helpdesk #203): en verde. Antes hubo que corregir dos
fallos: Qodana en `GlobalExceptionHandler` y las pruebas de `ServiceDown` sin la serie de `catalog-service`.

### Rendimiento (T036), imagen publicada `v0.1.0` en el compose local

| Recorrido secuencial completo | Resultado |
|---|---|
| `size=100`, directo (330 páginas) | 32.951 productos únicos y ordenados; 8,8 s (3.755/s); p50 30 ms, p95 35 ms, máx. 124 ms |
| `size=100`, por el gateway | 8,7 s (3.806/s); p50 30 ms, p95 34 ms, máx. 102 ms |
| `size=200`, directo (165 páginas) | 3,8 s (8.581/s); p95 32 ms |
| Detalle (100 peticiones) | p50 13 ms, p95 26 ms |
| `tools/call searchProducts` (3 llamadas) | 48-78 ms |

SC-004 (≥ 200 productos/s) y SC-005 (< 500 ms) se cumplen. El objetivo del plan para el detalle (< 20 ms) se cumple en la
mediana, no en el p95. Datos: 32.951 productos, 3.358 sin stock y todos con precio de referencia.

### Despliegue en dev (2026-11-26, clúster local)

| Comprobación | Resultado |
|---|---|
| Argo CD `catalog-service-dev` | `Synced` / `Healthy` (SC-007) |
| ExternalSecret `catalog-service-db` | `SecretSynced` |
| `GET /api/catalog/products?size=1` por el gateway del clúster | 200, `totalElements` 32951, `X-Request-Id` devuelto (SC-006) |
| `tools/call searchProducts` | 200; primera llamada 0,64 s, las cinco siguientes 0,16-0,23 s |
| Prometheus | `up{job="catalog-service"} = 1`, sin `ServiceDown` activas |
| Release `v0.1.0` | publicar y desplegar en verde (el despliegue, al relanzarlo tras fusionar infra) |

No verificado: las NetworkPolicy no se probaron con tráfico denegado.

Fuera de esta historia, observado al arrancar el clúster: los pods de Langfuse y LiteLLM quedan en `CrashLoopBackOff`
(`Permission denied` en el volumen de `litellm-postgres`), y dos releases de Helm faltan en el estado de Terraform.

## Desviaciones respecto de la especificación

- Olist tiene 73 categorías cargadas (71 traducidas y 2 que solo aparecen en productos), no 71.
- No hay títulos de producto: «reloj deportivo» no devuelve resultados. `query` busca palabras en el ID y la categoría
  en portugués o inglés; la búsqueda en lenguaje natural es de LAB-34.
- El gateway no cambia de código (FR-017 se cumple por configuración de despliegue).
- Puerto local 8085: el 8084 que cita la especificación ya es de `eval-service`.
- El parámetro `sort` de [contracts/http-api.md](contracts/http-api.md) no está implementado.
- Las pruebas de contrato usan YAML locales, no el OpenAPI de `shoplab-contracts`: SC-003 no está demostrado.
