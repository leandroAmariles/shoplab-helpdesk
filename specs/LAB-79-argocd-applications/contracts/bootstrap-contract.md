# Contrato: arranque desde Terraform, ingress y acceso local

## Terraform ↔ `root.yaml`

| Aspecto | Contrato |
|---|---|
| Fuente de verdad | `k8s/argocd/root.yaml`; `terraform/envs/local/main.tf` la lee con `yamldecode(file(...))` |
| Entrada | `module.platform.argocd_initial_app = local.argocd_root`: el manifiesto completo de `root.yaml` decodificado (tipo `any`) |
| Validaciones | `kind == "Application"`, `spec.source.repoURL` sin `CAMBIA-ESTE-OWNER` y `spec.source.targetRevision == "main"` |
| Valor por defecto | ninguno (se elimina `guestbook`); un valor ausente es un error de `terraform validate` |
| Orden | la raíz se crea después de Argo CD (`depends_on = [helm_release.argo_cd]`, ya existente) |
| Idempotencia | un segundo `apply` no cambia la raíz si `root.yaml` no cambió |
| Cambios en la raíz | solo por `terraform apply`; las hijas cambian solo con git |

El chart local `argocd-application` pasa a renderizar el manifiesto recibido sin modificarlo (`toYaml`): ya no fija
`CreateNamespace=true` ni ningún otro campo, así que `root.yaml` es la única definición de la raíz.

`terraform/envs/local` pasa a crear por defecto las claves de Secrets Manager de `dev`, `staging` y `prod`
(`app_environments`), porque la raíz despliega los tres y sus `ExternalSecret` las necesitan.

## Ingress de Argo CD

| Aspecto | Contrato |
|---|---|
| Creado por | valores del chart `argo-cd` en `terraform/modules/platform/argocd.tf` (no por una `Application`) |
| Variable | `argocd_host` (por defecto `argocd.localtest.me`; el nombre de ambiente real lo define quien despliega) |
| Clase | `nginx` |
| Protocolo | HTTP (`configs.params.server.insecure=true`); TLS fuera de alcance |
| Credenciales | ninguna en el repositorio; contraseña inicial vía `argocd-initial-admin-secret` |
| Dominios | solo `*.localtest.me`; nunca de producción |

## Acceso local

```bash
export KUBECONFIG=terraform/envs/local/kubeconfig.yaml
kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80
# navegador: http://argocd.localtest.me:8080  (usuario: admin)
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

`output "argocd_url"` devuelve `http://argocd.localtest.me:8080` y su descripción incluye el `port-forward`.

## Salidas y pruebas que cambian

| Elemento | Cambio |
|---|---|
| `module.platform` output `argocd_url` | de `http://localhost:8080` (port-forward al servidor) a `http://argocd.localtest.me:8080` |
| `platform.tftest.hcl` | casos: ingress habilitado con el host de `argocd_host`; `initial_app` obligatorio y sin `guestbook` |
| `scripts/smoke.sh` | espera `shoplab-root` y `shoplab-dev` `Synced/Healthy`; `shoplab-staging` y `shoplab-prod` solo deben existir |
| `docs/terraform-local.md` | paso 4 usa el ingress; enlaza a `docs/gitops.md` |
