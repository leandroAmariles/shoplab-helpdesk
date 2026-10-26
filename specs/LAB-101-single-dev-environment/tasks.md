# Tasks: Consolidar shoplab-infra en un solo ambiente dev

**Input**: Design documents from `/specs/LAB-101-single-dev-environment/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: no se pide una suite nueva. La verificación es la existente (`make check`, `make smoke`,
`terraform test`, `scripts/tests/policy-check.test.sh`), actualizada junto al cambio, más las comprobaciones
de [quickstart.md](quickstart.md) (diff del overlay `dev`, búsqueda de residuos, validación en vivo).

**Organization**: tareas agrupadas por historia de usuario, en orden de prioridad (US1, US2, US3 y US5 son P1;
US4 es P2). Todas las rutas son relativas al repositorio `shoplab-infra` salvo las marcadas con
`shoplab-helpdesk`. Rutas, nombres y valores exactos: [contracts/](contracts/) y [data-model.md](data-model.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: partir de `main` actualizado, fijar la línea base y limpiar cualquier entorno local anterior.

- [X] T001 En `shoplab-infra`: `git switch main && git pull --ff-only`, comprobar árbol limpio y crear la rama `chore/LAB-101-single-dev-environment` (misma convención que la rama de `shoplab-helpdesk`)
- [ ] T002 [P] Con la rama aún sin cambios, ejecutar `make check` y anotar en `shoplab-helpdesk` `specs/LAB-101-single-dev-environment/validation.md` el commit base de `shoplab-infra` y el resultado (línea base) — PENDIENTE: no ejecutado, este equipo no tiene `make`, `kubeconform` ni `yq`
- [X] T003 [P] Guardar la salida de referencia `kustomize build k8s/overlays/dev > <directorio temporal fuera del repo>/dev-before.yaml` para el diff de FR-003 (T008); no se versiona
- [X] T004 Si existe un entorno local anterior: con la configuración de `main` (`ENV=local`) ejecutar `make down` y `bash scripts/verify-clean.sh`; si no responde, `docker compose down -v --remove-orphans`. Conservar `terraform/envs/local/secrets.auto.tfvars` (ignorado por git) para T018. Anotar lo ejecutado en `validation.md` (FR-014, research D4)

---

## Phase 2: Foundational (Blocking Prerequisites)

Sin tareas bloqueantes: la historia solo elimina y renombra, y cada historia toca archivos distintos. La única
dependencia entre historias es que T012 (`kustomize-build.sh`) debe llegar en el mismo cambio que T007
(eliminar `argocd-local`), o `make kustomize` fallaría; ver Dependencies.

---

## Phase 3: User Story 1 - Un solo ambiente definido en el repositorio de infraestructura (Priority: P1) 🎯 MVP

**Goal**: `shoplab-infra` define solo `base/` + overlay `dev` + Application de `dev`, y lo que se despliega en
`dev` no cambia.

**Independent Test**: `ls k8s/overlays` → `dev`; `kustomize build k8s/overlays/dev` idéntico a `dev-before.yaml`;
`kustomize build k8s/argocd` rinde una sola Application (`shoplab-dev`).

- [X] T005 [P] [US1] Eliminar los overlays: `git rm -r k8s/overlays/staging k8s/overlays/prod` (contracts/infra-layout.md, "Se elimina")
- [X] T006 [P] [US1] Eliminar `k8s/argocd/application-staging.yaml` y `k8s/argocd/application-prod.yaml` con `git rm`, y dejar `k8s/argocd/kustomization.yaml` con `resources: [application-dev.yaml]`; ajustar su comentario de cabecera a un solo ambiente (conservar la nota de que `root.yaml` NO se lista)
- [X] T007 [P] [US1] Eliminar `k8s/argocd-local/` con `git rm -r` y editar `k8s/argocd/root.yaml`: `spec.source.path: k8s/argocd`; reescribir el comentario de cabecera (sin "tres ambientes", sin `argocd-local`, ruta `terraform/envs/dev/main.tf`; conservar que cambiarlo exige `terraform apply` y las exigencias de las políticas: `targetRevision: main`, `repoURL` sin placeholder y finalizer)
- [X] T008 [US1] Verificar FR-003: `kustomize build k8s/overlays/dev | diff - <ruta>/dev-before.yaml` sin diferencias
- [X] T009 [US1] Verificar el contrato de Applications: `kustomize build k8s/argocd` rinde exactamente una `Application` llamada `shoplab-dev` con `targetRevision: main` (`yq eval --no-doc 'select(.kind == "Application") | .metadata.name'`) y no contiene `shoplab-root`
- [X] T010 [US1] Comprobar que no quedan referencias en manifiestos: `grep -rn -E "staging|prod|argocd-local" k8s/` sin resultados

**Checkpoint**: la estructura de `k8s/` coincide con contracts/infra-layout.md; `make kustomize` aún no pasa hasta T012.

---

## Phase 4: User Story 2 - El pipeline valida solo dev (Priority: P1)

**Goal**: el pipeline construye y valida únicamente `dev` (y el directorio de Applications), con las mismas
políticas y los mismos contextos exigidos por la protección de `main`.

**Independent Test**: `make kustomize policies test-policies` pasa y `.rendered/` contiene `dev.yaml`,
`argocd.yaml` y `argocd-root.yaml`; un `:latest` en `dev` sigue haciendo fallar `make policies`.

- [X] T011 [US2] `versions.env`: `OVERLAYS=dev` y reescribir el comentario de `ARGOCD_DIR` ("Application del ambiente dev y su raíz root.yaml"); no tocar `ARGOCD_READY_APPS` ni `ARGOCD_PRESENT_APPS`
- [X] T012 [US2] `scripts/kustomize-build.sh` (contracts/pipeline-checks.md): quitar `"${ARGOCD_DIR}-local|argocd-local"` de `targets`; en el bucle de la raíz iterar solo `argocd`; en el `case` de `root_path` aceptar únicamente `"$ARGOCD_DIR"` (y ajustar su mensaje de error); actualizar la cabecera y los comentarios que citan `staging`, `prod`, `argocd-local` o "una Application por ambiente"
- [X] T013 [P] [US2] `scripts/smoke.sh`: reescribir el comentario de cabecera (ya no espera "las de staging y prod") y el comentario previo a la espera de Argo CD (un solo ambiente; la limitación del `StorageClass` se conserva); sin cambios de lógica
- [ ] T014 [US2] Ejecutar `make kustomize policies test-policies`; confirmar que `kubeconform` valida `k8s/overlays/dev` y `k8s/argocd` (más `argocd-root.yaml`) y que `policy-check.test.sh` sigue detectando sus casos malos y aceptando los buenos — PENDIENTE: requiere `kubeconform` y `yq` (los cubre la CI del PR); en local solo se verificó `kustomize build` de `dev` y `k8s/argocd` (T008–T009) y `bash -n` de los scripts
- [ ] T015 [US2] Regresión negativa (no se commitea): cambiar temporalmente una imagen de `k8s/overlays/dev/kustomization.yaml` a `:latest`, comprobar que `make policies` falla y revertir con `git checkout -- k8s/overlays/dev` — PENDIENTE: requiere `yq` (la CI y `policy-check.test.sh` cubren la regla)
- [X] T016 [P] [US2] Confirmar que `.github/workflows/ci.yml` y `.github/rulesets/main-protection.json` no necesitan cambios (jobs `changes`, `static`, `kustomize`, `trivy`, `plan`, `smoke`, `ci-gate`; contextos exigidos `ci-gate` y `pr-title`); solo se editan si un comentario cita `staging`/`prod` (`grep -n -E "staging|prod" .github -r`)

**Checkpoint**: `make kustomize policies test-policies` verde; los contextos de la protección de `main` no cambian.

---

## Phase 5: User Story 3 - Terraform con un único entorno raíz `dev` (Priority: P1)

**Goal**: `terraform/envs/dev` es el único entorno raíz; el clúster, el bucket y los secretos usan el nombre `dev`;
ya no se ofrecen ambientes de aplicación adicionales.

**Independent Test**: `ls terraform/envs` → `dev`; `make test` y `make lint` pasan; ningún archivo menciona
`envs/local`, `shoplab-local` ni `app_environments`.

- [X] T017 [US3] `git mv terraform/envs/local terraform/envs/dev` (mueve también `.terraform.lock.hcl`, `bootstrap/`, `charts/` y `tests/`)
- [X] T018 [US3] Restaurar el archivo ignorado `terraform/envs/dev/secrets.auto.tfvars` (el guardado en T004 o copiando `secrets.auto.tfvars.example`); no se commitea
- [X] T019 [P] [US3] `terraform/envs/dev/variables.tf`: `variable "environment"` con `default = "dev"` y eliminar por completo `variable "app_environments"` (incluida su validación `dev/staging/prod`)
- [X] T020 [US3] `terraform/envs/dev/main.tf`: eliminar `module "app_secrets"` y su comentario (research D3: colisionaría con `module.secrets`); actualizar el comentario de `local.argocd_root`/`module.secrets` (claves `/shoplab/dev/…` que leen los ExternalSecret del overlay `dev`); el `source` del `file()` de `root.yaml` conserva su ruta relativa
- [X] T021 [P] [US3] `terraform/envs/dev/backend.tf` con `bucket = "shoplab-tfstate-dev"`; `terraform/envs/dev/bootstrap/variables.tf` con `default = "dev"`; revisar `terraform/envs/dev/bootstrap/main.tf` y `outputs.tf` por referencias a `local`
- [X] T022 [P] [US3] `scripts/lib.sh`: `ENV_NAME="${ENV:-dev}"` y comentarios con `local`/"otro ENV"; revisar con `grep -n "local" scripts/init-env.sh scripts/up.sh scripts/down.sh scripts/bootstrap.sh scripts/verify-clean.sh scripts/plan.sh scripts/test-integration.sh` que ninguno fija `local` a mano
- [X] T023 [P] [US3] Pruebas con datos de ejemplo (contracts/terraform-root-dev.md): comentario de `terraform/envs/dev/tests/apply.tftest.hcl` (`shoplab-dev`); `terraform/modules/secrets/tests/secrets.tftest.hcl` (`environment = "dev"`, `/shoplab/dev/…`); `terraform/modules/state/tests/state.tftest.hcl` (`shoplab-tfstate-dev`, `Environment = "dev"`); `terraform/modules/eks/tests/eks.tftest.hcl` (`cluster_name = "shoplab-dev"`, rol `shoplab-dev-eks`)
- [X] T024 [US3] Ejecutar `make fmt-check validate lint pins tf-test` (incluye `check-layout.sh` y `validate` de `envs/dev` y de `envs/dev/bootstrap`); corregir con `terraform fmt` lo que reporte `fmt-check`
- [X] T025 [US3] Verificar residuos de Terraform: `ls terraform/envs` devuelve solo `dev` y `grep -rIn -E "envs/local|shoplab-local|app_environments|\"local\"" terraform scripts Makefile .gitignore .github docker-compose.yml versions.env` no devuelve nada que sea el nombre del entorno (el proveedor `local` de `versions.tf` y `local_sensitive_file` son legítimos)
- [ ] T026 [US3] Con Docker disponible, ejecutar `make test-integration` (ambiente `itest`) para confirmar que el `apply` de la raíz funciona sin `module.app_secrets` — PENDIENTE: requiere el emulador levantado y que Argo CD pueda leer la rama (ver T028)

**Checkpoint**: `terraform/envs/dev` completo y validado; el pipeline estático pasa.

---

## Phase 6: User Story 5 - Dev sigue funcionando tras el cambio (Priority: P1)

**Goal**: demostrar en vivo que, con el cambio completo, el clúster local creado desde cero lleva `dev` a Synced y
Healthy (FR-008, FR-009, FR-015).

**Independent Test**: `make up` desde cero → `shoplab-root` y `shoplab-dev` `Synced/Healthy`; `make down` sin restos.

- [ ] T027 [US5] `make check` completo en la rama con US1–US3 aplicadas; registrar el resultado en `validation.md` — PENDIENTE: requiere `make`, `kubeconform` y `yq`, que este equipo no tiene (la CI del PR ejecuta `make check` completo)
- [ ] T028 [US5] Ejecutar `make up` desde cero (entorno `dev`, tras T004/T018) y `export KUBECONFIG=terraform/envs/dev/kubeconfig.yaml`; esperar y comprobar con `kubectl -n argocd get applications` que `shoplab-root` y `shoplab-dev` están `Synced/Healthy`. Si `shoplab-dev` queda `Progressing` por el `StorageClass` del k3s de Floci (ver `docs/kustomize.md`), anotarlo como limitación conocida y confirmar `Synced` sin `ComparisonError` — PENDIENTE: Argo CD sincroniza `main` desde GitHub, que aún tiene staging y prod; hace falta publicar la rama (o fusionar) antes de validar en vivo
- [ ] T029 [US5] Comprobar que existe un solo ambiente en el clúster: solo las Applications `shoplab-root` y `shoplab-dev`; `kubectl get ns | grep shoplab` solo `shoplab-dev`; los secretos de Floci solo bajo `/shoplab/dev/` (`aws --endpoint-url http://localhost:4566 secretsmanager list-secrets --query 'SecretList[].Name'`) — PENDIENTE: Argo CD sincroniza `main` desde GitHub, que aún tiene staging y prod; hace falta publicar la rama (o fusionar) antes de validar en vivo
- [ ] T030 [US5] `make down` y `bash scripts/verify-clean.sh` sin contenedores ni redes restantes (`shoplab-floci`, `floci-aws-*`, `shoplab-dev-control-plane`) — PENDIENTE: Argo CD sincroniza `main` desde GitHub, que aún tiene staging y prod; hace falta publicar la rama (o fusionar) antes de validar en vivo
- [ ] T031 [US5] Completar `shoplab-helpdesk` `specs/LAB-101-single-dev-environment/validation.md`: fecha, commit de `shoplab-infra`, salida resumida de `make check`, estado de las Applications, resultado de T029/T030 y limitaciones observadas — PENDIENTE: Argo CD sincroniza `main` desde GitHub, que aún tiene staging y prod; hace falta publicar la rama (o fusionar) antes de validar en vivo

**Checkpoint**: queda constancia escrita de la validación en vivo.

---

## Phase 7: User Story 4 - Decisión registrada y documentación coherente (Priority: P2)

**Goal**: README, `docs/` y un ADR cuentan la misma historia de un solo ambiente y explican cómo añadir otro.

**Independent Test**: la búsqueda de `staging|prod|argocd-local|envs/local|shoplab-local` solo devuelve el ADR-0008
y documentos históricos; el ADR-0008 aparece en el índice y contiene decisión, motivo, consecuencias y los pasos
para añadir otro ambiente.

- [X] T032 [P] [US4] `README.md` de `shoplab-infra`: un único entorno raíz `envs/dev` (línea 6), `kustomize build k8s/overlays/dev` en la tabla de CI (línea 20) y `k8s/overlays/` con solo `dev` (línea 48); revisar el resto con `grep -n -i -E "staging|prod|local" README.md`
- [X] T033 [P] [US4] `docs/gitops.md`: ejemplo de la línea 15 con `k8s/overlays/dev/`; diagrama y tabla de Applications (líneas 28–37, 54) a una sola (`shoplab-dev`); eliminar la explicación de `argocd-local` (líneas 56 y 37); añadir una sección breve "Si alguna vez se sincronizaron staging o prod" con `kubectl -n argocd delete application shoplab-staging shoplab-prod --ignore-not-found` y `kubectl delete namespace shoplab-staging shoplab-prod --ignore-not-found` (FR-014)
- [X] T034 [P] [US4] `docs/kustomize.md`: líneas 4 y 13 (un solo ambiente), tabla de valores (líneas 26–33) solo con la columna `dev`, y quitar `TF_VAR_app_environments='["dev","staging"]' make up` (línea 60)
- [X] T035 [P] [US4] `docs/terraform-local.md`: rutas `terraform/envs/dev`; `ENV` por defecto `dev`; sustituir la sección "Varios ambientes" por "Un solo ambiente" (explica que otro ambiente se añade como indica el ADR-0008); añadir "Pasar desde el entorno local anterior": `make down` con la versión previa (o `docker compose down -v --remove-orphans`), mover o recrear `secrets.auto.tfvars` en `terraform/envs/dev` y `make up` (FR-014, research D4); reescribir la frase "cambios en staging" de gitleaks (línea 83) como "área de preparación de git"
- [X] T036 [P] [US4] `scripts/scan.sh`: sustituir "escaneando los cambios en staging" y su comentario por "área de preparación de git", para que la búsqueda de residuos no dé falsos positivos
- [X] T037 [P] [US4] `shoplab-helpdesk` `docs/adr/0008-un-solo-ambiente-dev.md` siguiendo `0000-plantilla.md`: Estado `aceptado`, Fecha `2026-10-26`; Contexto (tres ambientes definidos, uno solo cabe en el k3s de Floci, `argocd-local` como parche, entorno raíz `local`); Decisión (research D1–D3, D5); Consecuencias (clúster y estado se recrean, ADR-0006/0007 reemplazados parcialmente, menos mantenimiento); alternativas descartadas (ocultar ambientes, plantillas comentadas, conservar `environment = "local"`); sección "Cómo añadir otro ambiente" con los 5 pasos de research D8 (overlay sobre `base/`, `application-<env>.yaml` y kustomization, `OVERLAYS` en `versions.env`, `terraform/envs/<env>/` si necesita clúster y estado propios, claves `/shoplab/<env>/…`)
- [X] T038 [US4] `shoplab-helpdesk` `docs/adr/README.md`: añadir la fila `0008 | [Un solo ambiente dev en shoplab-infra](0008-un-solo-ambiente-dev.md) | aceptado` al índice (depende de T037)
- [X] T039 [P] [US4] `shoplab-helpdesk` `docs/adr/0006-gitops-pull-repo-infra-separado.md` y `docs/adr/0007-despliegue-continuo-dev-app-con-bypass.md`: bajo la línea de estado, añadir "Nota: parcialmente reemplazado por [ADR-0008](0008-un-solo-ambiente-dev.md) en lo relativo a los ambientes `staging` y `prod`"; no editar su cuerpo (FR-013); el estado sigue `aceptado`
- [X] T040 [P] [US4] `shoplab-helpdesk` `README.md` (línea 46) y `.github/pull_request_template.md` (línea 19): la Definition of Done de un servicio nuevo pasa a "su base y su overlay `dev` en `shoplab-infra/k8s/`"

**Checkpoint**: la documentación de ambos repositorios es coherente con un solo ambiente.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: verificación global, commits y pull requests (orden de fusión: primero `shoplab-infra`).

- [X] T041 Búsqueda final (SC-001) en ambos repositorios: `grep -rIn -i -E "staging|\bprod\b|argocd-local|envs/local|shoplab-local" --exclude-dir=.git --exclude-dir=.terraform --exclude-dir=.idea --exclude-dir=.rendered --exclude-dir=node_modules .`; solo se admiten ADR-0008, las notas de ADR-0006/0007 y las specs históricas (LAB-79, LAB-80, LAB-81, LAB-98, LAB-99) más esta carpeta
- [ ] T042 Antes de commitear, en ambos repositorios `git status --short` y `git diff --staged`: sin `*.orig`, `*.bak`, `kubeconfig.yaml`, `secrets.auto.tfvars`, `.terraform/`, `.rendered/` ni cambios de formato ajenos; `make check` final en `shoplab-infra`
- [ ] T043 Commits atómicos en `shoplab-infra` (Conventional Commits; p. ej. `refactor(k8s): …` para US1, `ci: …` para US2, `refactor(terraform): …` para US3, `docs: …` para US4) y PR con título `chore(infra): [LAB-101] consolida shoplab-infra en un solo ambiente dev`, usando la skill `commit-and-push` y la plantilla de PR
- [ ] T044 Commits y PR en `shoplab-helpdesk` (spec, plan, tasks, `validation.md`, ADR-0008, índice, notas, README, plantilla de PR) con título `docs(adr): [LAB-101] ADR-0008 un solo ambiente dev y consolidación de shoplab-infra`
- [ ] T045 En el PR de `shoplab-infra`, confirmar que `ci-gate` y `pr-title` están en verde, que el job `kustomize` construye solo `k8s/overlays/dev` y `k8s/argocd` y que el job `smoke` pasa
- [ ] T046 Tras fusionar ambos PR, comprobar SC-005: un merge a `main` de `shoplab-helpdesk` produce en `shoplab-infra` el commit `chore(dev): [LAB-81] despliega <sha> a dev`; registrarlo en `validation.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T004 antes de T017/T018 (conserva los secretos locales).
- **Foundational (Phase 2)**: sin tareas.
- **US1, US2, US3 (Phases 3–5)**: tocan archivos distintos y pueden hacerse en paralelo tras Setup. Acoplamiento:
  T012 (US2) debe estar en el mismo cambio que T007 (US1); `make kustomize` solo pasa con ambos.
- **US5 (Phase 6)**: depende de US1, US2 y US3 completas (valida el resultado conjunto).
- **US4 (Phase 7)**: independiente en archivos; sus afirmaciones deben verificarse contra US1–US3. Puede ir en
  paralelo con US5 (T037 → T038).
- **Polish (Phase 8)**: depende de todas las historias; T043 antes de T044 (el PR de `shoplab-helpdesk` se
  fusiona después), T046 tras ambas fusiones.

### Within Each User Story

- US1: T005–T007 en paralelo → T008–T010 (verificaciones).
- US2: T011 → T012 → T014 → T015; T013 y T016 en paralelo.
- US3: T017 → T018; T019, T021, T022, T023 en paralelo tras T017; T020 tras T019; T024 → T025 → T026.
- US5: T027 → T028 → T029 → T030 → T031 (secuencial, comparten el clúster local).
- US4: T032–T037, T039, T040 en paralelo; T038 tras T037.

### Parallel Opportunities

```text
Tras Setup:   US1 (T005, T006, T007)  ‖  US2 (T011, T013, T016)  ‖  US3 (T017 → T019, T021, T022, T023)  ‖  US4 (T032–T037, T039, T040)
Dentro de US4: README / gitops / kustomize / terraform-local / scan.sh / ADR-0008 / notas de ADR / README helpdesk
```

## Implementation Strategy

### MVP First (User Story 1)

1. Completar Setup (T001–T004).
2. Completar US1 junto con T011–T012 de US2 (para que `make kustomize` pase).
3. **Validar**: T008 (overlay `dev` idéntico), T009 y `make kustomize policies`.
4. Con eso ya hay un único ambiente en `k8s/`; el resto completa el criterio de la historia.

### Incremental Delivery

1. US1 + US2 → manifiestos y pipeline en un ambiente (commit y verificación local).
2. US3 → renombre de Terraform (commit aparte: el cambio más delicado, afecta nombres de clúster, bucket y secretos).
3. US5 → validación en vivo con `make up` desde cero (debe pasar antes de abrir el PR).
4. US4 → documentación y ADR-0008.
5. Polish → búsqueda de residuos, PR de `shoplab-infra`, luego PR de `shoplab-helpdesk`.

### Notes

- Esta historia no añade ni cambia endpoints HTTP: no se genera `requests.http` (regla de pruebas HTTP).
- No se editan `CHANGELOG.md`, `.release-please-manifest.json` ni versiones a mano; los tipos `chore`/`docs`/`refactor`
  del título de PR no generan release.
- Las especificaciones y ADRs históricos no se reescriben; solo ADR-0006/0007 reciben la nota de T039.
