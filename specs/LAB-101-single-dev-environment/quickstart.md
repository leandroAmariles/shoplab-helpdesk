# Quickstart: validar la consolidación a un solo ambiente dev

Guía para comprobar de punta a punta que LAB-101 funciona. No contiene implementación; el detalle de
rutas y nombres está en [`contracts/`](contracts/) y [`data-model.md`](data-model.md).

## Requisitos

- Docker en marcha, bash (Git Bash en Windows), `make` y Terraform `1.15.8` (ver `versions.env`).
- `kustomize`, `kubeconform` y `yq` de las versiones de `versions.env` (`scripts/check-tools.sh` los verifica).
- `kubectl` para inspeccionar Argo CD.
- Rama `chore/LAB-101-single-dev-environment` en `shoplab-infra` con el cambio aplicado.
- Un clúster local **anterior** al cambio, si existe, destruido antes (paso 0).

## 0. Limpiar un clúster previo (solo si existe)

Con la configuración anterior (rama `main` previa al cambio, `ENV=local`):

```bash
make down
```

Si no responde: `docker compose down -v --remove-orphans`. Resultado esperado: sin contenedores
`shoplab-floci`, `floci-aws-*` ni `shoplab-local-control-plane` (`bash scripts/verify-clean.sh` sale 0).

## 1. Estructura y búsqueda de residuos (historias 1 y 3)

```bash
ls k8s/overlays          # dev
ls k8s/argocd            # application-dev.yaml  kustomization.yaml  root.yaml
ls terraform/envs        # dev
test ! -d k8s/argocd-local && echo "argocd-local eliminado"
grep -rIn -i -E "staging|\bprod\b|argocd-local|envs/local|shoplab-local" \
  --exclude-dir=.git --exclude-dir=.terraform --exclude-dir=.idea --exclude-dir=.rendered .
```

Esperado: los tres `ls` como se indica y la búsqueda sin resultados (en `shoplab-helpdesk`, solo ADR-0008
y documentos históricos).

## 2. El overlay dev no cambió (SC-003)

```bash
# en main (antes del cambio)
kustomize build k8s/overlays/dev > /tmp/dev-before.yaml
# en la rama del cambio
kustomize build k8s/overlays/dev > /tmp/dev-after.yaml
diff /tmp/dev-before.yaml /tmp/dev-after.yaml && echo "idéntico"
```

Esperado: `idéntico` (diferencia cero). Los archivos temporales van al directorio de scratch, no al repo.

## 3. Controles del pipeline en local (historia 2)

```bash
make check
```

Esperado: pasa completo; `kustomize` construye solo `k8s/overlays/dev` y `k8s/argocd`, y `.rendered/` queda con
`dev.yaml`, `argocd.yaml` y `argocd-root.yaml`. Regresión opcional (descartar el cambio después): meter
`:latest` en una imagen del overlay `dev` y comprobar que `make policies` falla.

## 4. Levantar dev desde cero (historias 3 y 5)

```bash
cp terraform/envs/dev/secrets.auto.tfvars.example terraform/envs/dev/secrets.auto.tfvars   # editar valores de prueba
make up
export KUBECONFIG=terraform/envs/dev/kubeconfig.yaml
kubectl -n argocd get applications
```

Esperado (máximo `ARGOCD_TIMEOUT`, 600 s, para Synced): `shoplab-root` y `shoplab-dev` en
`Synced/Healthy`; ninguna otra Application. En el Floci local, Postgres y Ollama dependen de un
`StorageClass` que el k3s de Floci no trae (ver `docs/kustomize.md`): si `shoplab-dev` queda `Progressing`
por eso, se registra como limitación conocida y se comprueba que está `Synced` y sin `ComparisonError`.

Comprobaciones adicionales:

```bash
aws --endpoint-url http://localhost:4566 secretsmanager list-secrets --query 'SecretList[].Name'   # solo /shoplab/dev/…
kubectl get ns | grep shoplab                                                                       # solo shoplab-dev
```

## 5. El despliegue continuo sigue funcionando (SC-005)

Tras fusionar el PR de `shoplab-infra`, un merge cualquiera a `main` de `shoplab-helpdesk` debe producir en
`shoplab-infra` el commit `chore(dev): [LAB-81] despliega <sha> a dev` y `shoplab-dev` pasa a ese SHA. Es
la misma comprobación de LAB-81; solo confirma que `OVERLAY=k8s/overlays/dev` no se vio afectado.

## 6. Prueba de humo y limpieza

```bash
make down
bash scripts/verify-clean.sh
```

En CI, `make smoke` hace lo mismo con la salvedad ya conocida de `scripts/smoke.sh` (en un runner
`shoplab-dev` solo debe estar *creada*).

## 7. Documentación (historia 4)

- Leer `docs/adr/0008-un-solo-ambiente-dev.md`: decisión, motivo, consecuencias y los pasos para añadir otro
  ambiente. Debe poder encontrarse desde `docs/adr/README.md`.
- ADR-0006 y ADR-0007 muestran la nota de reemplazo parcial.
- README de ambos repositorios y `docs/` de `shoplab-infra` describen un solo ambiente.

## 8. Registrar el resultado (FR-015)

Anotar en `specs/LAB-101-single-dev-environment/validation.md`: fecha, commit de `shoplab-infra`, salida de
`make check`, estado de `kubectl -n argocd get applications` y cualquier limitación observada.

## Resultados esperados (resumen)

| Comprobación | Esperado |
|---|---|
| Overlays / Applications / entornos Terraform | `dev` / `shoplab-dev` / `dev` |
| `kustomize build` de `dev` antes vs después | idéntico |
| `make check` | verde |
| `make up` desde cero | `shoplab-root` y `shoplab-dev` Synced/Healthy |
| Búsqueda de `staging`/`prod`/`envs/local` | sin resultados fuera del ADR-0008 e históricos |
| Despliegue continuo de LAB-81 | sin cambios |
