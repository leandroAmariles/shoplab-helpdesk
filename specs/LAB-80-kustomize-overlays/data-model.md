# Data Model: Manifiestos con Kustomize y overlays

No hay datos de aplicación nuevos. Las "entidades" son recursos declarativos y sus relaciones.

## Base

- Ruta: `k8s/base/`; construible solo para inspección, no se despliega directamente.
- Contiene 6 componentes; no incluye namespace, HPA, host del ingress ni tags de imagen efectivos.
- Etiquetas comunes: `app.kubernetes.io/part-of: shoplab`, y por componente `app.kubernetes.io/name`.

## Componente

| Componente | Tipo de carga | Puerto | Con estado | Credenciales | Sondas |
|---|---|---|---|---|---|
| backend | Deployment | 8080 | no | sí (BD) | startup/liveness/readiness Actuator |
| frontend | Deployment | 8080 | no | no | HTTP `/` |
| ingest | Deployment | 8000 | no | sí (BD) | TCP 8000 |
| postgres | StatefulSet | 5432 | sí (PVC) | sí (usuario y contraseña) | `pg_isready` |
| ollama | StatefulSet | 11434 | sí (PVC modelos) | no | `ollama list` |
| ingress | Ingress | 80 | n/a | no | n/a |

Cada componente tiene: carga (Deployment/StatefulSet), Service, `securityContext`, recursos y, si procede,
ExternalSecret y ConfigMap.

## Overlay de ambiente

| Campo | Descripción |
|---|---|
| `name` | `dev`, `staging` o `prod` |
| `namespace` | `shoplab-<name>` |
| `images` | tag/digest por imagen de la aplicación y de terceros |
| `replicas` / `resources` | por componente (ver contrato de valores) |
| `hpa` | mínimo, máximo y objetivo de CPU para backend, frontend e ingest |
| `config` | host del ingress, nivel de log, modelos de Ollama, tamaños de PVC |
| `secretPrefix` | `/shoplab/<name>/` para las `remoteRef.key` |

Invariante: cada overlay produce exactamente el mismo conjunto de tipos/nombres de recursos que la base, más
`Namespace` y los tres `HorizontalPodAutoscaler`.

## ExternalSecret

| Campo | Valor |
|---|---|
| `name` | `<componente>-credentials` |
| `secretStoreRef` | `ClusterSecretStore` de LAB-98 |
| `target.name` | mismo nombre; es el `Secret` que consumen los pods |
| `data[].remoteRef.key` | `/shoplab/<ambiente>/<nombre>` |
| Valor | nunca en el manifiesto |

## Relaciones

```text
Overlay ──usa──▶ Base ──contiene──▶ Componente ──consume──▶ ExternalSecret ──lee──▶ Secrets Manager (LAB-98)
Overlay ──define──▶ HPA ──escala──▶ Deployment (backend, frontend, ingest)
Argo CD (LAB-98) ──sincroniza──▶ Overlay dev ◀──valida── Pipeline (LAB-99)
```

## Reglas de validación

- Ninguna imagen con `:latest` ni sin tag (FR-008).
- Ningún `kind: Secret` en la salida de los overlays (FR-009).
- Todo recurso válido contra el esquema de Kubernetes y de los CRD usados (FR-012).
- Cada overlay produce ≥ 1 recurso y el conjunto completo de componentes (FR-011, SC-001).
