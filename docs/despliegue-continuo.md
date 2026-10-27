# Despliegue continuo a dev

Cada versión publicada (tag `vX.Y.Z` de release-please) termina desplegada en `dev` sin pasos manuales y sin que el CI
toque el clúster (LAB-81, ADR-0012). Un merge a `main` publica imágenes por SHA pero no cambia `dev`. El pipeline solo
escribe en git; Argo CD (ADR-0006) lee `shoplab-infra` y converge el clúster.

## Flujo

```text
merge del PR de release → tag vX.Y.Z → Images: build-scan → publish (3 imágenes, firmadas) → deploy
                                                                      │ kustomize edit set image (tag = vX.Y.Z)
                                                                      ▼
                          shoplab-infra · k8s/overlays/dev/kustomization.yaml  (commit del bot)
                                                                      │ sondeo de Argo CD (~3 min)
                                                                      ▼
                                                              dev ejecuta la versión
```

- El job `deploy` de [`images.yml`](../.github/workflows/images.yml) corre solo en `push` de un tag `v*.*.*` (nunca en PR ni en
  ramas) y solo si las tres imágenes se publicaron. Su lógica está en
  [`deploy-dev.sh`](../.github/scripts/deploy-dev.sh), con pruebas en
  [`tests/deploy-dev.test.sh`](../.github/scripts/tests/deploy-dev.test.sh).
- Antes de escribir comprueba que existen las tres imágenes con el tag de la versión y que cada servicio figura en
  `images:` del overlay. Después edita el overlay, construye `dev` con `kustomize build`, exige que cada imagen
  renderice con el tag de la versión, ejecuta `policy-check.sh` de `shoplab-infra` y solo entonces commitea y empuja.
- Nunca usa `kubectl`, ni credenciales del clúster, ni `:latest`.
- Si la versión ya está fijada termina en verde sin commit. Si el push se rechaza (otro commit en `main` de infra) descarta
  su commit, reaplica la edición sobre el remoto actual y reintenta hasta 3 veces con espera creciente.
- Las ejecuciones se serializan en el grupo `deploy-dev` sin cancelar: si llegan varios tags seguidos, GitHub conserva solo
  el pendiente más reciente y `dev` termina en la última versión.
- Cada ejecución deja en su resumen el resultado, la versión, el SHA del tag, los servicios y el commit creado en `shoplab-infra`.

## Trazabilidad

El commit de despliegue tiene este formato (contrato en
[`specs/LAB-81-continuous-deploy-dev/contracts/deploy-commit-contract.md`](../specs/LAB-81-continuous-deploy-dev/contracts/deploy-commit-contract.md)):

```text
chore(dev): [LAB-81] despliega <vX.Y.Z> a dev

Versión: <vX.Y.Z>
Aplicación: leandroAmariles/shoplab-helpdesk@<sha completo del tag>
CI: <URL de la ejecución de GitHub Actions>
Servicios: backend, frontend, ingest
```

Autor: `shoplab-deploy[bot]`. Un commit por versión, y solo toca `k8s/overlays/dev/kustomization.yaml`.

## Versión desplegada en dev

```bash
# La versión que pide git (en un clon de shoplab-infra)
git log -3 --format='%h %s%n%b' -- k8s/overlays/dev/kustomization.yaml
grep -A2 'name: ghcr.io/leandroamariles/backend' k8s/overlays/dev/kustomization.yaml

# La que ejecuta el clúster
kubectl -n shoplab-dev get deploy -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

Deben coincidir; si no, mira `kubectl -n argocd get application shoplab-dev`.

## Rollback

`dev` vuelve atrás revirtiendo el commit de despliegue en `shoplab-infra`; nunca se toca el clúster a mano (`selfHeal`
revertiría el cambio).

1. Identifica el commit a revertir: el último de `git log -- k8s/overlays/dev/kustomization.yaml` hecho por
   `shoplab-deploy[bot]`. Su cuerpo enlaza el SHA y la ejecución de CI que lo produjo.
2. En `shoplab-infra`: `git revert <commit>` en una rama nueva y abre la PR (el ruleset de `main` exige PR a las
   personas; solo la App de despliegue tiene bypass). Fusiónala con CI en verde.
3. Verifica: `kubectl -n argocd get application shoplab-dev -w` pasa a `OutOfSync` y vuelve a `Synced`, y las imágenes del
   clúster (comando anterior) son las anteriores.
4. El siguiente tag de versión **vuelve a avanzar `dev`**, porque el despliegue siempre fija la versión publicada sin
   mirar el estado previo. Si el defecto sigue en `main`, corrígelo antes de fusionar el siguiente PR de release; de lo
   contrario esa versión sobrescribe el rollback. Los merges a `main` que no publican versión no cambian `dev`.

## Credencial

El job escribe en `shoplab-infra` con una GitHub App propia (`shoplab-deploy`), no con un PAT personal.

| Aspecto | Valor |
|---|---|
| Instalación | solo `leandroAmariles/shoplab-infra` |
| Permisos | `contents: write` y `metadata: read`; nada más |
| Token | de instalación, generado en cada ejecución, caduca en 1 hora y se enmascara en los logs |
| Almacén | `Environment` `dev` de `shoplab-helpdesk`, restringido a `main` y a los tags `v*.*.*`: variable `DEPLOY_APP_ID` y secreto `DEPLOY_APP_PRIVATE_KEY` |
| Bypass | actor de bypass del ruleset `main-protect` de `shoplab-infra` (ADR-0007) |

Los PR (incluidos los de forks) no reciben el secreto: el job no corre en ellos.

### Crear la App

1. GitHub → Settings → Developer settings → GitHub Apps → New. Nombre `shoplab-deploy`, sin webhook.
2. Permisos de repositorio: **Contents: Read and write** (Metadata: Read-only es obligatorio). Ninguno más.
3. Instálala en tu cuenta eligiendo **Only select repositories → shoplab-infra**.
4. Genera una clave privada (`.pem`) y anota el App ID.
5. En `shoplab-helpdesk` → Settings → Environments → `dev`: limita los despliegues a la rama `main` y al patrón de tags `v*.*.*` (el job corre por tag),
   añade la variable `DEPLOY_APP_ID` y
   el secreto `DEPLOY_APP_PRIVATE_KEY` (contenido completo del `.pem`). Borra el archivo local.
6. En `shoplab-infra`, con `gh` autenticado como administrador: `DEPLOY_APP_ID=<id> bash scripts/apply-ruleset.sh` añade la
   App como único actor de bypass.

### Rotar y revocar

- **Rotar**: genera una clave nueva en la App, actualiza `DEPLOY_APP_PRIVATE_KEY` y borra la clave anterior. Rótala al
  menos una vez al año o si sospechas una filtración.
- **Revocar**: desinstala la App de `shoplab-infra` o borra su clave. El siguiente despliegue falla de forma visible y `dev`
  conserva su última versión.

## Si el despliegue falla

| Código | Causa | Qué hacer |
|---|---|---|
| 10 | falta una imagen con el tag de la versión | revisa el job `publish` de la misma ejecución; no despliega nada |
| 11 | un servicio publicado no está en `images:` del overlay | añádelo al overlay de `shoplab-infra` (o quítalo de `SERVICES`) |
| 12 | `kustomize build` o `policy-check.sh` rechazan el resultado | corrige el overlay en infra; `dev` no cambió |
| 13 | no se pudo empujar tras los reintentos | revisa la credencial y el ruleset; reejecuta el job |

Reejecutar el job es seguro: si la versión ya está fijada, no crea commits.
