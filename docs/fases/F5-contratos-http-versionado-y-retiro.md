# F5 · Versionado de APIs HTTP y retiro de versiones anteriores

Esta guía define cuándo cambia de versión una API HTTP en **ShopLab Helpdesk**, cómo coexisten versiones concurrentes durante una transición y el procedimiento obligatorio para deprecar y retirar una versión anterior sin romper a clientes (`frontend/` u otros microservicios).

Complementa la guía general del repositorio de contratos en [docs/contratos.md](../contratos.md) y la topología de servicios definida tras la extracción temprana (`ADR-0031`, `LAB-128`).

---

## 1. Cuándo cambia de versión una API HTTP (SemVer + `oasdiff`)

Todas las especificaciones HTTP viven en `shoplab-contracts/openapi/<servicio>/v<N>/<servicio>-api.yaml` y su versión semántica está gobernada por `release-please` junto con el gate automático de `oasdiff` en CI (`scripts/check-openapi-compatibility.sh`).

| Tipo de cambio | Incremento | Ejemplos concretos en OpenAPI | Regla de CI (`oasdiff` y `release-please`) |
|---|---|---|---|
| **Corrección sin impacto de contrato** | `patch` (`X.Y.Z+1`) | Aclaración en `description`, corrección de `example`, ajuste tipográfico en `summary` o etiquetas `tags`. | `oasdiff breaking` reporta 0 roturas. Commit `fix(api): ...` o `docs(api): ...`. |
| **Cambio compatible (aditivo)** | `minor` (`X.Y+1.0`) | Nuevo endpoint (`GET /api/assistant/settings`), nuevo campo **opcional** en un payload de petición o respuesta, nuevo parámetro de consulta opcional, nuevo código HTTP de respuesta documentado. | `oasdiff breaking` reporta 0 roturas. Commit `feat(<servicio>): ...`. |
| **Cambio incompatible (breaking change)** | `major` (`X+1.0.0`) | Eliminar o renombrar un endpoint/campo, cambiar el tipo de una propiedad, hacer **obligatorio** un campo o parámetro que era opcional, restringir un `enum`, cambiar el código de estado de éxito o error esperado. | `oasdiff breaking` detecta ruptura y **bloquea el PR** salvo que el título del PR o commit declare `!:` o `BREAKING CHANGE:`. |

> **Regla de oro**: Si `oasdiff` detecta una incompatibilidad en un PR sin `!:` ni `BREAKING CHANGE:`, el cambio está prohibido como `minor`/`patch` y debe transformarse en un cambio aditivo (*expand*) o seguir el flujo completo de cambio mayor.

---

## 2. Convivencia de versiones y estrategia *Expand-and-Contract*

Cuando un cambio incompatible es necesario, **nunca** se rompe a los consumidores en un único despliegue:

1. **Cambios compatibles dentro de la misma versión mayor (`v1`)**:
   - Se añaden los nuevos campos o endpoints en una versión `minor` de `shoplab-contracts`.
   - Se despliega primero el **productor** (backend), luego se actualizan los **consumidores** (`frontend/` o servicios llamantes).
2. **Cambios incompatibles que requieren convivencia simultánea**:
   - **Opción preferida (Expand-and-Contract en `v1`)**: el productor expone temporalmente tanto el campo/endpoint antiguo (marcado `deprecated: true`) como el nuevo durante una ventana `minor`, migra todos los consumidores y retira lo antiguo en la versión `major` (`v2.0.0`).
   - **Opción por versión en ruta (`/api/v2/...` o archivo `openapi/<servicio>/v2/<servicio>-api.yaml`)**: cuando la semántica completa de un recurso cambia de raíz y `v1` y `v2` deben coexistir en producción para clientes desacoplados, se crea `v2` sin eliminar `v1` hasta completar el retiro.

---

## 3. Procedimiento de retiro (*Sunset*) de una versión anterior

Para retirar un endpoint, campo o versión mayor previa de una API HTTP se siguen estos 6 pasos en orden estricto:

1. **Marcar la deprecación en OpenAPI**:
   - Añadir `deprecated: true` sobre la operación, parámetro o propiedad en `shoplab-contracts/openapi/<servicio>/...` y documentar en `description` la alternativa vigente y la fecha/versión objetivo de retiro.
   - Publicar una versión `minor` en `shoplab-contracts` (el marcado `deprecated: true` es retrocompatible para `oasdiff`).
2. **Señalizar en tiempo de ejecución (productor)**:
   - Cuando se trate de un endpoint HTTP completo en transición, el productor puede incluir las cabeceras estándar `Deprecation: true`, `Sunset: <fecha RFC 7231>` y `Link: <ruta-sucesora>; rel="successor-version"` y emitir un log/métrica de advertencia por invocación heredada.
3. **Migrar a todos los consumidores**:
   - Dependabot abre los PRs de actualización de `@leandroamariles/shoplab-contracts` (npm) y `com.shoplab:shoplab-contracts` (Maven) en `shoplab-helpdesk` y en los repositorios de servicios consumidores.
   - Los consumidores adoptan la nueva operación/campo; el compilador TypeScript/Java y los tests de Spring Cloud Contract (`StubRunner`) garantizan que ninguna referencia obsoleta siga en uso.
4. **Verificar tráfico cero antes de apagar**:
   - Comprobar en Prometheus (`http_server_requests_seconds_count{uri="<ruta-obsoleta>"}`) y en los logs estructurados que la versión o campo anterior registra **0 invocaciones** durante al menos una ventana completa de validación en `dev`/producción.
5. **Publicar el retiro como cambio mayor (`major`)**:
   - Abrir PR en `shoplab-contracts` eliminando el endpoint/campo/especificación obsoleta con título/commit `feat(<servicio>)!: [LAB-xxx] retire deprecated ...` (o `BREAKING CHANGE:`).
   - `oasdiff` valida que la ruptura está declarada para `major`; `release-please` genera la nueva versión mayor (`v2.0.0`).
6. **Eliminar el código legado del productor**:
   - Actualizar `shoplab-contracts` en el servicio productor, retirar los controladores, DTOs y contratos Spring Cloud Contract de la versión retirada, verificar `./mvnw clean verify` y desplegar.

---

## 4. Referencias

- Flujo de publicación en `shoplab-contracts`, autenticación y Dependabot: [docs/contratos.md](../contratos.md)
- Reglas de nacimiento de servicios (`catalog`, `orders`, `tickets`): `shoplab-contracts/openapi/README.md`
