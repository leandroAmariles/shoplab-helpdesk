# Contrato: API HTTP de retrieval-service

Fuente: `shoplab-contracts/openapi/retrieval/v1/retrieval-api.yaml`. **No cambia en esta historia.** Este documento
fija la paridad que el servicio debe mantener con el monolito.

| Operación | Éxito | Errores |
|---|---|---|
| `POST /api/documents` (multipart, parte `file`) | `201` nuevo, `200` reemplazado | `400`, `413`, `415`, `422`, `502`, `503` |
| `GET /api/documents` | `200` lista | `503` |
| `GET /api/knowledge/search?query=&limit=` | `200` `{model, results[]}` | `422`, `429`, `502`, `503` |

## Reglas de paridad

- Cuerpo de error: `{code, message, detail}` con los mismos `SLHD_RETR_00001`–`00010`, `SLHD_DBAS_00001` y
  `SLHD_GENE_*`. Sin envoltorio `ResponseDto`.
- `limit`: por defecto el configurado (5), recortado a 1–50.
- La búsqueda no aplica umbral ni presupuesto de contexto (ADR-0018, ADR-0024).
- Archivo máximo 10 MiB; parte multipart hasta 11 MB.
- `X-Request-Id`: se acepta si es seguro, si no se genera, y se devuelve en la respuesta.
- La línea inicial de la petición admite hasta 64 KB (research D-7).

## Gestión

`GET /actuator/health` (con `liveness` y `readiness`) y `GET /actuator/prometheus`. No se publican por el gateway.
`readiness` falla si la base no está disponible.

## Verificación

- Productor: contratos Spring Cloud Contract en `src/test/resources/contracts/retrieval/` (los tres actuales).
- Stubs: `com.shoplab:shoplab-retrieval-service:<versión>:stubs`.
- Comparación en paralelo: las mismas peticiones contra monolito y servicio ([quickstart](../quickstart.md), paso 4).
