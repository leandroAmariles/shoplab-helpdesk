# Contrato HTTP de eval-service

Paridad exacta con el monolito (FR-007…FR-009, FR-011). El OpenAPI normativo vive en `shoplab-contracts`
(`eval-service.yaml`); esta tabla es la referencia para generarlo y para las pruebas productor.

| Método y ruta | Cabeceras | Éxito | Errores |
|---|---|---|---|
| `POST /api/eval/runs` | `X-Eval-Token` | `201` `EvalRunSavedResponse` | `403 SLHD_EVAL_00001`, `422 SLHD_EVAL_00002`, `409 SLHD_EVAL_00003`, `400 SLHD_GENE_00002`, `503 SLHD_EVAL_00005` |
| `GET /api/eval/runs/{runId}` | `X-Eval-Token` | `200` `StoredRunResponse` | `403 …00001`, `404 …00004`, `503 …00005` |
| `GET /api/eval/settings` | `X-Eval-Token` | `200` `EvalSettingsResponse` | `403 …00001`, `503 …00005` (incluye `chat-service` caído o lento) |
| `GET /api/eval/history/runs?kind&origin&limit` | ninguna | `200` `RunListResponse` | `422 …00002` (parámetros), `503 …00005` |
| `GET /api/eval/history/runs/{runId}` | ninguna | `200` `RunDetailResponse` (con `traceUrl`) | `404 …00004` |
| `GET /api/eval/history/compare?base&target&metric&drop` | ninguna | `200` `RunComparisonResponse` | `404 …00004`, `422 …00002`, `422 …00006` |

Reglas:

- Los cuerpos y cabeceras de error son los de `GlobalExceptionHandler` copiado del monolito (`ErrorResponse` con
  `code` y `description`), más `X-Request-Id`.
- Propiedades desconocidas y cuerpos ilegibles en `POST /api/eval/runs` se rechazan con `400 SLHD_GENE_00002`, como en
  el monolito; `422 SLHD_EVAL_00002` es para un cuerpo legible que no cumple las reglas.
- Sin token configurado (`SHOPLAB_EVAL_PERSISTENCE_TOKEN` vacío) el guardado queda desactivado (403): ese es también el
  mecanismo para suspender el guardado durante la migración.
- Sin autenticación de usuario (ADR-0024); el token no llega al navegador.

## Verificación como productor

Contratos Spring Cloud Contract en `src/test/resources/contracts/eval/` (guardar, leer, no encontrado, ajustes,
listado). Los stubs se publican como `com.shoplab:shoplab-eval-service:<versión>:stubs`.
