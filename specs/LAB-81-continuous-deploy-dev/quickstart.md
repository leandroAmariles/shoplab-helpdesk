# Quickstart: validar el despliegue continuo a dev

## Prerrequisitos

- LAB-78 (imágenes en GHCR) y LAB-79 (Argo CD sincronizando `dev`) en `main`; clúster local arriba (`make up` en
  `shoplab-infra`).
- GitHub App de despliegue creada e instalada solo en `shoplab-infra`; `DEPLOY_APP_ID` y `DEPLOY_APP_PRIVATE_KEY`
  en el `Environment` `dev`; la App como actor de bypass del ruleset de `shoplab-infra`.
- `kustomize`, `yq` y `git` locales para las pruebas del script.

## 1. Pruebas del script (sin red)

```bash
bash .github/scripts/tests/deploy-dev.test.sh
```

Esperado: casos verdes de éxito, idempotencia, imagen inexistente (10), servicio no declarado (11), build/política
fallida (12) y conflicto con reintento (13 solo si se agotan).

## 2. Flujo completo (SC-001, SC-002, SC-006)

1. Fusiona un cambio trivial en `backend/` a `main`.
2. Espera a `Images` en verde y al job `deploy`.
3. Comprueba en `shoplab-infra`:

```bash
git -C ../shoplab-infra pull
git -C ../shoplab-infra log -1 --format=%B -- k8s/overlays/dev/kustomization.yaml
yq '.images' ../shoplab-infra/k8s/overlays/dev/kustomization.yaml
```

Esperado: asunto `chore(dev): [LAB-81] despliega <sha7> a dev`, SHA y URL de CI en el cuerpo, `newTag` = SHA en las
tres imágenes y ningún `digest` ni `latest`.

## 3. Convergencia de dev (SC-003)

```bash
kubectl -n argocd get application shoplab-dev -w
kubectl -n shoplab-dev get deploy -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

Esperado: `OutOfSync` → `Synced` en menos de 10 min y las imágenes del SHA fusionado. (En el k3s de Floci `dev`
puede no llegar a `Healthy` por la falta de `StorageClass`; basta `Synced` con las imágenes correctas.)

## 4. Idempotencia y concurrencia (SC-007)

- Reejecuta el job `deploy` de la misma ejecución: termina en verde, sin commit nuevo.
- Fusiona dos cambios seguidos: `dev` termina en el SHA del segundo.

## 5. Seguridad (SC-004, SC-005)

- `grep -rn "kubectl" .github/workflows/images.yml .github/scripts` sin resultados.
- `grep -rn ":latest" ../shoplab-infra/k8s/overlays` sin resultados.
- Abre un PR y confirma que el job `deploy` aparece omitido.
- Revisa los permisos de la instalación de la App: solo `shoplab-infra`, `contents: write`.

## 6. Rollback (SC-008)

Sigue `docs/despliegue-continuo.md`: abre una PR en `shoplab-infra` que revierta el último commit de despliegue,
fusiónala y observa a `dev` volver a las imágenes anteriores; después fusiona otro cambio y confirma que `dev`
avanza de nuevo.
