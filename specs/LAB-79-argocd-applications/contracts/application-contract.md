# Contrato: Application de Argo CD

Aplica a `k8s/argocd/root.yaml` y a `k8s/argocd/application-{dev,staging,prod}.yaml` en `shoplab-infra`.

## Campos obligatorios

| Campo | Raíz | Ambiente | Regla / quién la verifica |
|---|---|---|---|
| `apiVersion` / `kind` | `argoproj.io/v1alpha1` / `Application` | igual | kubeconform (catálogo de CRD) |
| `metadata.namespace` | `argocd` | `argocd` | kubeconform + revisión |
| `metadata.finalizers` | `resources-finalizer.argocd.argoproj.io` | igual | política `APPLICATION_NO_FINALIZER` (nueva) |
| `spec.project` | `default` | `default` | revisión |
| `spec.source.repoURL` | URL real de `shoplab-infra` | igual | política `TEMPLATE_REPO_URL` (LAB-99) |
| `spec.source.targetRevision` | `main` | `main` | política `TARGET_REVISION_NOT_MAIN` (LAB-99) |
| `spec.source.path` | `k8s/argocd` | `k8s/overlays/<env>` | `kustomize build` del destino |
| `spec.destination.server` | `https://kubernetes.default.svc` | igual | revisión |
| `spec.destination.namespace` | `argocd` | `shoplab-<env>` | revisión (igual al namespace del overlay) |
| `spec.syncPolicy.automated.prune` | `true` | `true` | revisión; prueba de `terraform test` para la raíz |
| `spec.syncPolicy.automated.selfHeal` | `true` | `true` | ídem |
| `spec.syncPolicy.retry` | backoff 5 × (10 s … 3 min) | igual | revisión |

## Prohibido

- `CreateNamespace=true` en las hijas: el namespace lo crea el overlay (LAB-80).
- Cualquier `Secret`, token o credencial en el archivo (política `PLAIN_SECRET`).
- `targetRevision` distinto de `main` (`HEAD`, ramas, tags).
- Que `root.yaml` figure en `k8s/argocd/kustomization.yaml` (la raíz no se gestiona a sí misma).

## Composición de `k8s/argocd/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - application-dev.yaml
  - application-staging.yaml
  - application-prod.yaml
```

Añadir un ambiente o servicio con `Application` propia = nuevo `application-<nombre>.yaml` + una línea aquí.

## Reglas de CI

| Regla | Origen | Cambio en esta historia |
|---|---|---|
| `TEMPLATE_REPO_URL` | LAB-99 | ninguno; ahora lee `argocd.yaml` y `argocd-root.yaml` |
| `TARGET_REVISION_NOT_MAIN` | LAB-99 | ninguno; ahora lee `argocd.yaml` y `argocd-root.yaml` |
| `PLAIN_SECRET` | LAB-80 | ninguno |
| `APPLICATION_NO_FINALIZER` | LAB-79 | nueva: toda `Application` debe llevar el finalizer |
