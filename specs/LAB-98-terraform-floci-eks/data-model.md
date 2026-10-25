# Data Model: LAB-98

Modelo de los recursos que gestiona la infraestructura (no hay base de datos de aplicación).

## Entidades

### Environment (`envs/<ambiente>`)
- `name` (p. ej. `local`), `region` (`us-east-1` ficticia), `cluster_mode` (`floci` | `kind`, por defecto `floci`).
- Posee un estado remoto propio (clave `<ambiente>/terraform.tfstate`) y compone `eks`, `secrets`, `platform`.
- Regla: dos ambientes nunca comparten clave de estado.

### EmulatorEndpoint
- `url` (`http://localhost:4566`), credenciales ficticias (`test`/`test`).
- Usado por el provider `aws` y por el backend; ningún valor real de AWS.

### StateBucket (módulo `state`)
- `bucket_name`, versionado activo. Contiene `terraform.tfstate` y `terraform.tfstate.tflock`.
- Transición del bloqueo: libre → bloqueado (operación en curso) → libre; huérfano solo ante fallo (se libera con
  `force-unlock`, documentado).

### Cluster (módulo `eks`)
- `name`, `kubernetes_version`, `endpoint`, `certificate_authority`, `kubeconfig` (sensible).
- Estados: no existe → creando → activo → eliminado. `platform` solo arranca con el clúster activo.

### Secret / Parameter (módulo `secrets`)
- Secret: `name`, `value` (sensible, externo a git), versión actual.
- Parameter: `name` (ruta `/shoplab/<ambiente>/…`), `type`, `value`.
- Regla: los valores llegan solo por variables sensibles; en git solo nombres y marcadores.

### PlatformComponent (módulo `platform`)
- `name` (`ingress-nginx`, `external-secrets`, `argo-cd`, `kube-prometheus-stack`), `namespace`,
  `chart`, `chart_version` (exacta), `values`.
- Dependencias: ingress-nginx → external-secrets → kube-prometheus-stack → argo-cd.
- Relación: `external-secrets` crea un `ClusterSecretStore` que apunta a Secrets Manager de Floci.

## Relaciones

```text
Environment 1 ── 1 StateBucket
Environment 1 ── 1 Cluster ── * PlatformComponent
Environment 1 ── * Secret/Parameter ──(sincroniza ESO)──▶ Kubernetes Secret en Cluster
```
