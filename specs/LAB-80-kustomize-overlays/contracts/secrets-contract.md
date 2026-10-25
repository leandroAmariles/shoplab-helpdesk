# Contrato: secretos con ExternalSecret

## Entradas en Secrets Manager (por ambiente)

| Clave remota | Contenido | Consumido por |
|---|---|---|
| `/shoplab/<ambiente>/postgres-password` | contraseña de Postgres | postgres, backend, ingest |

Las entradas las crea el módulo `secrets` de LAB-98 (prefijo `/shoplab/<ambiente>/`, valores por `TF_VAR_secrets`; hoy
`postgres-password` y `llm-api-key`). El usuario y el nombre de la base (`shoplab`) no son secretos y viven en los
ConfigMap de cada componente. Si el backend o ingest necesitan claves de proveedores en fases posteriores, se añaden
aquí con el mismo prefijo.

## Recursos

| ExternalSecret | `Secret` generado | Claves del `Secret` |
|---|---|---|
| `postgres-credentials` | `postgres-credentials` | `POSTGRES_PASSWORD` |
| `backend-credentials` | `backend-credentials` | `SPRING_DATASOURCE_PASSWORD` |
| `ingest-credentials` | `ingest-credentials` | `DATABASE_PASSWORD` |

Cada ExternalSecret usa `secretStoreRef` hacia el `ClusterSecretStore` `aws-secrets-manager` (instalado por el módulo
`platform` de LAB-98) y `refreshInterval` de 1h. La base usa la clave `/shoplab/ENVIRONMENT/postgres-password` y un
parche del overlay sustituye `ENVIRONMENT` por `dev`, `staging` o `prod`.

## Reglas

1. Ningún `kind: Secret` con `data`/`stringData` en el repositorio ni en la salida de `kustomize build`.
2. Los manifiestos solo contienen la clave remota, nunca el valor.
3. Los pods consumen secretos con `secretKeyRef`/`envFrom`, nunca con valores en línea ni en ConfigMap.
4. Cada ambiente referencia solo sus propias claves (`/shoplab/<ambiente>/…`).
5. La política `PLAIN_SECRET` de `scripts/policy-check.sh` falla el PR con la línea
   `PLAIN_SECRET | <overlay> | Secret/<namespace>/<nombre> | kind Secret found (use ExternalSecret)`.

## Fallos esperados

| Situación | Comportamiento |
|---|---|
| Clave remota inexistente | El ExternalSecret queda `SecretSyncedError`; los pods dependientes no arrancan; sin valores en logs |
| `ClusterSecretStore` no disponible | Igual que arriba; se corrige en LAB-98, no en los overlays |
