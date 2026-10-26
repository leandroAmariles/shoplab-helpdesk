# Contrato: disposición de `k8s/` tras el cambio

Fuente de verdad del árbol que debe quedar en `shoplab-infra` (FR-001, FR-002, FR-003).

## Árbol final

```text
k8s/
├── base/                         # sin cambios
├── overlays/
│   └── dev/                      # sin cambios: namespace, imágenes, recursos, HPA, host, claves /shoplab/dev/…
└── argocd/
    ├── application-dev.yaml      # sin cambios
    ├── kustomization.yaml        # resources: [application-dev.yaml]
    └── root.yaml                 # NO se lista en el kustomization (la raíz no se gestiona a sí misma)
```

## Se elimina

| Ruta | Motivo |
|---|---|
| `k8s/overlays/staging/` | ambiente fuera de alcance |
| `k8s/overlays/prod/` | ambiente fuera de alcance |
| `k8s/argocd/application-staging.yaml` | su overlay ya no existe |
| `k8s/argocd/application-prod.yaml` | su overlay ya no existe |
| `k8s/argocd-local/` | solo ocultaba las dos Applications anteriores |

## Se edita

| Archivo | Cambio |
|---|---|
| `k8s/argocd/kustomization.yaml` | quitar `application-staging.yaml` y `application-prod.yaml` |
| `k8s/argocd/root.yaml` | `spec.source.path: k8s/argocd`; reescribir el comentario de cabecera (sin "tres ambientes" ni `argocd-local`; sigue indicando que Terraform la crea y que cambiarla exige `terraform apply`) |

## Invariantes (los comprueba el pipeline; no deben cambiar)

1. `kustomize build k8s/overlays/dev` produce los mismos manifiestos que antes del cambio
   (comparar contra la salida de `main`).
2. La salida de `kustomize build k8s/argocd` contiene exactamente una `Application`:
   `shoplab-dev`, con `targetRevision: main`, `repoURL` real y el finalizer
   `resources-finalizer.argocd.argoproj.io`.
3. La salida de `k8s/argocd` **no** incluye `shoplab-root`.
4. `root.yaml` apunta a `k8s/argocd` y mantiene `syncPolicy.automated` con `prune` y `selfHeal`.
5. Contrato con LAB-81: `k8s/overlays/dev/kustomization.yaml` sigue siendo el único archivo que
   edita `deploy-dev.sh` (`OVERLAY=k8s/overlays/dev`).

## Búsqueda de verificación (SC-001)

Sin resultados fuera del ADR-0008 y de documentos históricos (specs de otras historias, ADR-0006/0007):

```bash
grep -rIn -i -E "staging|\bprod\b|argocd-local|envs/local|shoplab-local" \
  --exclude-dir=.git --exclude-dir=.terraform --exclude-dir=.idea --exclude-dir=.rendered .
```
