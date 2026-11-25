# Contrato: migración de datos

Scripts en `shoplab-helpdesk/scripts/`, ejecutados contra el contenedor `postgres` del compose, con versión PowerShell
y Bash como los demás scripts del repositorio.

| Paso | Hace | Falla si |
|---|---|---|
| Provisionar (`retrieval-db-init`) | crea rol, base y extensión; idempotente | no hay superusuario |
| Migrar | copia el esquema `retrieval` de `shoplab` a `retrieval` | el destino ya tiene filas y no se pidió `--replace` |
| Verificar | compara ambas bases | cualquier diferencia |

## Garantías

- El origen solo se lee.
- La restauración es de una sola transacción: queda completa o no queda.
- Repetir con `--replace` borra el esquema destino y vuelve a copiar; nunca duplica.
- `--replace` se usa solo antes del corte. Después, el destino es la fuente de verdad.

## Verificación

Por cada tabla (`document`, `chunk`, `flyway_schema_history`): número de filas y suma de comprobación de las filas
completas ordenadas por clave. Además, número de fragmentos con vector por `embedding_model`. Salida legible, código de
salida 0 solo si todo coincide.

## Orden del corte

1. Línea base de LAB-28 contra el sistema actual.
2. Avisar y dejar de subir documentos.
3. Copia de seguridad de `shoplab`.
4. Migrar con `--replace` y verificar.
5. Comprobar que el servicio está sano.
6. Fusionar el commit del corte.
7. Evaluación de LAB-28 y comprobaciones; reanudar subidas.

## Reversión

Revertir el commit del corte. Los documentos subidos al servicio entre tanto no vuelven al monolito: se vuelven a subir
desde `data/kb/` o desde su archivo original.

## Retirada del origen

`DROP SCHEMA retrieval CASCADE` en `shoplab`, manual, solo después de verificar la release B en dev y con una copia de
seguridad. No forma parte de ninguna migración ni release.
