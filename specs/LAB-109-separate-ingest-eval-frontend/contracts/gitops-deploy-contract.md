# Contrato de Despliegue GitOps sobre `shoplab-infra`

**Feature**: `LAB-109-separate-ingest-eval-frontend`
**Target**: Automatización de Despliegue y Argo CD

---

## 1. Especificación del Job de Despliegue

Cada repositorio delega en el job reutilizable de despliegue de `shoplab-ci` (o ejecuta el script homologado `deploy-dev.sh`).

### Precondiciones
1. La imagen OCI fue publicada en GHCR con el tag inmutable `:sha-<commit>` o `:vX.Y.Z`.
2. La imagen está firmada con Cosign y cuenta con SBOM adjunto.
3. El secreto de GitHub App `DEPLOY_APP_ID` y `DEPLOY_APP_PRIVATE_KEY` está configurado con permisos exclusivos en `shoplab-infra:contents:write`.

### Flujo de Ejecución
1. Generación de token efímero de GitHub App válido por 60 minutos.
2. Clon de `shoplab-infra` en la rama `main`.
3. Ejecución de Kustomize:
   ```bash
   cd k8s/overlays/dev
   kustomize edit set image ghcr.io/leandroamariles/<servicio>=ghcr.io/leandroamariles/<servicio>:<nuevo-tag>
   ```
4. Validación estricta con `policy-check.sh` de `shoplab-infra`:
   * Falla si la imagen contiene `:latest` o carece de tag.
   * Falla si hay secrets en texto claro.
   * Falla si `kustomize build` genera errores de validación OpenAPI en Kubernetes.
5. Commit firmado por `shoplab-deploy[bot]` con mensaje:
   ```text
   deploy(dev): update <servicio> to <nuevo-tag> from <repo>@<commit>
   ```
6. Push a `main` de `shoplab-infra`.

---

## 2. Reconciliación y Salud en Argo CD

* **Intervalo de Reconciliación**: Pull automático o webhook de GitHub en menos de 60 segundos.
* **Criterio de Éxito**:
  * `Application` en estado `Synced`.
  * Recursos `Deployment`, `Service`, `Ingress` en estado `Healthy`.
  * Rollout completado sin pods en `CrashLoopBackOff` ni `ImagePullBackOff`.
