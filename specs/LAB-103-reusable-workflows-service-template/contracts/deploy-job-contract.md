# Contrato: Job Reutilizable de Despliegue GitOps (`deploy.yml`)

**Repositorio**: `leandroamariles/shoplab-ci`
**Ruta**: `.github/workflows/deploy.yml`
**Versión**: `@v1`
**Trigger**: `workflow_call`

---

## 1. Entradas del Workflow (`inputs`)

| Nombre | Tipo | Requerido | Por Defecto | Descripción |
|---|---|---|---|---|
| `service-name` | `string` | Sí | — | Nombre del servicio tal como aparece en `shoplab-infra` (ej: `chat-service`) |
| `image-tag` | `string` | Sí | — | Tag de la versión recién publicada en GHCR (ej: `v1.2.0`) |
| `image-namespace` | `string` | No | `'ghcr.io/leandroamariles'` | Prefijo del registro OCI |
| `overlay` | `string` | No | `'k8s/overlays/dev'` | Ruta relativa del overlay en `shoplab-infra` |
| `branch` | `string` | No | `'main'` | Rama destino de `shoplab-infra` |

---

## 2. Secretos y Variables Requeridas

| Nombre | Tipo | Ubicación | Descripción |
|---|---|---|---|
| `DEPLOY_APP_ID` | Variable | Org / Repo | ID de la GitHub App `shoplab-deploy[bot]` |
| `DEPLOY_APP_PRIVATE_KEY` | Secreto | Org / Repo | Llave privada PEM de la GitHub App |

---

## 3. Protocolo de Ejecución GitOps

1. **Obtención de Token de Alcance Mínimo**:
   ```yaml
   - name: Generate Deploy Token
     id: app-token
     uses: actions/create-github-app-token@v3
     with:
       app-id: ${{ secrets.DEPLOY_APP_ID }}
       private-key: ${{ secrets.DEPLOY_APP_PRIVATE_KEY }}
       owner: ${{ github.repository_owner }}
       repositories: 'shoplab-infra'
       permission-contents: write
   ```

2. **Clonación de `shoplab-infra`**:
   Se clona exclusivamente la rama `main` de `shoplab-infra` utilizando el token efímero generado.

3. **Verificación Previa en Registro**:
   Antes de editar manifiestos, comprueba que la imagen `${IMAGE_NAMESPACE}/${SERVICE_NAME}:${IMAGE_TAG}` exista físicamente en GHCR mediante `docker buildx imagetools inspect`.

4. **Actualización con Kustomize**:
   ```bash
   cd infra/k8s/overlays/dev
   kustomize edit set image "${IMAGE_NAMESPACE}/${SERVICE_NAME}=${IMAGE_NAMESPACE}/${SERVICE_NAME}:${IMAGE_TAG}"
   ```

5. **Validación de Render y Políticas**:
   * Ejecuta `kustomize build . > /tmp/rendered.yaml`.
   * Verifica que ninguna imagen tenga tag `:latest`.
   * Ejecuta el script de validación de políticas de `shoplab-infra`:
     ```bash
     bash infra/scripts/policy-check.sh /tmp/rendered.yaml dev
     ```

6. **Commit y Push GitOps**:
   * Si no hay cambios en el archivo `kustomization.yaml`, finaliza con salida idempotente (`result=no-change`).
   * Si hay cambios, realiza commit con autoría `shoplab-deploy[bot]`:
     ```text
     chore(dev): [LAB-103] despliega <servicio> a dev (<image-tag>)

     Versión: <image-tag>
     Servicio: <servicio>
     Pipeline: <run-url>
     Commit origen: <caller-sha>
     ```
   * Aplica reintentos (`git pull --rebase` hasta 3 veces) para resolver colisiones de concurrencia entre despliegues simultáneos de distintos microservicios.

---

## 4. Códigos de Salida del Despliegue

| Código | Estado | Significado |
|---|---|---|
| `0` | Success | Despliegue completado con éxito (`pushed` o `no-change`). |
| `2` | Exit Usage | Parámetros de entrada faltantes o inválidos (ej. tag no cumple `vX.Y.Z`). |
| `10` | Missing Image | La imagen y tag especificados no existen en GHCR. |
| `11` | Undeclared Service | El servicio no está declarado en la sección `images:` de `kustomization.yaml`. |
| `12` | Policy Failure | `kustomize build` falló o `policy-check.sh` rechazó el render (ej. presencia de `:latest`). |
| `13` | Push Failure | No se pudo realizar el push tras agotar los reintentos por colisión en `main`. |
