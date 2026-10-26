# Tasks: Argo CD con una Application por ambiente

**Input**: Design documents from `/specs/LAB-79-argocd-applications/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: la regla de política nueva (`APPLICATION_NO_FINALIZER`) y los casos de `terraform test` se escriben con
fixtures y casos antes de activar el cambio (plan.md, Testing). El resto se valida con `kustomize build` + kubeconform
y con [quickstart.md](quickstart.md).

**Organization**: tareas agrupadas por historia de usuario. Todas las rutas son relativas al repositorio
`shoplab-infra` salvo que se indique `shoplab-helpdesk`. Campos de las `Application`:
[contracts/application-contract.md](contracts/application-contract.md); arranque, ingress y acceso:
[contracts/bootstrap-contract.md](contracts/bootstrap-contract.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirmar el punto de partida y resolver los puntos "A validar" de research.md.

- [X] T001 Crear en `shoplab-infra` la rama `feature/LAB-79-argocd-applications` desde `main` actualizado y verificar que LAB-80 y LAB-99 están integradas (`k8s/overlays/{dev,staging,prod}`, `k8s/argocd/*/application.yaml`, `scripts/kustomize-build.sh`, `scripts/policy-check.sh`, `versions.env`); registrar el resultado en `specs/LAB-79-argocd-applications/research.md` de `shoplab-helpdesk` *(Resultado: rama `feature/LAB-79-argocd-applications` creada en shoplab-infra desde main (168ffe3); LAB-80 y LAB-99 integradas (PR #5, #7, #4).)*
- [X] T002 [P] Spike (R4): con la versión del chart `argo-cd` fijada en `terraform/modules/platform/variables.tf` (`chart_versions.argo_cd`), ejecutar `helm template` con `server.ingress.enabled=true`, `server.ingress.ingressClassName=nginx`, `server.ingress.hostname=argocd.localtest.me` y `configs.params."server.insecure"=true` y confirmar que renderiza un `Ingress` con ese host; anotar en `research.md` (R4) las claves exactas válidas para esa versión *(Resultado: chart argo-cd 10.11.1: `server.ingress.{enabled,ingressClassName,hostname}` y `configs.params."server.insecure"` renderizan el Ingress esperado.)*
- [ ] T003 [P] Spike (R8): levantar el entorno con `make up`, aplicar a mano los overlays `staging` y `prod` y anotar en `research.md` (R8) la memoria usada (`docker stats`, `kubectl top nodes`) y si los tres ambientes caben juntos en la máquina local; si no caben, parar y decidir con quien mantiene LAB-80 antes de seguir *(Resultado parcial: aplazada junto a las validaciones en vivo: necesita los tres ambientes desplegados desde main.)*
- [X] T004 [P] Spike: comprobar con `kubeconform` y el catálogo de CRD que una `Application` con `metadata.finalizers` y `spec.syncPolicy.retry` valida (`scripts/kustomize-build.sh` ya usa el catálogo `datreeio`); anotar en `research.md` si hace falta vendorizar esquemas *(Resultado: kubeconform 0.8.0 valida una Application con finalizer y retry con el catálogo datreeio; no hace falta vendorizar esquemas.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: layout plano de `k8s/argocd/` y CI adaptado; todas las historias leen estos archivos.

**⚠️ CRITICAL**: ninguna historia puede empezar antes de completar esta fase.

- [X] T005 Mover con `git mv` `k8s/argocd/dev/application.yaml`, `k8s/argocd/staging/application.yaml` y `k8s/argocd/prod/application.yaml` a `k8s/argocd/application-dev.yaml`, `k8s/argocd/application-staging.yaml` y `k8s/argocd/application-prod.yaml`, y borrar los directorios `k8s/argocd/{dev,staging,prod}/` con sus `kustomization.yaml`, sin cambiar aún el contenido (data-model.md, "Application de ambiente")
- [X] T006 Crear `k8s/argocd/kustomization.yaml` (`apiVersion: kustomize.config.k8s.io/v1beta1`, `kind: Kustomization`) con `resources:` exactamente `application-dev.yaml`, `application-staging.yaml` y `application-prod.yaml`; NO incluir `root.yaml` (contracts/application-contract.md, "Composición")
- [X] T007 [P] Cambiar en `versions.env` `ARGOCD_DIR=k8s/argocd` y su comentario (ya no hay sufijo por ambiente) y comprobar con `grep -rn ARGOCD_DIR scripts Makefile .github` que ningún otro archivo asume `$ARGOCD_DIR/<ambiente>` *(Resultado: las listas de Application del smoke se añaden a `versions.env` separadas por comas (el archivo se hace `source` en bash, sin comillas).)*
- [X] T008 Adaptar `scripts/kustomize-build.sh` (depende de T005–T007): construir `$ARGOCD_DIR` una sola vez a `$RENDER_DIR/argocd.yaml` (nombre `argocd`) y, si existe `$ARGOCD_DIR/root.yaml`, copiarlo a `$RENDER_DIR/argocd-root.yaml` y validarlo con `kubeconform -strict -schema-location default -schema-location "$CRD_SCHEMAS"`; los overlays de `$KUSTOMIZE_DIR/<overlay>` siguen igual; el script debe seguir fallando si `$ARGOCD_DIR/kustomization.yaml` no existe, no construye o rinde 0 recursos
- [X] T009 [P] Crear los fixtures de la regla nueva en `scripts/tests/fixtures/good-application/rendered.yaml` (Application con `finalizers: [resources-finalizer.argocd.argoproj.io]`, `repoURL` real y `targetRevision: main`) y `scripts/tests/fixtures/bad-nofinalizer/rendered.yaml` (la misma sin `finalizers`), con el mismo formato que `scripts/tests/fixtures/bad-revision/rendered.yaml` *(Resultado: los 6 fixtures con Application llevan ahora el finalizer; `good` hace de `good-application`, por eso solo se crea `bad-nofinalizer`.)*
- [X] T010 Añadir en `scripts/tests/policy-check.test.sh` los casos de `good-application` (salida 0) y `bad-nofinalizer` (salida 1 con la regla `APPLICATION_NO_FINALIZER`) y ejecutar `make test-policies` comprobando que el caso malo FALLA antes de implementar la regla (depende de T009) *(Resultado: el caso nuevo falló antes de implementar la regla (salida 0 en lugar de 1).)*
- [X] T011 Implementar la regla `APPLICATION_NO_FINALIZER` en `scripts/policy-check.sh` (misma estructura que `TARGET_REVISION_NOT_MAIN`): para cada documento con `kind == "Application"`, reportar `report APPLICATION_NO_FINALIZER "$id" "falta resources-finalizer.argocd.argoproj.io"` si `.metadata.finalizers` no contiene `resources-finalizer.argocd.argoproj.io`; actualizar la cabecera del script con la regla nueva; `make test-policies` debe pasar (depende de T010) *(Resultado: 10 casos de política en verde.)*

**Checkpoint**: `make kustomize` construye `k8s/argocd` (3 `Application`) y los 3 overlays; las `Application` actuales fallan `APPLICATION_NO_FINALIZER` hasta T012 (esperado).

---

## Phase 3: User Story 1 - Un ambiente se despliega solo desde git (Priority: P1) 🎯 MVP

**Goal**: `Application` por ambiente completas y conformes al contrato (FR-002, FR-004, FR-005, FR-006, FR-007, FR-010, FR-014).

**Independent Test**: con el entorno levantado, aplicar a mano `k8s/argocd` (`kubectl apply -k k8s/argocd`) y comprobar que `staging` y `prod` quedan `Synced/Healthy` en su namespace y que un cambio en `main` converge sin intervención (quickstart §3).

### Implementation for User Story 1

- [X] T012 [P] [US1] Completar `k8s/argocd/application-dev.yaml` según contracts/application-contract.md: `metadata.finalizers: [resources-finalizer.argocd.argoproj.io]`, `spec.syncPolicy.retry` con `limit: 5` y `backoff` (`duration: 10s`, `factor: 2`, `maxDuration: 3m`), conservando `automated.prune: true`, `automated.selfHeal: true`, `repoURL: https://github.com/leandroAmariles/shoplab-infra`, `targetRevision: main`, `path: k8s/overlays/dev`, `destination.namespace: shoplab-dev`; sin `CreateNamespace` ni secretos; actualizar el comentario de cabecera (ya no se aplica por Terraform sino por la raíz)
- [X] T013 [P] [US1] Completar `k8s/argocd/application-staging.yaml` con los mismos campos que T012 para `path: k8s/overlays/staging` y `destination.namespace: shoplab-staging`
- [X] T014 [P] [US1] Completar `k8s/argocd/application-prod.yaml` con los mismos campos que T012 para `path: k8s/overlays/prod` y `destination.namespace: shoplab-prod`
- [X] T015 [US1] Verificar que cada `destination.namespace` coincide con el `namespace:` de `k8s/overlays/<env>/kustomization.yaml` (`grep -n '^namespace:' k8s/overlays/*/kustomization.yaml`) y que `make kustomize` y `make policies` pasan sin violaciones, incluida `APPLICATION_NO_FINALIZER` (depende de T011–T014)
- [ ] T016 [US1] Validar en vivo (quickstart §3, depende de T015 y de que T012–T014 estén en `main`): `kubectl apply -k k8s/argocd` con `KUBECONFIG=terraform/envs/local/kubeconfig.yaml`, esperar `Synced/Healthy`, escalar `deploy/frontend` de `shoplab-staging` a mano y comprobar que `selfHeal` lo revierte en < 5 min, y quitar un recurso de un overlay en `main` y comprobar el `prune`; registrar tiempos en `quickstart.md`, sección "Resultados"

**Checkpoint**: las 3 `Application` convergen y se autorreparan aplicadas a mano; US1 es entregable por sí sola.

---

## Phase 4: User Story 2 - Application raíz (app-of-apps) (Priority: P1)

**Goal**: una raíz en git que Terraform crea al arrancar y que gestiona las `Application` de ambiente (FR-001, FR-003, FR-004, FR-009, FR-011, FR-014).

**Independent Test**: `make up` desde cero deja `shoplab-root` y, creadas por ella, `shoplab-dev`, `shoplab-staging` y `shoplab-prod`; añadir y quitar un archivo de `Application` en `k8s/argocd` aparece y desaparece sin tocar el clúster (quickstart §2 y §4).

### Tests for User Story 2

> **NOTE: escribir estos casos primero y comprobar que FALLAN antes de implementar**

- [X] T017 [US2] Añadir en `terraform/modules/platform/tests/platform.tftest.hcl` un caso `initial_app_required` que ejecute `plan` sin pasar `argocd_initial_app` y espere error de validación (hoy pasa por el valor por defecto `guestbook`) y un caso `initial_app_values` que pase `{ name = "shoplab-root", repo_url = "https://github.com/leandroAmariles/shoplab-infra", path = "k8s/argocd", target_revision = "main", namespace = "argocd" }` y compruebe `finalizers`, `retry` y ausencia de `CreateNamespace` en los valores de `helm_release.argocd_initial_app`; ejecutar `make tf-test` y comprobar que falla *(Resultado: la variable pasa a recibir el manifiesto completo, así que los casos validan `kind`, `repoURL` de plantilla y `targetRevision`; no se puede probar la ausencia de una variable obligatoria como fallo de validación. El caso malo falló antes de la validación.)*

### Implementation for User Story 2

- [X] T018 [P] [US2] Crear `k8s/argocd/root.yaml` con la `Application` `shoplab-root` (namespace `argocd`, `finalizers: [resources-finalizer.argocd.argoproj.io]`, `project: default`, `repoURL: https://github.com/leandroAmariles/shoplab-infra`, `targetRevision: main`, `path: k8s/argocd`, `destination.server: https://kubernetes.default.svc`, `destination.namespace: argocd`, `automated` con `prune: true` y `selfHeal: true`, `retry` como en T012) y un comentario de cabecera que explique que Terraform la lee y que NO está en `kustomization.yaml`
- [X] T019 [P] [US2] Adaptar el chart local `terraform/modules/platform/charts/argocd-application/templates/application.yaml` y su `values`: renderizar `metadata.finalizers` y `spec.syncPolicy.retry` desde `.Values.application` (con los mismos valores por defecto del contrato) y hacer `CreateNamespace=true` opcional con `createNamespace` falso por defecto; mantener compatible el resto de campos *(Resultado: el chart renderiza el manifiesto recibido tal cual (`toYaml`), sin duplicar finalizer, retry ni política; `helm template` con root.yaml da un manifiesto idéntico.)*
- [X] T020 [US2] En `terraform/modules/platform/variables.tf` eliminar el `default` de `argocd_initial_app` (el ejemplo `guestbook`), añadir `createNamespace` (bool, opcional, `false`), actualizar la descripción ("Application raíz que Argo CD sincroniza") y validar que `repo_url` no contenga `CAMBIA-ESTE-OWNER` y que `target_revision` sea `main` (depende de T017, T019) *(Resultado: sin valor por defecto y con tres validaciones (kind, repoURL sin plantilla, targetRevision main); sin `createNamespace` porque el chart ya no añade syncOptions.)*
- [X] T021 [US2] En `terraform/envs/local/main.tf` leer la raíz con `locals { argocd_root = yamldecode(file("${path.module}/../../../k8s/argocd/root.yaml")) }` y pasar a `module.platform` `argocd_initial_app = { name = local.argocd_root.metadata.name, repo_url = local.argocd_root.spec.source.repoURL, path = local.argocd_root.spec.source.path, target_revision = local.argocd_root.spec.source.targetRevision, namespace = local.argocd_root.spec.destination.namespace }` (contracts/bootstrap-contract.md); `make validate` y `make tf-test` deben pasar (depende de T018, T020) *(Resultado: `scripts/test.sh` copia `k8s/argocd/root.yaml` a la copia temporal de validate (file() se evalúa ahí). Además `app_environments` pasa a `[dev, staging, prod]` por defecto: la raíz despliega los tres y sin sus claves los ExternalSecret quedarían en error.)*
- [X] T022 [US2] Ajustar `scripts/smoke.sh` (R8): sustituir la espera "todas las `Application` Synced/Healthy" por la espera de `shoplab-root` y `shoplab-dev` `Synced/Healthy` y la comprobación de que `shoplab-staging` y `shoplab-prod` existen; al fallar, mostrar el estado de las cuatro; mantener `ARGOCD_TIMEOUT` y el reintento existente *(Resultado: versions.env: `ARGOCD_READY_APPS=shoplab-root,shoplab-dev` y `ARGOCD_PRESENT_APPS=shoplab-staging,shoplab-prod`.)* *(Corrección tras el CI de la PR #8: el smoke apunta la raíz a la revisión de la PR (`SMOKE_ROOT_REVISION`) y solo exige `shoplab-root` Healthy y que existan las tres hijas; ver research R8. Reproducido en local: pasa.)*
- [ ] T023 [US2] Validar en vivo desde cero (quickstart §2 y §4, depende de T021–T022 y de que todo esté en `main`): `make down && make up`, confirmar que `guestbook` no existe, que `shoplab-root` crea las tres hijas y que quedan `Synced/Healthy`; añadir `application-demo.yaml` a `k8s/argocd/kustomization.yaml`, comprobar que aparece, quitarlo y comprobar que la `Application` y sus recursos desaparecen (finalizer); comprobar que `kubectl -n argocd get application shoplab-root -o jsonpath='{.status.resources[*].name}'` no incluye `shoplab-root`; registrar resultados en `quickstart.md` *(Resultado parcial: con los cambios sin fusionar, `up.sh` crea `shoplab-root` desde `root.yaml` (Synced/Healthy, sin `guestbook`); falta comprobar que cree las tres hijas y el alta/baja de una Application, que exigen los cambios en main.)*

**Checkpoint**: arranque de cero a cuatro `Application` sin pasos manuales; US1 + US2 forman el MVP completo.

---

## Phase 5: User Story 3 - Argo CD accesible por ingress local (Priority: P2)

**Goal**: interfaz de Argo CD por ingress con `argocd.localtest.me` (FR-008, FR-009).

**Independent Test**: con el entorno levantado y `port-forward` al controlador del ingress, `http://argocd.localtest.me:8080` carga la interfaz y lista las `Application` (quickstart §5).

### Tests for User Story 3

- [X] T024 [US3] Añadir en `terraform/modules/platform/tests/platform.tftest.hcl` un caso `argocd_ingress` que compruebe, sobre `helm_release.argo_cd`, que los valores incluyen `server.ingress.enabled = true`, `ingressClassName = nginx`, el host de `var.argocd_host` y `configs.params."server.insecure" = true`, y que `output.argocd_url` es `http://<argocd_host>:8080`; ejecutar `make tf-test` y comprobar que falla

### Implementation for User Story 3

- [X] T025 [US3] Añadir en `terraform/modules/platform/variables.tf` la variable `argocd_host` (string, por defecto `argocd.localtest.me`, validación: debe terminar en `.localtest.me`) y propagarla desde `terraform/envs/local/variables.tf` y `main.tf` (depende de T024) *(Resultado: `argocd_host` solo en el módulo (por defecto `argocd.localtest.me`, validado como `*.localtest.me`); no se propaga desde `envs/local` por no haber quien lo cambie.)*
- [X] T026 [US3] En `terraform/modules/platform/argocd.tf` pasar al `helm_release.argo_cd` los valores del ingress con las claves confirmadas en T002 (`server.ingress.enabled`, clase `nginx`, host `var.argocd_host`, `configs.params."server.insecure"`), conservando el `set` de `redis.image.repository`; sin credenciales ni dominios de producción (depende de T002, T025)
- [X] T027 [US3] Actualizar la salida `argocd_url` de `terraform/modules/platform/outputs.tf` (y `terraform/envs/local/outputs.tf`) a `http://${var.argocd_host}:8080` con la descripción del `port-forward` a `svc/ingress-nginx-controller` (contracts/bootstrap-contract.md, "Acceso local"); `make tf-test` debe pasar (depende de T026)
- [X] T028 [US3] Actualizar el paso 4 de `docs/terraform-local.md` para entrar por el ingress (`kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80` y `http://argocd.localtest.me:8080`), manteniendo la obtención de la contraseña inicial con `argocd-initial-admin-secret`, y enlazar a `docs/gitops.md` (depende de T027)
- [ ] T029 [US3] Validar en vivo (quickstart §5, depende de T026–T028): `curl -H 'Host: argocd.localtest.me' http://127.0.0.1:8080/` devuelve 200 o redirección, la interfaz lista `shoplab-root` y las tres hijas y `shoplab.localtest.me` sigue respondiendo; registrar en `quickstart.md` *(Resultado parcial: ingress comprobado en vivo (HTTP 200 por Host y por nombre, interfaz, login y API); falta ver las cuatro Application y `shoplab.localtest.me`, que dependen de main.)*

**Checkpoint**: Argo CD visible por navegador sin `port-forward` al servidor.

---

## Phase 6: User Story 4 - Guía del flujo GitOps y la frontera con Terraform (Priority: P2)

**Goal**: guía en `docs/` con flujo, frontera, acceso y diagnóstico (FR-012).

**Independent Test**: una persona ajena sigue la guía para cambiar las réplicas de `staging`, verlo sincronizar y revertirlo (quickstart §6, SC-006).

### Implementation for User Story 4

- [X] T030 [US4] Crear `docs/gitops.md` en `shoplab-infra` con: (1) flujo (cambio en rama → PR → CI de LAB-99 → fusión a `main` → Argo CD sincroniza → verificación en `kubectl -n argocd get applications`), (2) diagrama de texto de `Terraform → Argo CD → shoplab-root → 3 Application → overlays` tomado de data-model.md, (3) tabla de la frontera: Terraform = Floci, clúster, secretos en Secrets Manager, ingress-nginx, External Secrets, monitorización, Argo CD y `shoplab-root`; Argo CD = backend, frontend, ingest, Postgres y Ollama por ambiente; (4) cómo cambiar un valor de `staging` y revertirlo con `git revert`
- [X] T031 [US4] Ampliar `docs/gitops.md` con acceso (puerto, host y obtención de la contraseña sin escribirla en el repo), diagnóstico de `OutOfSync`/`Degraded` (`kubectl -n argocd describe application <nombre>`, `ExternalSecret` sin clave en Secrets Manager, overlay que no construye) y las advertencias: no corregir editando el clúster a mano (`selfHeal` lo revierte), todo lo desplegado debe estar en el overlay (`prune`), y cambiar `root.yaml` exige `terraform apply` (depende de T030)
- [X] T032 [P] [US4] Enlazar `docs/gitops.md` desde `README.md` y `docs/kustomize.md` de `shoplab-infra` y comprobar con `grep -rn "gitops.md" README.md docs` que los enlaces resuelven
- [ ] T033 [US4] Prueba de lectura (SC-006): pedir a una persona que no participó seguir solo `docs/gitops.md` para cambiar las réplicas de `staging` y revertirlas; anotar el tiempo y los tropiezos en `quickstart.md` y corregir la guía en consecuencia (depende de T031)

**Checkpoint**: flujo y frontera documentados y probados por alguien ajeno.

---

## Phase 7: User Story 5 - ADR: GitOps pull frente a despliegue push (Priority: P2)

**Goal**: decisión registrada en `shoplab-helpdesk` (FR-013). Independiente del resto: puede hacerse en paralelo desde el inicio.

**Independent Test**: el ADR tiene Contexto, Decisión y Consecuencias con contenido, compara ambas alternativas y figura en el índice (quickstart §6).

### Implementation for User Story 5

- [X] T034 [P] [US5] Crear en `shoplab-helpdesk` `docs/adr/0006-gitops-pull-repo-infra-separado.md` a partir de `docs/adr/0000-plantilla.md` (estado `aceptado`): Contexto (LAB-79, repo `shoplab-infra` separado, ADR-0005); Decisión (GitOps pull con Argo CD y repo de infraestructura separado, app-of-apps con una `Application` por ambiente); alternativa descartada: despliegue push desde GitHub Actions; comparación en credenciales del clúster fuera del CI, auditoría en git, deriva (`selfHeal`) y reversión (`git revert`); Consecuencias (un componente más que operar, un repo adicional, la raíz solo cambia por Terraform, promoción entre ambientes en historias posteriores)
- [X] T035 [US5] Añadir la fila `| 0006 | [GitOps pull con repositorio de infraestructura separado](0006-gitops-pull-repo-infra-separado.md) | aceptado |` a la tabla de `docs/adr/README.md` de `shoplab-helpdesk` y comprobar que el ADR tiene las tres secciones con contenido (depende de T034)

**Checkpoint**: ADR indexado y revisable.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: verificación final y cierre.

- [X] T036 Ejecutar el pipeline local completo en `shoplab-infra`: `make check`, `make lint`, `make tf-test`, `make kustomize`, `make test-policies`, `make trivy` y `make scan` (gitleaks); confirmar 0 violaciones y 0 secretos en `k8s/argocd/` (SC-004, SC-008) *(Resultado: sin `make` en Windows se ejecutaron los scripts: kustomize-build, policies, policy-check.test (10 ok), test.sh fmt/checks/modules/validate (platform 13 casos), lint, trivy (0 hallazgos) y scan (gitleaks: sin fugas).)*
- [X] T037 Introducir a propósito cada violación de SC-007 en una rama descartable (`repoURL` con `CAMBIA-ESTE-OWNER`, `targetRevision: HEAD`, `Application` sin finalizer, `root.yaml` listado en `k8s/argocd/kustomization.yaml`) y comprobar que `make kustomize`/`make policies` fallan con el archivo y la regla; descartar la rama (si `root.yaml` listado no falla, añadir esa comprobación a `scripts/kustomize-build.sh` y su fixture) *(Resultado: probado sobre una copia del repo: `repoURL` de plantilla, `targetRevision: HEAD` y finalizer ausente fallan en policies; root listado y root ausente fallan en kustomize-build. Los mensajes de TEMPLATE_REPO_URL citan la línea del manifiesto renderizado, no el archivo fuente.)*
- [ ] T038 Ejecutar el quickstart completo (§1–§7) con los cambios en `main` de `shoplab-infra`, rellenar "Resultados" en `specs/LAB-79-argocd-applications/quickstart.md` (ambientes `Healthy` en local, tiempos, memoria) y terminar con `make down` + `scripts/verify-clean.sh`
- [ ] T039 Abrir las PR: en `shoplab-infra` con título `feat(argocd): [LAB-79] Application raíz y por ambiente con ingress` y en `shoplab-helpdesk` con título `docs(adr): [LAB-79] ADR-0006 y especificación de Argo CD`; usar `/commit-and-push` y la plantilla de PR; no editar `CHANGELOG.md` ni la versión a mano
- [ ] T040 Registrar el cierre de LAB-79 en los `tasks.md` y `quickstart.md` (resultados reales, bloqueos) y dejar a LAB-79 pendiente de validar en Jira hasta que el pipeline de `main` de `shoplab-infra` pase en verde

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T002–T004 en paralelo tras T001.
- **Foundational (Phase 2)**: depende de Setup; BLOQUEA US1–US3. T005 → T006; T007 en paralelo; T008 tras T005–T007; T009 en paralelo; T010 → T011.
- **US1 (Phase 3)**: depende de Foundational.
- **US2 (Phase 4)**: depende de US1 (la raíz sincroniza las `Application` ya completas) y de Foundational.
- **US3 (Phase 5)**: depende de Foundational y de T002; independiente de US1/US2 salvo en la validación en vivo (T029 usa el entorno de US2).
- **US4 (Phase 6)**: redacción desde US2 (T030 necesita la forma final del flujo); T031 tras US3 para el acceso por ingress.
- **US5 (Phase 7)**: sin dependencias de código; puede ir desde el inicio.
- **Polish (Phase 8)**: depende de las historias deseadas.

### User Story Dependencies

- **US1 (P1)**: tras Foundational; entregable aplicando las `Application` a mano.
- **US2 (P1)**: tras US1; añade el arranque automático.
- **US3 (P2)**: tras Foundational; toca solo Terraform y `docs/terraform-local.md`.
- **US4 (P2)**: tras US2 y US3 para el contenido completo.
- **US5 (P2)**: independiente.

### Within Each User Story

- Casos de `terraform test` y fixtures se escriben y fallan antes de la implementación (T010→T011, T017→T020/T021, T024→T025–T027).
- Manifiestos antes de la validación en vivo; la validación en vivo exige los cambios en `main` de `shoplab-infra` (Argo CD sincroniza `main`).

### Parallel Opportunities

- Setup: T002, T003, T004.
- Foundational: T007 y T009 en paralelo con la cadena T005→T006→T008.
- US1: T012, T013, T014.
- US2: T018 y T019.
- US3 puede avanzar en paralelo con US2 (archivos distintos).
- US5 completa (T034) en paralelo con cualquier otra fase; T032 en paralelo con T031.

---

## Parallel Example: User Story 1

```bash
# Las tres Application de ambiente se editan a la vez (archivos distintos):
Task: "Completar k8s/argocd/application-dev.yaml (T012)"
Task: "Completar k8s/argocd/application-staging.yaml (T013)"
Task: "Completar k8s/argocd/application-prod.yaml (T014)"
```

## Implementation Strategy

### MVP First (US1 + US2)

1. Completar Phase 1 (Setup) y Phase 2 (Foundational).
2. Completar US1 → validar aplicando `k8s/argocd` a mano (T016).
3. Completar US2 → validar el arranque desde cero (T023). **STOP**: este es el MVP; con él el clúster ya refleja `main`.
4. Fusionar a `main` de `shoplab-infra` antes de las validaciones en vivo.

### Incremental Delivery

1. Setup + Foundational → CI adaptado al layout plano.
2. US1 → `Application` por ambiente conformes → validar.
3. US2 → raíz y arranque por Terraform → validar (MVP).
4. US3 → ingress de Argo CD → validar.
5. US4 + US5 → guía y ADR.
6. Polish → pipeline local completo, violaciones a propósito, PR.

## Notes

- `requests.http` no aplica: la historia no añade ni cambia endpoints HTTP de la aplicación.
- [P] = archivos distintos, sin dependencias pendientes.
- Las tareas de validación en vivo (T003, T016, T023, T029, T038) requieren Docker y ≈16 GB de RAM; si no hay recursos,
  registrar el bloqueo en `quickstart.md` en lugar de marcarlas como hechas.
- Confirmar que las pruebas fallan antes de implementar (T010, T017, T024).
- Commit al terminar cada tarea o grupo lógico; mensajes `LAB-79 type(scope): description`.
