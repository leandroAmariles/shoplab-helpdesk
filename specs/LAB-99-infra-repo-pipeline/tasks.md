# Tasks: Pipeline del repo de infraestructura

**Input**: Design documents from `/specs/LAB-99-infra-repo-pipeline/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: las pruebas de las políticas (fixtures buenas/malas, `scripts/tests/`) están previstas en el plan y se
escriben antes de la implementación. El resto de controles se validan con PRs de "mutación" descritos en
[quickstart.md](quickstart.md).

**Organization**: tareas agrupadas por historia de usuario. Todas las rutas son relativas al repositorio
`shoplab-infra` salvo que se indique `shoplab-helpdesk`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US7)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirmar el punto de partida, fijar versiones y resolver los riesgos de `research.md`.

- [X] T001 Verificar en `shoplab-infra` que LAB-98 está integrada (`terraform/`, `docker-compose.yml`, `scripts/up.sh`, `scripts/down.sh`, `scripts/verify-clean.sh`) y localizar los overlays `dev`, `staging`, `prod`; si no existen, parar y coordinar con la historia que los crea (ver plan.md, Riesgos) o crear overlays mínimos y anotar el directorio real para `KUSTOMIZE_DIR` *(Resultado: LAB-98 integrada; no hay `kubernetes/` ni overlays. Decisión del usuario: implementar solo lo que no depende de overlays; US2 y T007 quedan aplazadas.)*
- [X] T002 Crear `versions.env` con `TERRAFORM_VERSION`, `TFLINT_VERSION`, `KUSTOMIZE_VERSION`, `KUBECONFORM_VERSION`, `YQ_VERSION`, `TRIVY_VERSION`, `FLOCI_IMAGE` (mismo tag que `docker-compose.yml`), `KUSTOMIZE_DIR`, `ARGOCD_TIMEOUT` y `FLOCI_READY_TIMEOUT`, todo con versión exacta (sin rangos ni `latest`; data-model.md, `ToolVersions`); anotar en research.md cómo se resolvió cada versión
- [X] T003 [P] Crear `scripts/check-tools.sh`: lee `versions.env`, compara cada herramienta instalada, imprime `falta <herramienta> <versión>` con la forma de instalarla y falla si falta o difiere (salvo `ALLOW_TOOL_DRIFT=1`) (contracts/make-check.md)
- [X] T004 [P] Crear `.tflint.hcl` con el plugin `aws` en versión fija, compatible con el provider de `terraform/` (resolver compatibilidad aquí; research R2)
- [X] T005 [P] Crear `.trivyignore` con el mismo formato de comentarios (motivo, responsable, fecha de revisión) que el de `shoplab-helpdesk` (FR-014) *(Se añadieron 8 excepciones iniciales AWS-0039/0040/0041/0086/0087/0091/0093/0132 del código de LAB-98, justificadas por ser recursos solo del emulador.)*
- [X] T006 [P] Spike: comprobar en un workflow de prueba que Floci funciona como `services:` con `/var/run/docker.sock` como volumen y un healthcheck; registrar el resultado y el plan B (`docker compose up -d --wait`) en `specs/LAB-99-infra-repo-pipeline/research.md` de `shoplab-helpdesk` (R5) *(Resultado: no se usó `services:` (el contexto `env` no está disponible en `services.<id>.image`); Floci se levanta con `docker compose` en el runner, con `docker.sock`, y funciona en `ubuntu-latest`.)*
- [ ] T007 [P] Spike: ejecutar kubeconform con el catálogo de esquemas de CRDs sobre los overlays y registrar qué recursos (Argo CD, ESO, kube-prometheus-stack) quedan sin esquema y si hace falta vendorizar en `schemas/` (R4)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esqueleto del workflow y del Makefile que todas las historias extienden.

**⚠️ CRITICAL**: ninguna historia puede empezar antes de completar esta fase.

- [X] T008 Crear `.github/workflows/ci.yml` con disparadores `pull_request`, `push` a `main` y `workflow_dispatch`, `permissions: contents: read`, `concurrency` con `cancel-in-progress` solo en PR, y el job `changes` (filtro de rutas: `terraform/`, `kubernetes/`, `scripts/`, `versions.env`, `.tflint.hcl`, el propio workflow) (contracts/ci-workflow.md)
- [X] T009 Añadir a `.github/workflows/ci.yml` el job agregador `ci-gate` (`if: always()`, `needs` de todos los jobs; falla si alguno terminó en `failure` o `cancelled`, los `skipped` no bloquean) (depende de T008)
- [X] T010 [P] Copiar `.github/workflows/pr-title.yml` desde `shoplab-helpdesk` (contexto `pr-title`) y `.github/pull_request_template.md`
- [X] T011 [P] Crear `Makefile` base: incluye `versions.env`, `.PHONY`, `SHELL := bash`, objetivo `check-tools` que llama a `scripts/check-tools.sh` y una ayuda `make help` (si LAB-98 ya tiene `Makefile`, extenderlo sin romper `up`/`down`/`test`/`fmt`)
- [X] T012 En `.github/workflows/ci.yml`, definir el paso común que vuelca `versions.env` a `$GITHUB_ENV` (se reutiliza en cada job) y fijar `timeout-minutes` por job (depende de T008)

**Checkpoint**: un PR vacío ejecuta `changes` + `ci-gate` en verde.

---

## Phase 3: User Story 1 - Validación estática de Terraform en cada PR (Priority: P1) 🎯 MVP

**Goal**: `fmt -check`, `validate`, TFLint y `terraform test` en cada PR (FR-002, FR-003, FR-004).

**Independent Test**: un PR con un `.tf` sin formatear, otro con una variable inexistente y otro con una prueba rota
falla en el paso correspondiente; corregidos, queda en verde (quickstart §3, puntos 1, 2 y 4).

- [X] T013 [US1] Añadir a `Makefile` los objetivos `fmt-check` (`terraform fmt -check -recursive terraform/`), `validate` (`init -backend=false` + `validate` por módulo y entorno), `lint` (`tflint --recursive --config .tflint.hcl`) y `tf-test` (`terraform test` por módulo) (contracts/make-check.md)
- [X] T014 [US1] Añadir el job `static` a `.github/workflows/ci.yml`: checkout, `versions.env`, `hashicorp/setup-terraform`, instalación de TFLint con caché de plugins, y llamadas a `make fmt-check validate lint tf-test`; condicionado al filtro de rutas (depende de T012, T013)
- [X] T015 [US1] Añadir `static` a los `needs` de `ci-gate` en `.github/workflows/ci.yml` (depende de T014)
- [ ] T016 [US1] Validar con PRs de mutación (archivo sin formatear, variable inexistente, prueba rota, regla de TFLint) que cada uno falla en su paso con el detalle esperado, y que el PR corregido pasa; anotar el resultado en `specs/LAB-99-infra-repo-pipeline/` de `shoplab-helpdesk` *(Hecho en local: las cuatro mutaciones fallan con el código esperado; falta ejecutarlas como PR en CI.)*

**Checkpoint**: US1 funcional y comprobable por sí sola.

---

## Phase 4: User Story 2 - Overlays y políticas de manifiestos (Priority: P1)

**Goal**: `kustomize build` de `dev`, `staging`, `prod` + kubeconform y las cuatro reglas de política
(FR-007 a FR-012).

**Independent Test**: cada violación introducida a propósito (`:latest`, sin tag, `repoURL` de plantilla,
`targetRevision` distinto de `main`) falla con la regla y el valor (quickstart §2).

### Tests for User Story 2 (escribir primero y comprobar que fallan)

- [ ] T017 [P] [US2] Crear las fixtures `scripts/tests/fixtures/{good,bad-latest,bad-notag,bad-repourl,bad-revision}/` con manifiestos renderizados mínimos; `good/` incluye una imagen por digest sin tag y una `Application` con `targetRevision: main`; añadir una fixture de imagen con puerto de registro y sin tag (contracts/policy-rules.md)
- [ ] T018 [US2] Crear `scripts/tests/policy-check.test.sh`: ejecuta `scripts/policy-check.sh` sobre cada fixture y comprueba código de salida y regla esperada según la tabla de casos de contracts/policy-rules.md (depende de T017)

### Implementation for User Story 2

- [ ] T019 [US2] Implementar `scripts/policy-check.sh <rendered.yaml> [<overlay>]` con `yq`: reglas `IMAGE_LATEST`, `IMAGE_NO_TAG` (containers, initContainers, ephemeralContainers), `TEMPLATE_REPO_URL` (literal `CAMBIA-ESTE-OWNER` en todo el texto) y `TARGET_REVISION_NOT_MAIN` (`spec.source`, `spec.sources[]`, `ApplicationSet`; ausente = `<unset>`); salida `regla | overlay | recurso | valor`, resumen y códigos 0/1/2; en CI emitir también `::error` (depende de T018)
- [ ] T020 [P] [US2] Implementar `scripts/kustomize-build.sh`: para cada overlay de `dev`, `staging`, `prod` falla si no existe o rinde 0 recursos, ejecuta `kustomize build`, valida con `kubeconform -strict -summary` más el catálogo de CRDs (o `schemas/` según T007) y guarda la salida renderizada en un directorio temporal para políticas y Trivy
- [ ] T021 [US2] Añadir a `Makefile` los objetivos `kustomize`, `policies` y `test-policies` (depende de T019, T020)
- [ ] T022 [US2] Añadir el job `kustomize` a `.github/workflows/ci.yml`: instala kustomize, kubeconform y yq a las versiones de `versions.env` y ejecuta `make kustomize policies test-policies`; incluirlo en los `needs` de `ci-gate` (depende de T021)
- [ ] T023 [US2] Validar con PRs de mutación (las cuatro violaciones, un overlay renombrado y un overlay vacío) que el job falla con el mensaje esperado y que la variante con digest sin tag pasa; anotar el resultado

**Checkpoint**: US1 y US2 funcionan de forma independiente.

---

## Phase 5: User Story 3 - Plan de Terraform contra Floci en el PR (Priority: P2)

**Goal**: `terraform plan` contra Floci del runner, publicado y actualizado como comentario único (FR-005, FR-006).

**Independent Test**: un PR con cambios en `terraform/` muestra un único comentario que se actualiza en un segundo
push (quickstart §4).

- [X] T024 [P] [US3] Crear `scripts/tf-plan-comment.sh`: toma la salida de `terraform plan -no-color`, antepone el marcador `<!-- shoplab-infra-tf-plan -->` y el resumen `N to add, N to change, N to destroy`, y trunca a ~60 000 caracteres con aviso y enlace a la ejecución (contracts/ci-workflow.md)
- [X] T025 [US3] Añadir el objetivo `plan` a `Makefile`: levanta Floci (compose de LAB-98), espera la salud con `FLOCI_READY_TIMEOUT`, ejecuta el bootstrap del estado, `init` con el backend de Floci y `plan`, y al terminar baja Floci
- [X] T026 [US3] Añadir el job `plan` a `.github/workflows/ci.yml`: `services:` con `FLOCI_IMAGE`, puerto 4566, `docker.sock` y healthcheck (o el plan B de T006), espera con tiempo límite, bootstrap + `init` + `plan` con credenciales ficticias, permisos `contents: read` y `pull-requests: write` (depende de T006, T024, T025) *(Floci se levanta con `docker compose` desde `scripts/plan.sh` y no con `services:`: el contexto `env` no está disponible en `services.<id>.image`, y así se reutiliza el mismo `docker-compose.yml` que en local.)*
- [X] T027 [US3] En el job `plan`, publicar el comentario con `actions/github-script`: buscar el marcador y actualizar o crear; si el token no tiene escritura (fork, Dependabot) escribir el plan en `$GITHUB_STEP_SUMMARY`; incluir `plan` en los `needs` de `ci-gate` (depende de T026)
- [ ] T028 [US3] Validar: PR con cambio de Terraform → un comentario; segundo push → mismo comentario actualizado; Floci caído a propósito → el job falla con mensaje claro; PR desde fork → resumen del job *(Verificado en CI: un único comentario del plan, actualizado en pushes posteriores. Sin probar: Floci caído y PR desde fork.)*

**Checkpoint**: US3 funcional e independiente.

---

## Phase 6: User Story 4 - Escaneo de seguridad con Trivy (Priority: P2)

**Goal**: Trivy config sobre Terraform y manifiestos, SARIF en Security y compuerta (FR-013, FR-014).

**Independent Test**: una mala configuración conocida falla el job y aparece en Security; con excepción
documentada pasa (quickstart §6).

- [X] T029 [US4] Añadir el objetivo `trivy` a `Makefile`: `trivy config` sobre `terraform/` y sobre la salida renderizada de los overlays (reutilizar la de `kustomize-build.sh`), `--severity HIGH,CRITICAL`, `--ignorefile .trivyignore`, fallando si `trivy` no está instalado
- [X] T030 [US4] Añadir el job `trivy` a `.github/workflows/ci.yml` con el patrón de dos pasadas de `shoplab-helpdesk` (`aquasecurity/trivy-action` fijada por SHA): informe `format: sarif` con `exit-code: 0`, subida con `github/codeql-action/upload-sarif` (`category: trivy-config`, `if: always()` y solo con `CAN_WRITE`), subida del artefacto y compuerta `format: table` con `exit-code: 1`; permisos `security-events: write`; incluirlo en los `needs` de `ci-gate` (depende de T005, T020)
- [ ] T031 [US4] Validar con una mala configuración conocida (falla y aparece en Security → Code scanning), con la excepción añadida a `.trivyignore` con motivo, responsable y fecha (pasa) y en un PR de fork (sin subida de SARIF, sin error) *(El SARIF no se puede subir mientras `shoplab-infra` sea privado (Code Scanning no habilitado): la subida es best-effort y el informe queda como artefacto.)*

**Checkpoint**: US4 funcional e independiente.

---

## Phase 7: User Story 5 - Prueba de humo: apply, Argo CD sano y destroy (Priority: P2)

**Goal**: apply sobre Floci, esperar `Synced` + `Healthy` y destruir, con limpieza garantizada (FR-015, FR-016).

**Independent Test**: sobre `main` sin cambios termina en verde sin recursos remanentes; con una `Application` rota
falla mostrando el estado y limpia igualmente (quickstart §5).

- [X] T032 [US5] Crear `scripts/smoke.sh`: `scripts/up.sh` de LAB-98, espera con `kubectl wait` (`sync.status=Synced` y `health.status=Healthy` en todas las `Application`) con `ARGOCD_TIMEOUT`, `trap` que ejecuta `scripts/down.sh` y `scripts/verify-clean.sh` siempre, y en fallo imprime `kubectl get applications -A` y los eventos recientes (depende de T001)
- [X] T033 [US5] Añadir el objetivo `smoke` a `Makefile` que llama a `scripts/smoke.sh`
- [X] T034 [US5] Añadir el job `smoke` a `.github/workflows/ci.yml`: corre en PR que toca `terraform/`, `kubernetes/`, `scripts/` o el workflow, en push a `main` y en `workflow_dispatch`; paso de limpieza `if: always()` que repite `down` y `verify-clean`; subida del log como artefacto; incluirlo en los `needs` de `ci-gate` (depende de T032, T033)
- [X] T035 [US5] Medir la duración en el runner estándar; si supera el límite de SC-004 (20 min) restringir el job a `main` y `workflow_dispatch` y anotarlo en research.md R6 *(Medido en el runner estándar: ~14,7 min en total (apply, Argo CD y destroy), por debajo de los 20 min de SC-004; no hace falta restringirlo a `main`.)*
- [ ] T036 [US5] Validar el camino feliz y el de fallo (`Application` con `targetRevision` roto): el job falla mostrando el estado de las aplicaciones y `verify-clean` confirma que no quedan contenedores ni redes de Floci; cancelar un run a mitad y comprobar la limpieza *(Hecho en local: camino feliz con Argo CD 1/1 Synced+Healthy y camino de fallo con limpieza; faltan la cancelación y la ejecución en CI.)* *(En CI: apply y Argo CD 1/1 Synced+Healthy; `aws_vpc` no responde al destroy en el Docker de Linux del runner (Floci), así que `smoke.sh` acota el destroy (`DESTROY_TIMEOUT`), comprueba que solo queda la VPC y avisa. Redis de Argo CD se descarga de `mirror.gcr.io` en CI porque `ecr-public.aws.com` responde 429.)*

**Checkpoint**: US5 funcional e independiente.

---

## Phase 8: User Story 7 - Los mismos controles en local con `make check` (Priority: P2)

**Goal**: `make check` reproduce en local los controles de CI (FR-019, FR-020).

**Independent Test**: en un clon limpio con las herramientas, `make check` termina en 0; una violación falla con el
mismo mensaje que en CI (quickstart §1).

- [X] T037 [US7] Añadir el objetivo `check` a `Makefile` encadenando `check-tools fmt-check validate lint tf-test kustomize policies test-policies trivy` (sin `plan` ni `smoke`), detenido en el primer fallo; documentar `make -k check` (depende de T013, T021, T029)
- [X] T038 [US7] Verificar en `scripts/check-tools.sh` los tres escenarios de aceptación: herramienta ausente (dice cuál y cómo instalarla), versión distinta (falla) y `ALLOW_TOOL_DRIFT=1` (avisa, no falla)
- [ ] T039 [US7] Comprobar la paridad (SC-006): en el mismo commit comparar la salida de `make check` con la de los jobs de CI y confirmar que el mensaje de una violación de política es idéntico; en Windows ejecutar con Git Bash y documentar `MSYS_NO_PATHCONV=1` donde haga falta

**Checkpoint**: US7 funcional; CI y local dan el mismo veredicto.

---

## Phase 9: User Story 6 - Misma protección de `main` que el repo de la app (Priority: P1)

**Goal**: ruleset equivalente al de `shoplab-helpdesk` con `ci-gate` y `pr-title` requeridos (FR-017, FR-018).

**Independent Test**: push directo y force push rechazados; PR con un check en rojo no se fusiona; solo squash con
título válido (quickstart §7 y §8).

> Se ejecuta al final aunque sea P1: el ruleset exige checks que deben existir y haber corrido antes
> (plan.md y research R8), de lo contrario `main` queda bloqueado.

- [X] T040 [P] [US6] Copiar `.github/rulesets/main-protection.json` desde `shoplab-helpdesk` sin cambiar las reglas (`deletion`, `non_fast_forward`, `required_linear_history`, PR con squash, checks estrictos `ci-gate` y `pr-title`, `bypass_actors: []`)
- [X] T041 [P] [US6] Crear `scripts/apply-ruleset.sh` (usa `gh api`): aplica o actualiza el ruleset por nombre y fija los ajustes del repositorio (`allow_merge_commit=false`, `allow_rebase_merge=false`, `squash_merge_commit_title=PR_TITLE`, `squash_merge_commit_message=BLANK`, `delete_branch_on_merge=true`)
- [ ] T042 [US6] Comprobar que `ci-gate` y `pr-title` ya se ejecutaron al menos una vez en `shoplab-infra` (fusionando antes el PR de este pipeline si hace falta) y entonces ejecutar `scripts/apply-ruleset.sh` (depende de T040, T041 y de todas las fases anteriores) *(Bloqueado: la API de rulesets responde 403 en un repositorio privado sin GitHub Pro; hay que hacer público el repo o pasar a Pro.)*
- [ ] T043 [US6] Validar: push directo y force push a `main` rechazados, PR con un job en rojo bloqueado, solo "Squash and merge" disponible; PR que solo cambia `README.md` con `ci-gate` en verde y fusionable; comparar el ruleset con el de la app y registrar diferencias (ninguna esperada)

**Checkpoint**: las siete historias completas.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T044 [P] Documentar el pipeline en `README.md` de `shoplab-infra`: jobs, checks requeridos, cómo reproducirlos con `make check`, `make plan` y `make smoke`, cómo añadir una excepción a `.trivyignore` y cómo actualizar `versions.env` (FR-022)
- [X] T045 [P] Revisar que nada del pipeline usa `latest` ni rangos abiertos: acciones fijadas por SHA o versión mayor como en `shoplab-helpdesk`, versiones en `versions.env`, imagen de Floci con tag fijo, y permisos mínimos por job (FR-020, FR-021)
- [ ] T046 Ejecutar la validación completa de [quickstart.md](quickstart.md) (pasos 1 a 8) y confirmar los criterios de salida
- [ ] T047 En `shoplab-helpdesk`: registrar resultados de validación y marcar las tareas completadas en este archivo; si el ruleset de la app cambió algún check, sincronizar `.github/rulesets/main-protection.json` en ambos repos
- [ ] T048 Preparar el PR con título `chore(ci): [LAB-99] pipeline del repo de infraestructura` y la plantilla del repositorio; no editar a mano `CHANGELOG.md` ni el manifiesto de release-please

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 condiciona todo (overlays y LAB-98).
- **Foundational (Phase 2)**: depende de Setup; bloquea las historias.
- **Historias (Phases 3-9)**: dependen de Foundational. US1 y US2 (P1) primero; US3, US4 y US5 son independientes
  entre sí; US7 agrega los objetivos de US1, US2 y US4; US6 (ruleset) va al final.
- **Polish (Phase 10)**: depende de las historias completas.

### User Story Dependencies

- **US1**: tras Foundational; sin dependencias de otras historias.
- **US2**: tras Foundational; produce la salida renderizada que reutiliza US4.
- **US3**: tras Foundational y T006; independiente.
- **US4**: tras Foundational; usa los overlays renderizados de T020 (US2).
- **US5**: tras Foundational y LAB-98 (scripts `up/down/verify-clean`); independiente.
- **US7**: requiere los objetivos de US1, US2 y US4.
- **US6**: requiere que existan y hayan corrido los checks de todas las demás historias.

### Parallel Opportunities

- Setup: T003, T004, T005, T006 y T007 en paralelo tras T002.
- Foundational: T010 y T011 en paralelo.
- US2: T017 y T020 en paralelo; US3 (T024) y US6 (T040, T041) pueden prepararse en paralelo a otras historias.
- Con varios desarrolladores: A en US1+US2, B en US3, C en US4+US5, y luego US7 y US6 en serie.

### Parallel Example: User Story 2

```bash
# Tras T002 y la fase 2:
Task: "T017 Fixtures de políticas en scripts/tests/fixtures/"
Task: "T020 scripts/kustomize-build.sh"
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Setup y Foundational (el PR vacío ya reporta `ci-gate`).
2. US1: validación estática de Terraform.
3. US2: overlays y políticas.
4. **PARAR y validar** con PRs de mutación; ya hay un pipeline útil que protege `main` de errores de sintaxis y
   de manifiestos mal configurados.

### Incremental Delivery

1. Setup + Foundational → esqueleto verde.
2. US1 → US2 (MVP).
3. US4 (Trivy) → US3 (plan) → US5 (humo), cada una añadida a `ci-gate` sin romper las anteriores.
4. US7 (`make check`) → US6 (ruleset) → Polish.

---

## Notes

- Esta historia no añade ni cambia endpoints HTTP: no se genera `requests.http`.
- [P] = archivos distintos y sin dependencias pendientes; la etiqueta [Story] traza cada tarea a `spec.md`.
- Hacer commit por tarea o grupo lógico (`LAB-99 type(scope): …`); no mezclar cambios del repo de la app con los de
  `shoplab-infra`.
- Evitar editar `ci.yml` en paralelo desde varias tareas: las que lo tocan se aplican en serie.
