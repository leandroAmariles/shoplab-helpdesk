# Research: LAB-98 Terraform sobre Floci (EKS local)

Fuentes: README de [floci-io/floci](https://github.com/floci-io/floci) y su ficha en Docker Hub. Lo que no está
verificado se marca como **a validar en T001 (spike)**; no se inventan versiones.

## R1. Emulador y modo de ejecución

- **Decisión**: Floci (`floci/floci`) en docker compose, puerto 4566, con `/var/run/docker.sock` montado y
  `FLOCI_HOSTNAME` definido; imagen fijada a un tag de versión (las estables salen el día 15 y el último de cada mes).
- **Justificación**: licencia MIT, sirve S3, Secrets Manager, SSM y EKS (k3s real) y funciona con el provider AWS
  estándar (compatibilidad probada con Terraform >= 1.10 según su README).
- **Alternativas**: LocalStack (EKS real es de pago), kind/k3d solos (no emulan AWS: no practican S3/Secrets
  Manager/SSM ni el módulo `eks`).
- **Resultado del spike (T004, 2026-10-24)**: tag estable `floci/floci:2.3.0` (los tags `nightly-*` no se usan);
  imagen k3s fijada con `FLOCI_SERVICES_EKS_DEFAULT_IMAGE=rancher/k3s:v1.35.9-k3s1` (por defecto sería `latest`,
  que violaría FR-011). Arranca en < 10 s y queda `healthy`. Con Terraform 1.15.8 y provider `hashicorp/aws` sin
  fijar aún (se fija en T008).

## R2. Estado remoto con bloqueo nativo

- **Decisión**: backend `s3` con `use_lockfile = true`, endpoints apuntando a Floci, `skip_credentials_validation`,
  `skip_requesting_account_id`, `skip_metadata_api_check`, `use_path_style = true`; credenciales ficticias.
- **Justificación**: `use_lockfile` (Terraform >= 1.10) bloquea con un objeto `.tflock` usando escrituras
  condicionales de S3; evita DynamoDB.
- **Riesgo**: el README de Floci no menciona escrituras condicionales (`If-None-Match`) en S3. Si no se
  soportan, el bloqueo no es exclusivo. **Spike T001**: lanzar dos `apply` concurrentes y comprobar el rechazo
  (SC-009). Mitigación si falla: reportar a Floci / fijar versión que lo soporte; como último recurso documentar la
  limitación en el ADR (no sustituir por DynamoDB sin actualizar la spec).

## R3. Bootstrap del estado

- **Decisión**: `envs/local/bootstrap` aplica el módulo `state` con backend local y crea el bucket versionado;
  el `Makefile` ejecuta después `terraform init -backend-config=…` en `envs/local`.
- **Justificación**: resuelve el huevo-y-gallina del backend sin scripts ad hoc con la CLI de AWS.
- **Alternativa**: crear el bucket con `aws s3 mb`; descartada por no ser Terraform y requerir otra herramienta.
- **Nota**: el estado del bootstrap es local y desechable (en `.gitignore`); `make down` lo destruye al final.

## R4. Módulo `eks` y kubeconfig

- **Decisión**: recurso `aws_eks_cluster` (y `aws_eks_node_group` si Floci lo exige) contra Floci; el módulo
  produce un output sensible `kubeconfig` (YAML) y los datos de conexión (`host`, `cluster_ca_certificate`,
  credenciales) para configurar `helm`/`kubernetes`.
- **Resultado del spike: RESUELTO con una variación.** `aws_eks_cluster` (requiere `aws_vpc` + `aws_subnet` +
  `aws_iam_role` en Floci) tarda ~17 s y devuelve `endpoint = https://localhost:<puerto>` (6501 en la prueba) y
  `certificate_authority`; el nodo queda `Ready` con k3s v1.35.9. **Autenticación**: el token de
  `aws_eks_cluster_auth` sale con `X-Amz-Expires=0` y la API responde `401 invalid bearer token` (también con una
  clave IAM real; Floci rechaza `test`/`test`). Solución adoptada: construir el kubeconfig con el certificado de
  cliente de `/etc/rancher/k3s/k3s.yaml` del contenedor `floci-aws-eks-<cluster>` (`docker exec`), sustituyendo el
  servidor por el `endpoint` devuelto. Se implementa con `data "external"` + `scripts/k3s-kubeconfig.sh`;
  requiere CLI de Docker (ya es requisito). Probado con `kubectl get nodes/pods`.
- **Hallazgos operativos**: Floci crea contenedores y redes fuera de compose (`floci-aws-ecr-registry`,
  `floci-aws-eks-<cluster>`, red `floci-aws-vpc-*`) que `docker compose down` no elimina → `down` y
  `verify-clean.sh` deben limpiarlos. En Git Bash hay que usar `MSYS_NO_PATHCONV=1` con `docker exec` y rutas
  Windows para `kubectl --kubeconfig`.
- **Respaldo kind**: variable `cluster_mode = "floci" | "kind"`; en `kind` se crea el clúster con el proveedor
  `tehcyx/kind` y se devuelve el mismo contrato de salida. Modo por defecto: `floci` (FR-017).

## R5. Proveedores `helm`/`kubernetes` con un clúster creado en el mismo apply

- **Decisión**: configurar ambos proveedores desde las salidas del módulo `eks` en `providers.tf`; el orden se
  garantiza con `depends_on` y dependencias implícitas por esas salidas. `platform` usa `wait = true` y
  `timeout` en cada `helm_release`.
- **Riesgo conocido**: configurar proveedores con valores desconocidos en plan es frágil (p. ej. al destruir).
- **Mitigación**: si T001/T0xx muestran fallos, dividir `envs/local` en dos raíces (`cluster` y `platform`) con
  `terraform_remote_state` o salidas por archivo, manteniendo el contrato de módulos; el `Makefile` ya encadena
  etapas, así que no cambia la experiencia de "un comando".

## R6. Orden e instalación de plataforma

- **Decisión**: orden por dependencias: `ingress-nginx` → `external-secrets` (+ `ClusterSecretStore` hacia Secrets
  Manager de Floci con endpoint y credenciales ficticias) → `kube-prometheus-stack` → `argo-cd`. CRDs de ESO y de
  prometheus instalados por sus charts antes de crear objetos que las usen (`kubernetes_manifest` solo tras el
  chart; si el plan falla por CRD ausente, usar `helm_release` auxiliar o `kubectl_manifest`).
- **Justificación**: evita condiciones de carrera (FR-010) y deja Argo CD como último paso, sincronizando una
  `Application` inicial mínima (app-of-apps vacía o repositorio de `shoplab-infra`), suficiente para "Argo CD
  sincronizando" (SC-002).
- **A validar**: qué `Application` inicial se considera "sincronizando" sin las apps de ShopLab (fuera de alcance).

## R7. Secretos sin valores en git

- **Decisión**: variables `sensitive = true` alimentadas por `TF_VAR_*` o por `secrets.auto.tfvars` (ignorado, con
  `secrets.auto.tfvars.example` de marcadores); módulo `secrets` crea `aws_secretsmanager_secret(_version)` y
  `aws_ssm_parameter`. Si falta un valor, una `validation` en la variable falla con un mensaje claro (US4-3).
- **Salvaguarda**: gitleaks en el `Makefile` (`make scan`) y en las pruebas de pre-commit documentadas.
- **Nota**: el estado remoto contiene los valores en claro; está en el emulador local, no en git, y se documenta.

## R8. Pruebas con `terraform test`

- **Decisión**: dos niveles. (1) `command = plan` con `mock_provider` por módulo para validar contrato
  (variables, outputs, validaciones, ausencia de `latest`); (2) una prueba de integración `command = apply`
  contra Floci (marcada para ejecución local, no en CI de esta historia).
- **Justificación**: la primera corre sin emulador (SC-008); la segunda cubre el flujo real.

## R9. Versionado y reproducibilidad

- **Decisión**: `required_version` y `required_providers` con versiones exactas o `~>` en el último componente;
  `.terraform.lock.hcl` versionado con hashes de las plataformas usadas (`terraform providers lock` para
  windows/linux/darwin); charts con `version` exacta; imagen Floci y k3s por tag exacto (o digest).
- **Comprobación**: test de contrato que falla si aparece `latest` o un rango abierto (`>=` sin tope).

## R10. Ubicación de artefactos

- **Decisión**: spec/plan/tasks en `shoplab-helpdesk/specs/`; código en `shoplab-infra` (repo por crear:
  tarea previa T000). ADR en `docs/adr/` de `shoplab-helpdesk` (registro LAB-16) y enlazado desde `docs/` de
  `shoplab-infra`.
