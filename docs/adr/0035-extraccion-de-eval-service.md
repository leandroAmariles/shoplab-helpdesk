# ADR-0035: Extracción de `eval-service`

- Estado: aceptado
- Fecha: 2026-11-25

## Contexto

LAB-130 extrae el último módulo del monolito, `eval` (historial de ejecuciones de evaluación y su lectura para
`admin-eval`), a su propio repositorio `shoplab-eval-service`
([ADR-0031](0031-extraccion-temprana-de-servicios.md)), detrás del gateway estrangulador
([ADR-0032](0032-gateway-estrangulador.md)) y después de `retrieval-service`
([ADR-0033](0033-extraccion-de-retrieval-service.md)) y `chat-service`
([ADR-0034](0034-extraccion-de-chat-service.md)). Tras LAB-59, `backend/` solo contiene `eval` y `shared`, así que esta
extracción permite retirar el monolito y deja a la evaluación online de LAB-69 un servicio donde vivir. Hay que decidir
dónde viven sus datos, cómo se hace el corte, cómo lee la configuración del asistente y qué pasa con `backend/`.

## Decisión

- **Base propia en el mismo servidor, sin `pgvector`.** Base `eval` con rol dedicado `eval` (`REVOKE CONNECT` a
  `PUBLIC` en ambas bases), aprovisionada de forma idempotente por `infra/postgres/eval-db-init.sh` (`eval-db-init`).
  Se conserva el esquema `eval` y las migraciones V1 y V2 sin cambios.
- **Migración por volcado y comprobación de secuencias `IDENTITY`.** `pg_dump --schema=eval` y `pg_restore` bajo
  `SET ROLE eval` en una sola transacción (`scripts/migrate-eval.{sh,ps1}`), verificando recuentos, sumas MD5 de
  `eval_run`, `eval_case`, `eval_result` y `flyway_schema_history`, además de `last_value >= max(id)` en las tres
  secuencias (`scripts/verify-eval-migration.{sh,ps1}`).
- **Sin clave de LiteLLM propia.** `eval-service` no llama a modelos: el juez vive en la herramienta Python.
- **Mismo contrato y mismos códigos.** `/api/eval/runs`, `/api/eval/settings` y `/api/eval/history/**` con el mismo
  estado, cuerpo y códigos `SLHD_EVAL_*` que el monolito, aunque ADR-0031 prevea códigos propios por servicio:
  cambiarlos rompería a la herramienta de evaluación. El token `X-Eval-Token` se conserva; sin autenticación de usuario
  (ADR-0024).
- **Configuración del asistente desde el contrato de `chat`.** `GET /api/eval/settings` lee
  `GET /api/assistant/settings` de `chat-service` con un cliente generado desde `shoplab-contracts`, con tiempo máximo
  de 5 s, propagación de `X-Request-Id` y `traceparent`, y traducción de fallos a `SLHD_EVAL_00005`. Sin modo
  `in-process` ni dependencia de código de `chat`. La prueba de contrato eval → chat usa los stubs de `chat-service`.
- **Corte por configuración.** Un solo commit en `shoplab-infra` mueve `/api/eval/**` en el gateway. La ventana
  suspende el guardado vaciando el token del monolito; no hay doble escritura.
- **Retiro de `backend/` en una fase aparte.** Tras verificar el corte, un PR retira `backend/`, su imagen, CI, compose
  y despliegue, sin ejecutar `DROP SCHEMA` y preservando los datos de Olist (ADR-0034). La constitución se enmienda
  (PATCH) en ese PR.

## Consecuencias

- `eval-service` se construye, publica, firma y despliega de forma independiente.
- La separación de la base `eval` es de credenciales en el mismo servidor, compartido por local y `dev`; `shoplab` es
  superusuario, de modo que «solo el servicio lee su base» se cumple por configuración, no por el motor.
- **Volver atrás.** Antes del retiro de `backend/` basta con revertir el commit de corte en `shoplab-infra`; las
  ejecuciones guardadas en `eval-service` durante la ventana no vuelven solas al esquema antiguo (la herramienta puede
  volver a guardarlas: el guardado es idempotente por `runId`). Después del retiro hay que restaurar `backend/` desde el
  tag `backend-final-pre-LAB-130` y la copia de la base `eval`.
- El esquema `eval` antiguo en `shoplab` queda sin uso; su borrado (`DROP SCHEMA eval CASCADE` con copia previa) es un
  paso manual documentado en [F5-mover-rutas-al-gateway.md](../fases/F5-mover-rutas-al-gateway.md).
- La evaluación online de LAB-69 se añade en `eval-service` sin crear otro servicio.

## Imagen del monolito

`ghcr.io/leandroamariles/backend` queda **deprecada**: no se publican versiones nuevas y ningún compose ni overlay la
usa. Se conserva (última: `v0.31.0`) para poder restaurar.

## Cómo restaurar

El punto de restauración es el tag `backend-final-pre-LAB-130` (último commit de `main` con `backend/`). Las copias de
datos se tomaron el 2026-11-26 con `pg_dump --format=custom` de la base `eval` (`eval-db.dump`) y del esquema `eval` de
la base `shoplab` (`shoplab-eval-schema.dump`); se guardan fuera del repositorio.

1. Código: `git checkout backend-final-pre-LAB-130 -- backend` y revertir el PR de retiro (compose, CI y configuración).
2. Datos: para llevar al monolito lo guardado después del corte, restaurar la base `eval` sobre `shoplab`:
   `pg_restore --clean --if-exists -d shoplab eval-db.dump` (sustituye el esquema `eval`; hacer antes otra copia).
3. Dev: en `shoplab-infra`, revertir el retiro del monolito y el commit de corte del gateway.

