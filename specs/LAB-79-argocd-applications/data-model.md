# Data Model: Argo CD con una Application por ambiente

No hay base de datos ni esquema nuevo. El "modelo" son los recursos declarativos y sus relaciones.

## Entidades

### Application raíz (`shoplab-root`)

| Campo | Valor | Regla |
|---|---|---|
| `metadata.name` | `shoplab-root` | única en el namespace `argocd` |
| `spec.source.repoURL` | URL real de `shoplab-infra` | sin `CAMBIA-ESTE-OWNER` (FR-005) |
| `spec.source.path` | `k8s/argocd` | detectado como Kustomize |
| `spec.source.targetRevision` | `main` | FR-006 |
| `spec.destination` | `https://kubernetes.default.svc`, namespace `argocd` | las hijas son recursos de `argocd` |
| `spec.syncPolicy.automated` | `prune: true`, `selfHeal: true` | FR-004 |
| `metadata.finalizers` | `resources-finalizer.argocd.argoproj.io` | retira las hijas al borrarla |

Archivo: `k8s/argocd/root.yaml`. **No** se lista en `k8s/argocd/kustomization.yaml`. Creada por Terraform.

### Application de ambiente (`shoplab-dev`, `shoplab-staging`, `shoplab-prod`)

| Campo | `dev` | `staging` | `prod` |
|---|---|---|---|
| Archivo | `application-dev.yaml` | `application-staging.yaml` | `application-prod.yaml` |
| `spec.source.path` | `k8s/overlays/dev` | `k8s/overlays/staging` | `k8s/overlays/prod` |
| `spec.destination.namespace` | `shoplab-dev` | `shoplab-staging` | `shoplab-prod` |

Comunes: `repoURL` y `targetRevision` como la raíz, `project: default`, `automated` con `prune` y `selfHeal`,
`retry` con backoff y el mismo finalizer. Sin `CreateNamespace` (lo crea el overlay).

### Ingress de Argo CD

Creado por el chart de Argo CD (Terraform): clase `nginx`, host `argocd.localtest.me`, backend HTTP del servidor de
Argo CD en modo `insecure`. Sin credenciales ni dominios de producción.

## Relaciones

```text
Terraform (module platform)
  ├── instala  Argo CD (chart) ──── crea ──▶ Ingress argocd.localtest.me
  └── crea     Application shoplab-root   (lee k8s/argocd/root.yaml)
                  └── sincroniza k8s/argocd  (kustomization)
                         ├── Application shoplab-dev      ─▶ k8s/overlays/dev      ─▶ ns shoplab-dev
                         ├── Application shoplab-staging  ─▶ k8s/overlays/staging  ─▶ ns shoplab-staging
                         └── Application shoplab-prod     ─▶ k8s/overlays/prod     ─▶ ns shoplab-prod
```

## Estados y transiciones

Cada `Application` pasa por: `OutOfSync/Missing` → `OutOfSync/Progressing` → `Synced/Healthy`.

- Cambio en `main`: `Synced` → `OutOfSync` → (auto) `Synced`.
- Deriva manual: `Synced` → `OutOfSync` → (`selfHeal`) `Synced`.
- Recurso quitado de git: `OutOfSync` → (`prune`) `Synced`.
- `Application` quitada de `kustomization.yaml`: la raíz la borra (finalizer) y retira sus recursos.
- Overlay inexistente o que no construye: `Unknown`/error de comparación, aislado a esa `Application` (FR-014).
- `ExternalSecret` sin entrada en Secrets Manager: `Synced/Degraded`, sin exponer valores.
