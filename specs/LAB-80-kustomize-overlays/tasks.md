# Tasks: Manifiestos con Kustomize y overlays dev, staging y prod

**Input**: Design documents from `/specs/LAB-80-kustomize-overlays/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: la regla de política nueva (`no-plain-secret`) se escribe con fixtures buenas/malas antes de activarse
(plan.md, Testing). El resto se valida con `kustomize build` + kubeconform y con [quickstart.md](quickstart.md).

**Organization**: tareas agrupadas por historia de usuario. Todas las rutas son relativas al repositorio
`shoplab-infra` salvo que se indique `shoplab-helpdesk`. Valores de réplicas, recursos y HPA:
[contracts/environment-values.md](contracts/environment-values.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirmar el punto de partida y resolver los puntos abiertos de plan.md (Riesgos).

- [X] T001 Verificar en `shoplab-infra` que LAB-98 y LAB-99 están integradas (`terraform/`, `scripts/up.sh`, `versions.env`, `scripts/policy-check.sh`, `.github/workflows/ci.yml`) y registrar en `specs/LAB-80-kustomize-overlays/research.md` de `shoplab-helpdesk`: (a) nombre real del `ClusterSecretStore` de `terraform/modules/platform`, (b) si `terraform/modules/secrets` puede crear las claves `/shoplab/dev/postgres-user` y `/shoplab/dev/postgres-password`, (c) ruta de la `Application` inicial de Argo CD; si algo falta, parar y coordinar *(Resultado: LAB-98 y LAB-99 integradas en main de shoplab-infra. ClusterSecretStore = `aws-secrets-manager`; el módulo `secrets` crea `/shoplab/<environment>/<clave>` con `environment` configurable, hoy las claves `postgres-password` y `llm-api-key` (no hay `postgres-user`: el usuario va en el ConfigMap). La Application inicial de Argo CD es `guestbook` por defecto (variable `argocd_initial_app` del módulo platform); LAB-99 había dejado overlays mínimos en `kubernetes/`, que se migraron a `k8s/`.)*
- [X] T002 [P] Spike: levantar el entorno de LAB-98 (`make up`) y comprobar que existen metrics-server (`kubectl top nodes`), el `ClusterSecretStore` y la clase de ingress `nginx`; anotar la StorageClass por defecto en `research.md` (R4, R7) *(Pendiente: requiere `make up` con Docker (≈15 min); no se ejecutó en esta sesión.)* *(Pendiente: requiere `make up` con Docker (unos 15 min); no se ejecutó en esta sesión.)* *(Resultado: clúster levantado con `scripts/up.sh`. Hay metrics-server (`kubectl top nodes`), el ClusterSecretStore `aws-secrets-manager` está `Valid` y existe la clase de ingress `nginx`. El k3s de Floci NO trae StorageClass: los PVC quedan Pending y se validó con una clase sin provisionador y PV hostPath (documentado en docs/kustomize.md).)*
- [X] T003 [P] Spike: ejecutar `kubeconform` con el catálogo de CRDs sobre un `ExternalSecret` y un `HorizontalPodAutoscaler` de ejemplo y anotar en `research.md` si hace falta vendorizar esquemas en `schemas/` (R10) *(Resultado: con el catálogo datreeio, kubeconform valida ExternalSecret `external-secrets.io/v1`, HPA y Application sin esquemas ausentes; no hace falta vendorizar `schemas/`.)*
- [X] T004 Confirmar y fijar `KUSTOMIZE_DIR=k8s/overlays` en `versions.env` y en las llamadas de `scripts/kustomize-build.sh` y `Makefile` (sustituye la ruta provisional `kubernetes/overlays` de LAB-99) *(Resultado: `KUSTOMIZE_DIR=k8s/overlays` y `ARGOCD_DIR=k8s/argocd` en `versions.env`; `kustomize-build.sh` construye overlays y Application; CI, README y fixtures apuntan a `k8s/`.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esqueleto de Kustomize que todas las historias extienden.

**⚠️ CRITICAL**: ninguna historia puede empezar antes de completar esta fase.

- [X] T005 Crear `k8s/base/kustomization.yaml` vacío de recursos con las etiquetas comunes `app.kubernetes.io/part-of: shoplab` (commonLabels) y sin `namespace` (contracts/kustomize-layout.md, regla 2)
- [X] T006 [P] Crear la estructura vacía `k8s/overlays/{dev,staging,prod}/kustomization.yaml` con `resources: [../../base]` y `namespace: shoplab-dev|shoplab-staging|shoplab-prod`
- [X] T007 [P] Crear `k8s/overlays/{dev,staging,prod}/namespace.yaml` con el `Namespace` del ambiente y las etiquetas `pod-security.kubernetes.io/enforce: baseline` y `pod-security.kubernetes.io/warn: restricted` (research R2) y registrarlo en el `kustomization.yaml` de cada overlay *(Resultado: `namespace:` de Kustomize fija el namespace de todo el overlay y el Namespace se llama igual.)* *(Resultado: `namespace:` de Kustomize fija el namespace de todo el overlay y el Namespace se llama igual.)*

**Checkpoint**: `kustomize build k8s/overlays/dev` construye (solo el `Namespace`) y el pipeline de LAB-99 encuentra los 3 overlays.

---

## Phase 3: User Story 1 - Base declarativa del monolito (Priority: P1) 🎯 MVP

**Goal**: base con backend, frontend, ingest, Postgres con pgvector, Ollama e ingress, válida y sin `:latest` (FR-001, FR-002, FR-007, FR-008, FR-012).

**Independent Test**: `kustomize build k8s/base | kubeconform -strict -summary` valida los 6 componentes; ninguna imagen sin tag fijo; el backend declara las tres sondas (quickstart §1).

### Implementation for User Story 1

- [X] T008 [P] [US1] Crear `k8s/base/postgres/` (`statefulset.yaml`, `service.yaml`, `kustomization.yaml`): StatefulSet `postgres` de 1 réplica con imagen `pgvector/pgvector:0.8.7-pg16`, puerto 5432, `volumeClaimTemplates` de datos, sonda `pg_isready` y variables `POSTGRES_DB=shoplab` y `POSTGRES_USER`/`POSTGRES_PASSWORD` desde `secretKeyRef` al Secret `postgres-credentials`; `configMapGenerator` con `01-extensions.sql` copiado de `infra/postgres/init/01-extensions.sql` de `shoplab-helpdesk`, montado en `/docker-entrypoint-initdb.d`
- [X] T009 [P] [US1] Crear `k8s/base/ollama/` (`statefulset.yaml`, `service.yaml`, `kustomization.yaml`): StatefulSet `ollama` de 1 réplica con imagen `ollama/ollama:0.40.8`, puerto 11434, PVC de modelos en `/root/.ollama`, `configMapGenerator` con `entrypoint.sh` copiado de `infra/ollama/entrypoint.sh` de `shoplab-helpdesk`, `command: ["sh", "/entrypoint.sh"]` y variables `OLLAMA_CHAT_MODEL` y `OLLAMA_EMBED_MODEL` desde el ConfigMap del componente; sonda `ollama list` como en el healthcheck de compose
- [X] T010 [P] [US1] Crear `k8s/base/backend/` (`deployment.yaml`, `service.yaml`, `configmap` por `configMapGenerator`, `kustomization.yaml`): Deployment `backend` con imagen `ghcr.io/leandroamariles/backend` (tag fijado en los overlays), puerto 8080, `startupProbe` y `livenessProbe` en `/actuator/health/liveness`, `readinessProbe` en `/actuator/health/readiness`, variables `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`, `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/shoplab`, `SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434` y credenciales desde `secretKeyRef` al Secret `backend-credentials`; volumen `emptyDir` en `/tmp` (research R5)
- [X] T011 [P] [US1] Crear `k8s/base/frontend/` (`deployment.yaml`, `service.yaml`, `kustomization.yaml`): Deployment `frontend` con imagen `ghcr.io/leandroamariles/frontend`, puerto 8080, sonda HTTP `/`, `emptyDir` para las rutas escribibles de nginx-unprivileged (`/tmp`)
- [X] T012 [P] [US1] Crear `k8s/base/ingest/` (`deployment.yaml`, `service.yaml`, `kustomization.yaml`): Deployment `ingest` con imagen `ghcr.io/leandroamariles/ingest`, puerto 8000, sonda TCP 8000 y credenciales desde `secretKeyRef` al Secret `ingest-credentials`
- [X] T013 [P] [US1] Crear `k8s/base/ingress/` (`ingress.yaml`, `kustomization.yaml`): `Ingress` con `ingressClassName: nginx`, host de marcador `shoplab.invalid`, ruta `/api` → servicio `backend:8080` y `/` → servicio `frontend:8080`; sin TLS e ingest sin publicar (research R8) *(Resultado: sin TLS; el host es el marcador `shoplab.invalid` hasta que el overlay lo sustituye.)* *(Resultado: sin TLS; el host es el marcador `shoplab.invalid` hasta que el overlay lo sustituye.)*
- [X] T014 [US1] Aplicar a los Deployments y StatefulSets de la base el endurecimiento de research R9: `automountServiceAccountToken: false`, `securityContext` con `runAsNonRoot` (backend, frontend, ingest), `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, `readOnlyRootFilesystem` donde la imagen lo permita, y `resources.requests/limits` de marcador mínimo en cada contenedor (los valores finales los fija cada overlay) (depende de T008–T012) *(Resultado: tras Trivy, Postgres y Ollama también corren con `readOnlyRootFilesystem` (probado con `docker run --read-only` y emptyDir en `/tmp` y `/var/run/postgresql`).)*
- [X] T015 [US1] Registrar los 6 directorios en `k8s/base/kustomization.yaml` (`resources:`), sin ninguna referencia a Kafka (FR-002), y comprobar con `kustomize build k8s/base | kubeconform -strict -summary` que los 6 componentes son válidos (depende de T008–T014)

**Checkpoint**: la base construye y valida; la ausencia de Kafka no rompe nada (US1 escenarios 1, 3 y 5).

---

## Phase 4: User Story 2 - Overlays por ambiente (Priority: P1)

**Goal**: `dev`, `staging` y `prod` ajustan imágenes, réplicas, recursos, HPA y configuración sobre la misma base (FR-003 a FR-006, FR-008).

**Independent Test**: construir los 3 overlays y comparar con `diff`; difieren en namespace, host, recursos, HPA y almacenamiento, no en tipos ni nombres de componentes (quickstart §1 y §3).

### Implementation for User Story 2

- [X] T016 [P] [US2] Completar `k8s/overlays/dev/kustomization.yaml`: `images:` con `newTag: v0.1.1` para `ghcr.io/leandroamariles/{backend,frontend,ingest}` y las versiones de Postgres y Ollama de T008/T009; parches en `k8s/overlays/dev/patches/` con los recursos, almacenamiento (postgres 5Gi, ollama 10Gi) y host `shoplab.localtest.me` de contracts/environment-values.md; `config.env` con `LOG_LEVEL=DEBUG` y `configMapGenerator` con `behavior: merge`
- [X] T017 [P] [US2] Completar `k8s/overlays/staging/kustomization.yaml` y `patches/` con los valores de la columna `staging` (host `staging.shoplab.example`, nivel de log `INFO`, postgres 20Gi, ollama 20Gi); los Deployments con HPA sin `replicas`
- [X] T018 [P] [US2] Completar `k8s/overlays/prod/kustomization.yaml` y `patches/` con los valores de la columna `prod` (host `shoplab.example`, nivel de log `INFO`, postgres 50Gi, ollama 40Gi); imágenes por digest `@sha256:` si ya existe el digest del release, y si no, `newTag: v0.1.1`; los Deployments con HPA sin `replicas`
- [X] T019 [P] [US2] Crear `k8s/overlays/dev/hpa.yaml` con 3 `HorizontalPodAutoscaler` (`autoscaling/v2`) para backend (1–2, CPU 75%), frontend (1–2, CPU 75%) e ingest (1–2, CPU 75%) y registrarlo en `resources:`
- [X] T020 [P] [US2] Crear `k8s/overlays/staging/hpa.yaml` con backend (2–4, CPU 70%), frontend (2–3, CPU 70%) e ingest (1–2, CPU 70%) y registrarlo en `resources:`
- [X] T021 [P] [US2] Crear `k8s/overlays/prod/hpa.yaml` con backend (3–8, CPU 70%), frontend (2–6, CPU 70%) e ingest (2–4, CPU 70%) y registrarlo en `resources:`
- [X] T022 [US2] Ejecutar `for env in dev staging prod; do kustomize build k8s/overlays/$env | kubeconform -strict -summary; done` y comprobar que cada overlay emite los 6 componentes, 1 `Namespace` y 3 HPA, y que `prod` ≥ `staging` ≥ `dev` en réplicas mínimas, recursos y almacenamiento (depende de T016–T021)

**Checkpoint**: los 3 overlays construyen con valores propios; un cambio en la base se refleja en los 3 (US2 escenarios 1–5).

---

## Phase 5: User Story 3 - Secretos fuera de git con ExternalSecret (Priority: P1)

**Goal**: ninguna credencial en git; todo secreto sale de Secrets Manager vía ExternalSecret (FR-009, FR-010).

**Independent Test**: ninguna salida de overlay contiene `kind: Secret`; los 3 ExternalSecret referencian `/shoplab/<ambiente>/…` (quickstart §2).

### Tests for User Story 3 ⚠️

> Escribir las fixtures y el caso de prueba PRIMERO y comprobar que fallan antes de implementar la regla.

- [X] T023 [P] [US3] Crear fixtures `scripts/tests/fixtures/good/` (overlay con solo `ExternalSecret`) y `scripts/tests/fixtures/bad-secret/` (overlay con un `kind: Secret` con `stringData`) y añadir a `scripts/tests/policy-check.test.sh` los casos `good` (espera salida 0) y `bad-secret` (espera salida ≠ 0 y el mensaje de contracts/secrets-contract.md, regla 5)

### Implementation for User Story 3

- [X] T024 [US3] Añadir la regla `no-plain-secret` a `scripts/policy-check.sh`: recorre la salida de `kustomize build` de cada overlay con `yq` y falla con `no-plain-secret: <archivo>: kind Secret found in overlay <ambiente> (use ExternalSecret)` si encuentra cualquier `kind: Secret` (depende de T023); ejecutar `make test-policies` hasta que las fixtures pasen
- [X] T025 [P] [US3] Crear `k8s/base/postgres/externalsecret.yaml`: `ExternalSecret` `postgres-credentials` (`external-secrets.io/v1`, `refreshInterval: 1h`, `secretStoreRef` al `ClusterSecretStore` de T001) que genera el Secret `postgres-credentials` con `POSTGRES_USER` y `POSTGRES_PASSWORD` desde `remoteRef.key` `/shoplab/ENV/postgres-user` y `/shoplab/ENV/postgres-password`; registrarlo en `k8s/base/postgres/kustomization.yaml`
- [X] T026 [P] [US3] Crear `k8s/base/backend/externalsecret.yaml`: `ExternalSecret` `backend-credentials` que genera `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` desde las mismas claves remotas `/shoplab/ENV/postgres-user` y `/shoplab/ENV/postgres-password`; registrarlo en `k8s/base/backend/kustomization.yaml`
- [X] T027 [P] [US3] Crear `k8s/base/ingest/externalsecret.yaml`: `ExternalSecret` `ingest-credentials` que genera `DATABASE_USER` y `DATABASE_PASSWORD` desde las mismas claves remotas; registrarlo en `k8s/base/ingest/kustomization.yaml`
- [X] T028 [US3] En cada `k8s/overlays/<ambiente>/patches/` añadir el parche (JSON Patch sobre `remoteRef.key` de los 3 ExternalSecret) que sustituye `ENV` por `dev`, `staging` o `prod`, de modo que cada ambiente solo referencie `/shoplab/<ambiente>/…` (FR-010) y registrarlo en el `kustomization.yaml` del overlay (depende de T016–T018, T025–T027)
- [X] T029 [US3] Si T001 (b) mostró que falta la clave del ambiente, extender `terraform/envs/local` de `shoplab-infra` para crear `/shoplab/dev/postgres-user` y `/shoplab/dev/postgres-password` con valores ficticios por `TF_VAR_secrets` (nunca en git), manteniendo `secrets.auto.tfvars.example` con marcadores *(Resultado: sin cambios en Terraform; para sincronizar el overlay `dev` basta `TF_VAR_environment=dev make up` (documentado en docs/kustomize.md).)*
- [X] T030 [US3] Comprobar con `kustomize build k8s/overlays/<ambiente> | yq 'select(.kind == "Secret")'` que no hay ningún `Secret` en los 3 overlays y con `make test-policies` que las fixtures de T023 pasan (depende de T024, T028)

**Checkpoint**: 0 `Secret` en claro; cada ambiente con sus propias claves (US3 escenarios 1–4).

---

## Phase 6: User Story 4 - Validación de cada overlay en CI (Priority: P2)

**Goal**: el pipeline de LAB-99 construye y valida los 3 overlays y rechaza las violaciones (FR-011, FR-012).

**Independent Test**: PR con los 3 overlays en verde; PRs de mutación (imagen `latest`, `Secret` en claro, overlay roto) fallan con el archivo señalado (quickstart §2).

### Implementation for User Story 4

- [X] T031 [US4] Verificar que `scripts/kustomize-build.sh` y el job `kustomize` de `.github/workflows/ci.yml` recorren `k8s/overlays/{dev,staging,prod}` y fallan si un overlay no existe, no construye o genera cero recursos (FR-011); ajustar rutas y filtros de `changes` para incluir `k8s/` (depende de T004) *(Resultado: `scripts/kustomize-build.sh` valida `k8s/overlays` y `k8s/argocd`; falla si falta un overlay, no construye o rinde 0 recursos; el filtro de rutas de CI incluye `k8s/`.)*
- [X] T032 [US4] Verificar que `scripts/policy-check.sh` ejecuta `no-plain-secret` junto a las reglas de imágenes de LAB-99 en `make check` y en el job `policies` de CI, y que `.trivyignore` documenta con motivo, responsable y fecha cualquier hallazgo HIGH de Postgres u Ollama (research R9) *(Resultado: la regla se llama `PLAIN_SECRET`; `scripts/trivy.sh` y un paso del job `kustomize` analizan también los manifiestos renderizados, sin hallazgos HIGH/CRITICAL ni excepciones nuevas.)*
- [X] T033 [US4] Validar con PRs de mutación (imagen `:latest`, imagen sin tag, `kind: Secret` con datos, `prod/kustomization.yaml` borrado) que cada uno falla en su paso con el archivo señalado y que el PR corregido pasa; anotar el resultado en `specs/LAB-80-kustomize-overlays/research.md` de `shoplab-helpdesk` *(Hecho en local con `scripts/kustomize-build.sh` y `scripts/policies.sh`: `latest` da IMAGE_LATEST, un Secret en claro da PLAIN_SECRET y un overlay borrado falla. Falta ejecutarlo como PR en CI.)* *(Resultado en CI (shoplab-infra#6, cerrado sin fusionar): el job kustomize anotó PLAIN_SECRET (staging), IMAGE_NO_TAG (prod), IMAGE_LATEST (dev) y TARGET_REVISION_NOT_MAIN (argocd-dev))*
- [X] T034 [US4] Integrar con Argo CD: cambiar la `Application` inicial de LAB-98 (módulo `platform`, `argocd_initial_app`) para que apunte a `k8s/overlays/dev` con `targetRevision: main` y comprobar en el clúster local (`make up`) que queda `Synced` y `Healthy` y que los ExternalSecret están en `SecretSynced` (quickstart §4) (depende de T029, T030) *(Pendiente: `argocd_initial_app` sigue en guestbook; apuntarlo al overlay dev implica desplegar Ollama y las imágenes de GHCR en la prueba de humo (ver riesgos del plan).)* *(Resultado: con una Application temporal apuntando a la rama del fix, `shoplab-dev` quedó Synced y Healthy y los 3 ExternalSecret en SecretSynced. `argocd_initial_app` de Terraform se deja en guestbook: el overlay se despliega aplicando `k8s/argocd/dev/application.yaml`, y las claves de dev las crea `TF_VAR_app_environments='["dev"]'` (shoplab-infra#7))*
- [X] T035 [US4] Validar las sondas del backend en el clúster: `kubectl -n shoplab-dev port-forward deploy/backend 8080` y `curl localhost:8080/actuator/health/liveness` y `/readiness` devuelven `{"status":"UP"}`; si los grupos no están expuestos, abrir una historia para `backend/` y anotarlo en plan.md (Riesgos) *(Pendiente: requiere el clúster y la imagen del backend.)* *(Resultado: `/actuator/health/liveness` y `/readiness` devuelven UP y `/actuator/health` lista los grupos liveness y readiness; el pod está Ready sin reinicios)*

**Checkpoint**: CI valida los 3 overlays y la prueba de humo de LAB-99 sincroniza `dev` (US4 escenarios 1–3).

---

## Phase 7: User Story 5 - Convención para nuevos servicios desde F4 (Priority: P3)

**Goal**: documentar y exigir base y overlays para cada servicio nuevo desde F4 (FR-013, FR-014).

**Independent Test**: añadir un servicio de ejemplo siguiendo solo `docs/kustomize.md` y comprobar que los 3 overlays siguen construyendo (quickstart §5).

### Implementation for User Story 5

- [X] T036 [P] [US5] Crear `docs/kustomize.md` en `shoplab-infra`: estructura base/overlays, cómo construir un overlay en local (`kustomize build` + `kubeconform`), reglas de composición de contracts/kustomize-layout.md, tabla de valores por ambiente (enlace a environment-values.md) y receta "añadir un servicio": directorio en `base/`, línea en `base/kustomization.yaml`, `images`/réplicas/recursos/HPA en cada overlay y ExternalSecret si lleva credenciales
- [X] T037 [P] [US5] En `shoplab-helpdesk`, añadir a la Definition of Done del proyecto (`.github/pull_request_template.md` y la sección correspondiente de `README.md`) la condición "desde F4, cada servicio nuevo incluye su base y sus overlays en `shoplab-infra/k8s/`"
- [X] T038 [US5] Ejecutar la prueba de US5: añadir un servicio de ejemplo (base + 3 overlays) solo con `docs/kustomize.md`, confirmar que `kustomize build` de los 3 overlays y `make check` pasan, medir el tiempo (SC-006 < 30 min) y eliminar el servicio de ejemplo (depende de T036) *(Resultado: siguiendo solo docs/kustomize.md se añadió un servicio de ejemplo (base + 3 overlays); los 3 overlays construyeron con 26 recursos válidos y las políticas pasaron. La construcción tarda 4 s; el tiempo de un desarrollador nuevo (SC-006) no se midió. El servicio de ejemplo se eliminó)*

**Checkpoint**: la convención está documentada, exigida y probada (US5 escenarios 1–2).

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: cierre y verificación global.

- [X] T039 [P] Enlazar `docs/kustomize.md` desde el `README.md` de `shoplab-infra` y actualizar la lista de checks si cambió (FR-013)
- [X] T040 Recorrer [quickstart.md](quickstart.md) completo (pasos 1–5) y verificar SC-001 a SC-007 del spec; anotar resultados y desviaciones en `research.md` *(Resultado: pasos 1-4 ejecutados (build y esquema, políticas con mutaciones, diff entre ambientes y despliegue de dev en vivo con ingress, sondas, pgvector y modelos de Ollama); paso 5 como en T038. Descubierto y corregido: los tags v0.1.x no existen en GHCR, así que las imágenes van por digest (shoplab-infra#7))*
- [X] T041 [P] Revisar con `git status`/`git diff --staged` que no queda ningún valor de secreto, `*.orig` ni archivo temporal, y que ningún manifiesto contiene `:latest` (`grep -rn ':latest' k8s/`) *(Resultado: sin `:latest` ni `kind: Secret` en `k8s/`; sin archivos temporales; los cambios quedan sin commitear.)*
- [X] T042 Abrir los PR: en `shoplab-infra` con título `feat(k8s): [LAB-80] manifiestos Kustomize y overlays dev, staging y prod`, y en `shoplab-helpdesk` con título `docs(spec): [LAB-80] especifica y planifica los manifiestos Kustomize` y la Definition of Done actualizada *(Resultado: shoplab-infra#5 y shoplab-helpdesk#51 fusionados; correcciones posteriores en shoplab-infra#7 y shoplab-helpdesk (docs/LAB-80-close-tasks))*

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 condiciona T025–T029.
- **Foundational (Phase 2)**: depende de Setup; bloquea todas las historias.
- **US1 (Phase 3)**: depende de Foundational.
- **US2 (Phase 4)**: depende de US1 (parchea los recursos de la base).
- **US3 (Phase 5)**: la política y sus fixtures (T023–T024) solo dependen de Foundational; los ExternalSecret (T025–T027) dependen de US1 y los parches por ambiente (T028) de US2.
- **US4 (Phase 6)**: depende de US2 y US3 para la integración en vivo; T031–T032 pueden empezar tras US1.
- **US5 (Phase 7)**: la documentación (T036–T037) puede escribirse tras US1; la prueba (T038) tras US2 y US3.
- **Polish (Phase 8)**: depende de todas.

### Parallel Opportunities

- T002 y T003 en paralelo en Setup; T006 y T007 en Foundational.
- T008–T013 (un componente por directorio) en paralelo; T014 las integra.
- T016–T021 (un overlay o HPA por archivo) en paralelo.
- T023 en paralelo con US1 y US2; T025–T027 en paralelo.
- T036 y T037 en paralelo.

```bash
# Ejemplo US1: los seis componentes a la vez
Task: "Crear k8s/base/postgres/ ..."   Task: "Crear k8s/base/ollama/ ..."
Task: "Crear k8s/base/backend/ ..."    Task: "Crear k8s/base/frontend/ ..."
Task: "Crear k8s/base/ingest/ ..."     Task: "Crear k8s/base/ingress/ ..."
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Setup y Foundational.
2. US1: la base valida.
3. US2: los 3 overlays construyen con sus valores.
4. **Parar y validar** con `kustomize build` + kubeconform (quickstart §1 y §3). Sin US3 aún no es fusionable: los
   pods referencian Secrets que nadie crea, así que US3 va en la misma PR.

### Incremental Delivery

1. Setup + Foundational + US1 + US2 → manifiestos construibles.
2. US3 → secretos por ExternalSecret y política `no-plain-secret` (fusionable desde aquí).
3. US4 → validación en CI y sincronización de `dev` por Argo CD.
4. US5 → documentación y Definition of Done.
5. Polish y PR.

---

## Notes

- [P] = archivos distintos, sin dependencias pendientes.
- La mayoría de tareas son de `shoplab-infra`; T037 y las anotaciones de resultados son de `shoplab-helpdesk`.
- Confirmar con `git status` en el repositorio correcto antes de cada commit.
- Detenerse en cada checkpoint para validar la historia de forma independiente.
