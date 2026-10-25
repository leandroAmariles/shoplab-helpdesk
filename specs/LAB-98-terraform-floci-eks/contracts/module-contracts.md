# Contratos de módulos Terraform (LAB-98)

Interfaz pública de cada módulo. Las pruebas `terraform test` verifican estos contratos. Nombres en inglés.

## `modules/state`

| Tipo | Nombre | Descripción |
|---|---|---|
| input | `bucket_name` (string) | Nombre del bucket de estado. |
| input | `environment` (string) | Ambiente al que pertenece. |
| output | `bucket_name` | Bucket creado (para `backend.tf`). |

Garantías: bucket con versionado habilitado.

## `modules/eks`

| Tipo | Nombre | Descripción |
|---|---|---|
| input | `cluster_name` (string) | Nombre del clúster. |
| input | `kubernetes_version` (string) | Versión fijada. |
| input | `cluster_mode` (string) | `floci` (defecto) o `kind`; `validation` rechaza otros valores. |
| output | `kubeconfig` (sensible) | Kubeconfig YAML del clúster. |
| output | `host` | Endpoint de la API. |
| output | `cluster_ca_certificate` (sensible) | CA en base64. |
| output | `client_certificate` / `client_key` (sensibles) | Credenciales de conexión si aplican. |

Garantía: ambos modos exponen exactamente las mismas salidas (FR-017).

## `modules/secrets`

| Tipo | Nombre | Descripción |
|---|---|---|
| input | `environment` (string) | Ambiente; forma el prefijo `/shoplab/<ambiente>/`. |
| input | `secrets` (map(string), sensible) | Nombre → valor del secreto; sin valor por defecto. |
| input | `parameters` (map(string)) | Nombre → valor de parámetros no sensibles. |
| output | `secret_names` | Nombres creados en Secrets Manager. |
| output | `parameter_names` | Rutas creadas en SSM. |

Garantía: `validation` falla con mensaje claro si falta un secreto requerido (sin valores por defecto sensibles).

## `modules/platform`

| Tipo | Nombre | Descripción |
|---|---|---|
| input | `secrets_endpoint` (string) | Endpoint de Secrets Manager que usará el `ClusterSecretStore`. |
| input | `chart_versions` (object) | Versión exacta por chart; `validation` rechaza `latest`/vacío. |
| input | `argocd_initial_app` (object) | `Application` inicial a sincronizar. |
| output | `argocd_url` | URL de acceso a Argo CD. |
| output | `installed_components` | Mapa componente → versión instalada. |

Salida del entorno raíz `envs/local`: `kubeconfig_path` (archivo local ignorado por git con el kubeconfig).

Requiere proveedores `helm` y `kubernetes` configurados por el entorno raíz.

## Interfaz de comandos (`Makefile`)

| Comando | Efecto |
|---|---|
| `make up` | compose up (espera salud) → bootstrap apply → init con backend → `terraform apply`. |
| `make down` | `terraform destroy` → destroy bootstrap → compose down. |
| `make test` | `fmt -check`, `validate`, `terraform test` (plan con mocks). |
| `make test-integration` | `terraform test` con apply contra Floci. |
| `make scan` | gitleaks sobre el repo. |

## Variables de entorno

`TF_VAR_secrets` (o `secrets.auto.tfvars` ignorado) para valores; credenciales ficticias `AWS_ACCESS_KEY_ID=test`,
`AWS_SECRET_ACCESS_KEY=test`; `AWS_ENDPOINT_URL=http://localhost:4566`.
