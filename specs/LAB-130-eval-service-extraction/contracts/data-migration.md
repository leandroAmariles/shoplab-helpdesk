# Migración de datos del esquema eval

Modelada en `scripts/migrate-chat.*` y `infra/postgres/chat-*.sh` (LAB-59).

## Procedimiento

1. **Preparar** (idempotente): `eval-db-init` crea el rol `eval` y la base `eval` y revoca `CONNECT` a `PUBLIC`.
2. **Línea base**: guardar en `specs/LAB-130-eval-service-extraction/baseline/` recuentos, MD5 por tabla y las
   respuestas del historial para ids fijos (research D-10).
3. **Ventana**: vaciar `SHOPLAB_EVAL_PERSISTENCE_TOKEN` en el monolito (guardado → 403). La lectura sigue.
4. **Migrar**: `scripts/migrate-eval.{sh,ps1} [--replace]` → `pg_dump --schema=eval` de `shoplab` y `pg_restore` en la
   base `eval` bajo `SET ROLE eval`, en una transacción. `--replace` vacía el destino antes de restaurar.
5. **Verificar**: `scripts/verify-eval-migration.{sh,ps1}` (criterios abajo).
6. **Cortar**: commit en `shoplab-infra` que mueve `/api/eval/**`; fijar el token en `eval-service`; la herramienta
   apunta a la URL nueva; reanudar el guardado.
7. **Comparar** con la línea base (research D-10) y guardar el resultado en `validation.md`.

## Criterios de verificación

| Comprobación | Resultado esperado |
|---|---|
| `count(*)` de `eval_run`, `eval_case`, `eval_result`, `flyway_schema_history` | Igual en origen y destino |
| MD5 de cada tabla ordenada por `id` | Igual |
| `last_value >= max(id)` de las tres secuencias `IDENTITY` | Cierto |
| Esquema de origen | Sin cambios (MD5 igual que antes) |
| Una ejecución nueva guardada en el destino | Recibe un `id` mayor que todos los migrados |

## Reversión

- **Antes de la fase D**: revertir el commit del corte devuelve `/api/eval/**` al monolito. Las ejecuciones guardadas
  en `eval-service` durante la ventana no vuelven solas; se re-migran con `pg_dump` inverso o se vuelven a guardar
  desde la herramienta (idempotente por `runId`).
- **Después de la fase D**: restaurar `backend/` desde el tag previo al retiro y la copia de la base `eval`; es un
  procedimiento manual documentado en el ADR-0035.
- El esquema `eval` antiguo de `shoplab` queda sin uso; `DROP SCHEMA eval CASCADE` con copia previa es un paso manual
  posterior (FR-025), no parte de esta historia.
