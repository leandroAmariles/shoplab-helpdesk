---

description: "Task list for LAB-98 - Terraform sobre Floci (EKS local)"
---

# Tasks: Infraestructura como código sobre un AWS emulado en local (EKS local)

**Input**: Design documents from `/specs/LAB-98-terraform-floci-eks/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/module-contracts.md, quickstart.md

**Tests**: Incluidos: la spec exige pruebas con `terraform test` (FR-015). Se escriben antes o junto con cada módulo.

**Organization**: Tareas agrupadas por historia de usuario. Salvo que se indique lo contrario, las rutas son relativas
al repositorio `shoplab-infra` (`terraform/…`, `docs/…`). La excepción es el ADR (T039), que va en `shoplab-helpdesk`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de la spec (US1…US6)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repositorio, emulador fijado y spike que despeja los riesgos de `research.md`

- [X] T001 Crear el repositorio `shoplab-infra` (GitHub + clon local) con la estructura de directorios del plan (`terraform/modules/{state,eks,secrets,platform}`, `terraform/envs/local/bootstrap`, `docs/`) y un `README.md` mínimo
- [X] T002 [P] Crear `.gitignore` (`*.tfstate*`, `.terraform/`, `secrets.auto.tfvars`, `kubeconfig*`) y `.gitleaks.toml` en la raíz de `shoplab-infra`
- [X] T003 [P] Crear `Makefile` en la raíz con los objetivos vacíos `up`, `down`, `test`, `test-integration`, `scan` (contrato en `contracts/module-contracts.md`)
- [X] T004 Spike con Floci: crear `docker-compose.yml` con `floci/floci` a un tag de versión estable (no `latest`), puerto 4566, `/var/run/docker.sock`, `FLOCI_HOSTNAME` y `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` fijada a un tag de `rancher/k3s`; comprobar (a) escrituras condicionales de S3 para `use_lockfile`, (b) cómo se obtiene el endpoint/CA/kubeconfig de `aws_eks_cluster`, (c) recursos mínimos; registrar tags exactos y resultados en `specs/LAB-98-terraform-floci-eks/research.md` (R1, R2, R4) en `shoplab-helpdesk`

**Checkpoint**: tags fijados y riesgos R2/R4 resueltos o con plan B decidido

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Estado remoto operativo y proveedor AWS apuntando a Floci; bloquea todas las historias

**⚠️ CRITICAL**: Ninguna historia puede empezar hasta completar esta fase

- [X] T005 [P] Crear `terraform/modules/state/tests/state.tftest.hcl` (`command = plan`, `mock_provider "aws"`): bucket con versionado habilitado y output `bucket_name`
- [X] T006 Implementar `terraform/modules/state/{main,variables,outputs,versions}.tf`: `aws_s3_bucket` (`force_destroy = true`, ver R2) + `aws_s3_bucket_versioning` con inputs `bucket_name` y `environment`
- [X] T007 Crear `terraform/envs/local/bootstrap/{main,providers,versions}.tf` con backend local y provider `aws` apuntando a `http://localhost:4566` (`skip_credentials_validation`, `skip_requesting_account_id`, `skip_metadata_api_check`, `s3_use_path_style`, credenciales `test`/`test`)
- [X] T008 Crear `terraform/envs/local/{providers,variables,versions}.tf` (provider `aws` a Floci, `required_version >= 1.10` con versión exacta fijada) y el objetivo `bootstrap` del `Makefile` (`init`, `apply`, luego `init -backend-config`); depende de T004, T007

**Checkpoint**: `make bootstrap` crea el bucket de estado en Floci; `terraform test` del módulo `state` pasa

---

## Phase 3: User Story 1 - Levantar el entorno completo con un solo comando (Priority: P1) 🎯 MVP

**Goal**: De cero a Argo CD sincronizando con un único flujo de aplicar

**Independent Test**: `make up` en una máquina limpia; clúster activo, 4 componentes sanos, Argo CD `Synced/Healthy` (quickstart §2, §3, §9)

### Tests for User Story 1 ⚠️

> Escribirlos primero y comprobar que fallan antes de implementar

- [X] T009 [P] [US1] `terraform/modules/eks/tests/eks.tftest.hcl` (plan + mocks): outputs `kubeconfig`, `host`, `cluster_ca_certificate`; `cluster_mode` rechaza valores distintos de `floci`/`kind`
- [X] T010 [P] [US1] `terraform/modules/platform/tests/platform.tftest.hcl` (plan + mocks): 4 `helm_release` con `chart_versions` exactas; `validation` rechaza `latest` o vacío
- [X] T011 [P] [US1] `terraform/envs/local/tests/apply.tftest.hcl` (`command = apply` contra Floci): clúster activo y componentes instalados

### Implementation for User Story 1

- [X] T012 [US1] Implementar `terraform/modules/eks/{main,variables,outputs,versions}.tf` en modo `floci` (`aws_eks_cluster`, `aws_eks_node_group` si Floci lo exige) con outputs `kubeconfig` (sensible), `host`, `cluster_ca_certificate` (sensible), `client_certificate`/`client_key` (sensibles); incluye `aws_vpc`, `aws_subnet` y `aws_iam_role` requeridos por Floci y un `data "external"` con `scripts/k3s-kubeconfig.sh` que extrae el certificado de cliente de `/etc/rancher/k3s/k3s.yaml` del contenedor `floci-aws-eks-<cluster>` (R4)
- [X] T013 [US1] Componer `eks` en `terraform/envs/local/main.tf` y exponer `kubeconfig_path` (archivo local ignorado por git); configurar los providers `helm` y `kubernetes` en `terraform/envs/local/providers.tf` desde las salidas de `eks` (R5)
- [X] T014 [P] [US1] `terraform/modules/platform/ingress_nginx.tf`: `helm_release` de ingress-nginx con `version` exacta, `wait = true` y `timeout`
- [X] T015 [P] [US1] `terraform/modules/platform/external_secrets.tf`: `helm_release` de external-secrets y `ClusterSecretStore` hacia Secrets Manager de Floci (`secrets_endpoint`, credenciales ficticias en un `kubernetes_secret`), creado solo después del chart (CRDs)
- [X] T016 [P] [US1] `terraform/modules/platform/monitoring.tf`: `helm_release` de kube-prometheus-stack con `version` exacta
- [X] T017 [US1] `terraform/modules/platform/argocd.tf`: `helm_release` de argo-cd (último, con `depends_on` de los otros tres) y `Application` inicial `argocd_initial_app`; outputs `argocd_url` e `installed_components`; depende de T014, T015, T016
- [X] T018 [US1] Definir `terraform/modules/platform/{variables,outputs,versions}.tf` y componer `platform` en `terraform/envs/local/main.tf` con `chart_versions` fijadas en `terraform/envs/local/variables.tf`; depende de T017
- [X] T019 [US1] Implementar el objetivo `up` del `Makefile`: `docker compose up -d`, espera de salud de Floci (mensaje claro si no responde o si el puerto 4566 está ocupado), `bootstrap`, `terraform init -backend-config`, `terraform apply`; debe poder reanudarse tras una interrupción
- [X] T020 [US1] Validar US1: aplicar desde cero 3 veces (SC-002), re-aplicar sin cambios con `plan -detailed-exitcode` (SC-004), interrumpir y reanudar, y repetir con la salida a `*.amazonaws.com` bloqueada (SC-005); anotar resultados en `quickstart.md`

**Checkpoint**: US1 funcional y verificable por sí sola (MVP)

---

## Phase 4: User Story 2 - Destruir y reconstruir el entorno sin residuos (Priority: P1)

**Goal**: Un solo comando borra todo y se puede reconstruir

**Independent Test**: `make down`, comprobar que no quedan contenedores ni recursos, `make up` de nuevo (quickstart §7)

- [X] T021 [P] [US2] Crear `scripts/verify-clean.sh` que falla si quedan contenedores de Floci/k3s/kind, los creados por Floci fuera de compose (`floci-aws-*`) o sus redes (`floci-aws-vpc-*`); `down` también debe eliminarlos
- [X] T022 [US2] Implementar el objetivo `down` del `Makefile`: `terraform destroy` del entorno → destroy de `bootstrap` → `docker compose down -v`; ejecutar `scripts/verify-clean.sh` al final
- [X] T023 [US2] Ajustar `terraform/modules/platform/*.tf` para borrado fiable (`timeout`, `cleanup_on_fail`, `depends_on` inverso, destruir la `Application` de Argo CD antes del chart) y que un fallo indique el recurso afectado y sea reintentable
- [X] T024 [US2] Validar US2: `make up` → `make down` → `make up` (estado equivalente, SC-003), y destruir con Argo CD sincronizando; anotar resultados en `quickstart.md`

**Checkpoint**: US1 y US2 funcionan de forma independiente

---

## Phase 5: User Story 3 - Estado remoto con bloqueo y módulos por ambiente (Priority: P1)

**Goal**: Estado remoto con `use_lockfile`, estados independientes por ambiente y estructura de módulos verificable

**Independent Test**: dos `apply` concurrentes (el segundo es rechazado) y revisión de estructura (quickstart §4)

- [X] T025 [P] [US3] Crear `scripts/check-layout.sh` que falla si falta alguno de `state`, `eks`, `secrets`, `platform` o si un ambiente no tiene su raíz en `terraform/envs/`
- [X] T026 [US3] Crear `terraform/envs/local/backend.tf` con backend `s3`, `use_lockfile = true`, endpoints de Floci y `key` parametrizada por ambiente (`-backend-config` desde el `Makefile` con la variable `ENV`)
- [X] T027 [US3] Verificar el bloqueo: lanzar dos `apply` simultáneos y confirmar que el segundo falla sin corromper el estado (SC-009); si Floci no soporta escrituras condicionales, aplicar el plan B de T004 y registrarlo en el ADR
- [X] T028 [US3] Verificar aislamiento por ambiente: inicializar con dos valores de `ENV` y comprobar claves de estado distintas en el bucket (US3-3)

**Checkpoint**: US1–US3 completas (las tres historias P1)

---

## Phase 6: User Story 4 - Secretos y parámetros sin valores en git (Priority: P2)

**Goal**: Secretos en Secrets Manager y parámetros en SSM, sincronizados al clúster, sin valores en el repositorio

**Independent Test**: aplicar con valores externos, ver el secreto sincronizado y escanear el repositorio (quickstart §1, §5)

- [X] T029 [P] [US4] `terraform/modules/secrets/tests/secrets.tftest.hcl` (plan + mocks): `validation` falla con mensaje claro si falta un secreto requerido; outputs `secret_names` y `parameter_names`; `secrets` es sensible
- [X] T030 [US4] Implementar `terraform/modules/secrets/{main,variables,outputs,versions}.tf`: `aws_secretsmanager_secret(_version)` y `aws_ssm_parameter` con prefijo `/shoplab/<environment>/`; `secrets` como `map(string)` sensible sin valor por defecto
- [X] T031 [US4] Componer `secrets` en `terraform/envs/local/main.tf`, crear `terraform/envs/local/secrets.auto.tfvars.example` solo con marcadores y asegurar que `secrets.auto.tfvars` está en `.gitignore`
- [X] T032 [US4] Añadir un `ExternalSecret` de demostración (namespace `demo`) en `terraform/envs/local/main.tf` y comprobar que el secreto aparece en el clúster (US4-2)
- [X] T033 [US4] Implementar el objetivo `scan` del `Makefile` (gitleaks sobre el árbol y el historial) y verificar 0 credenciales reales (SC-006) y el mensaje al faltar valores (US4-3)

**Checkpoint**: US4 funcional y verificable

---

## Phase 7: User Story 5 - Versiones fijadas, pruebas y ADR (Priority: P2)

**Goal**: Reproducibilidad, pruebas ejecutables en local y decisión documentada

**Independent Test**: `make test`, comprobar pines y leer el ADR (quickstart §6)

- [X] T034 [P] [US5] Crear `scripts/check-pins.sh` que falla ante `latest` o rangos abiertos en `*.tf`, `docker-compose.yml` y la imagen k3s (SC-007)
- [X] T035 [US5] Generar y versionar `.terraform.lock.hcl` en `terraform/envs/local/` y `terraform/envs/local/bootstrap/` con `terraform providers lock` para `windows_amd64`, `linux_amd64`, `darwin_arm64` y `darwin_amd64`
- [X] T036 [P] [US5] Implementar el objetivo `test` (`terraform fmt -check`, `validate`, `scripts/check-layout.sh`, `scripts/check-pins.sh`, `terraform test` con mocks) y `test-integration` (apply contra Floci) en el `Makefile`
- [X] T037 [US5] Comprobar que las pruebas detectan un contrato roto (p. ej. quitar un output o poner `latest`) y fallan indicando el comportamiento incumplido (US5-4)
- [X] T038 [P] [US5] Revisar la plantilla y numeración del registro en `docs/adr/` de `shoplab-helpdesk` (LAB-16) para el ADR de T039
- [X] T039 [US5] Redactar `docs/adr/<NNNN>-terraform-floci-frente-a-kind-localstack.md` en `shoplab-helpdesk` (en español, plantilla del registro) comparando Terraform + Floci con kind/k3d y LocalStack, incluyendo el respaldo con kind y los resultados del spike (T004, T027); enlazarlo desde `docs/` de `shoplab-infra`; actualizar el índice del registro

**Checkpoint**: US5 verificable

---

## Phase 8: User Story 6 - Respaldo con kind (Priority: P3)

**Goal**: `cluster_mode = "kind"` produce el mismo contrato de salida y `platform` funciona sin cambios

**Independent Test**: `make up CLUSTER_MODE=kind` instala los mismos componentes (quickstart §8)

- [X] T040 [P] [US6] Ampliar `terraform/modules/eks/tests/eks.tftest.hcl` con casos `cluster_mode = "kind"`: mismas salidas que en `floci`
- [X] T041 [US6] Añadir el modo `kind` a `terraform/modules/eks/main.tf` con `tehcyx/kind` (recursos condicionados por `cluster_mode`), con la versión del provider y de la imagen de nodo fijadas
- [X] T042 [US6] Pasar `CLUSTER_MODE` desde el `Makefile` a `TF_VAR_cluster_mode`, validar que `platform` instala los mismos componentes/versiones sobre kind y que en modo por defecto no se crea ningún clúster de kind (US6-3)

**Checkpoint**: todas las historias completas

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T043 [P] Escribir `docs/terraform-local.md` en `shoplab-infra`: requisitos y recursos mínimos, `make up`/`down`, valores de secretos, liberación de bloqueo huérfano (`force-unlock`), recuperación tras reinicio del emulador, puerto ocupado, modo kind (FR-014, edge cases)
- [X] T044 Ejecutar `quickstart.md` completo desde una máquina limpia siguiendo solo `docs/` (SC-010) y actualizar `plan.md`/`research.md` con desviaciones
- [ ] T045 Revisar `git status`/`git diff --staged` en ambos repositorios (sin archivos sueltos ni secretos) y abrir el PR con título `feat(infra): [LAB-98] terraform sobre floci con eks local`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Foundational (2)** → historias. T004 (spike) condiciona T008, T012 y T027.
- **US1 (3)**: depende de la fase 2. **US2 (4)**: depende de US1 (necesita un entorno aplicado).
- **US3 (5)**: T026 depende de la fase 2; T027–T028 requieren US1 para tener un entorno que bloquear.
- **US4 (6)**: depende de US1 (necesita `platform` para sincronizar). **US5 (7)**: T034–T037 pueden ir en paralelo con US4; T039 espera a T027.
- **US6 (8)**: depende de US1. **Polish (9)**: tras las historias deseadas.

### Parallel Opportunities

- Setup: T002, T003 en paralelo. Foundational: T005 antes de T006 (test primero).
- US1: T009, T010, T011 en paralelo; T014, T015, T016 en paralelo (archivos distintos).
- US4, US5 y US6 pueden avanzar en paralelo una vez completa US1.

### Parallel Example: User Story 1

```text
Task: "terraform/modules/eks/tests/eks.tftest.hcl"            (T009)
Task: "terraform/modules/platform/tests/platform.tftest.hcl"  (T010)
Task: "terraform/modules/platform/ingress_nginx.tf"           (T014)
Task: "terraform/modules/platform/external_secrets.tf"        (T015)
Task: "terraform/modules/platform/monitoring.tf"              (T016)
```

---

## Implementation Strategy

### MVP First (US1)

1. Fases 1 y 2 (incluye el spike T004: si el EKS de Floci no funciona, decidir ya el uso de kind como modo principal temporal).
2. Fase 3 (US1) y **validar** `make up`.
3. Seguir con US2 y US3 (resto de las P1), luego US4–US6.

### Incremental Delivery

Cada historia añade valor sin romper las anteriores; el PR puede entregarse tras US3 y completar P2/P3 en PRs siguientes si se prefiere.

## Notes

- La historia no añade endpoints HTTP, por lo que no se genera `requests.http`.
- CI de Terraform queda fuera de alcance (ver supuestos de la spec).
- Commits: `LAB-98 type(scope): descripción en inglés`; una tarea o grupo lógico por commit.
- Las versiones exactas se resuelven en T004 y T035; no inventarlas.
