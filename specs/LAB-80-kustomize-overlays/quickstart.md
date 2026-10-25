# Quickstart: validar los manifiestos de LAB-80

Guía de validación; el detalle de cada recurso está en [contracts/](contracts/) y [data-model.md](data-model.md).

## Requisitos

- Clon de `shoplab-infra` con la rama de la historia y las herramientas de `versions.env` (kustomize, kubeconform, Trivy).
- Para la validación en vivo: Docker y el entorno de LAB-98 levantado (`make up`).

## 1. Construcción y esquema (sin clúster)

```bash
for env in dev staging prod; do
  kustomize build k8s/overlays/$env | kubeconform -strict -summary -ignore-missing-schemas=false
done
```

Esperado: los tres terminan sin error y cada uno emite los 6 componentes, un `Namespace` y 3 HPA.

## 2. Políticas

```bash
make check          # o: scripts/policy-check.sh
```

Esperado: pasa. Comprobaciones de rechazo (una por vez, revertir tras cada una):

| Mutación | Esperado |
|---|---|
| Cambiar un `newTag` por `latest` en un overlay | falla `image-latest` con archivo e imagen |
| Quitar el tag de una imagen | falla `image-no-tag` |
| Añadir un `kind: Secret` con datos en un overlay | falla `no-plain-secret` |
| Borrar `k8s/overlays/prod/kustomization.yaml` | falla indicando el overlay |

## 3. Comparación entre ambientes

```bash
diff <(kustomize build k8s/overlays/dev) <(kustomize build k8s/overlays/prod)
```

Esperado: difieren en namespace, host, recursos, HPA y almacenamiento; no en los tipos ni nombres de componentes.

## 4. Validación en vivo (overlay `dev` en el clúster de LAB-98)

```bash
make up                                           # Floci + EKS + ESO + Argo CD
kubectl --kubeconfig <kubeconfig> -n shoplab-dev get externalsecret,pods,hpa
```

Esperado: ExternalSecrets en `SecretSynced`; `postgres`, `backend`, `frontend` e `ingest` en `Running`/`Ready`; HPA
con métricas; Argo CD con la aplicación `Synced` y `Healthy`.

Sondas del backend:

```bash
kubectl -n shoplab-dev port-forward deploy/backend 8080
curl -s localhost:8080/actuator/health/liveness     # {"status":"UP"}
curl -s localhost:8080/actuator/health/readiness    # {"status":"UP"}
```

Acceso por ingress: `curl -H 'Host: shoplab.localtest.me' http://localhost:<nodePort>/`.

## 5. Servicio nuevo (US5)

Siguiendo solo `docs/kustomize.md`, añadir un servicio de ejemplo (base + 3 overlays) y comprobar que el paso 1 y el
paso 2 siguen en verde. Eliminarlo al terminar.

## Criterios de salida

SC-001 a SC-007 del [spec](spec.md) verificados; los puntos del paso 4 solo aplican a `dev`.
