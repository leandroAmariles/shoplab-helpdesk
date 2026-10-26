---

description: "Tareas de LAB-81 - Despliegue continuo a dev con kustomize edit set image"
---

# Tasks: Despliegue continuo a dev con kustomize edit set image

**Input**: Documentos de diseño en `/specs/LAB-81-continuous-deploy-dev/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se incluyen pruebas del script de despliegue (plan: test-first). No hay endpoints HTTP, así que no se
genera `requests.http`.

**Organization**: tareas agrupadas por historia de usuario. Dos repositorios: `shoplab-helpdesk` (`APP:`) y
`shoplab-infra` (`INFRA:`, ruta relativa a `../shoplab-infra`, PR hermana). Las tareas `[MANUAL]` son ajustes de
GitHub que no se pueden versionar.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: historia de usuario a la que pertenece (US1..US4)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: credencial, entorno y versiones fijadas

- [ ] T001 [MANUAL] Crear la GitHub App `shoplab-deploy` con permisos `contents: write` y `metadata: read`, sin webhooks, instalada **solo** en `leandroAmariles/shoplab-infra`; guardar su ID y generar la clave privada
- [ ] T002 [MANUAL] Crear el `Environment` `dev` en `shoplab-helpdesk` con la variable `DEPLOY_APP_ID` y el secreto `DEPLOY_APP_PRIVATE_KEY`; restringir el entorno a la rama `main`
- [X] T003 [P] Resolver y anotar los SHA de commit fijados (con la versión en comentario, como el resto de acciones) de `imranismail/setup-kustomize` y `actions/create-github-app-token` en `specs/LAB-81-continuous-deploy-dev/research.md` (sección nueva "Acciones fijadas"); `KUSTOMIZE_VERSION` se lee de `versions.env` de `shoplab-infra` (hoy 5.8.2)
- [X] T004 [P] Crear el directorio `.github/scripts/tests/` con un `README` de una línea en `.github/scripts/README.md` que explique cómo ejecutar las pruebas (`bash .github/scripts/tests/deploy-dev.test.sh`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: arnés de pruebas y esqueleto del script; bloquea todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T005 Crear fixtures en `.github/scripts/tests/fixtures/overlay-dev/kustomization.yaml` (copia mínima del overlay `dev` con las tres imágenes de `ghcr.io/leandroamariles/{backend,frontend,ingest}` fijadas por `digest`) y `.github/scripts/tests/fixtures/base/kustomization.yaml` más un `deployment.yaml` por servicio para que `kustomize build` funcione sin red
- [X] T006 Crear el arnés `.github/scripts/tests/deploy-dev.test.sh`: crea un remoto bare temporal con `k8s/overlays/dev` copiado de las fixtures, define `run_deploy` (invoca el script con `APP_SHA`, `RUN_URL`, `INFRA_DIR`, `REGISTRY_PREFIX`, `SERVICES`, `OVERLAY`) y un doble de `docker` y de `policy-check.sh` configurables por variable de entorno; imprime un resumen y sale distinto de 0 si algún caso falla
- [X] T007 Crear el esqueleto `.github/scripts/deploy-dev.sh` (`set -euo pipefail`, validación de variables obligatorias, `die`/`log`, códigos de salida 10-13 de `contracts/deploy-job-contract.md`, escribe `result=` en `$GITHUB_OUTPUT` si existe); no ejecuta lógica aún

**Checkpoint**: el arnés corre y el script rechaza entradas incompletas

---

## Phase 3: User Story 1 - Un merge a main llega a dev sin pasos manuales (Priority: P1) 🎯 MVP

**Goal**: cada merge a `main` con imágenes publicadas deja un commit en el overlay `dev` de `shoplab-infra` que Argo CD sincroniza.

**Independent Test**: fusionar un cambio a `main` y comprobar el commit en `shoplab-infra` con `newTag` = SHA y `dev` convergiendo (quickstart §2-§3).

### Tests for User Story 1 ⚠️ (escribir primero y ver que fallan)

- [X] T008 [US1] Caso "éxito" en `.github/scripts/tests/deploy-dev.test.sh`: tras `run_deploy`, el remoto tiene un commit nuevo que solo toca `k8s/overlays/dev/kustomization.yaml`, las tres imágenes tienen `newTag` = `APP_SHA` y ninguna conserva `digest` (data-model: "ningún `digest`")
- [X] T009 [US1] Caso "sin latest ni kubectl" en `.github/scripts/tests/deploy-dev.test.sh`: el overlay resultante no contiene `latest` y `grep -n kubectl .github/scripts/deploy-dev.sh .github/workflows/images.yml` no devuelve nada
- [X] T010 [US1] Caso "imagen inexistente" en `.github/scripts/tests/deploy-dev.test.sh`: el doble de `docker` falla para `ingest`; el script sale con 10 y el remoto no cambia (FR-006)
- [X] T011 [US1] Caso "build o política fallan" en `.github/scripts/tests/deploy-dev.test.sh`: el doble de `policy-check.sh` falla; el script sale con 12 y el remoto no cambia (R4)

### Implementation for User Story 1

- [X] T012 [US1] En `.github/scripts/deploy-dev.sh`: por cada servicio de `SERVICES`, comprobar con `docker buildx imagetools inspect "$REGISTRY_PREFIX/<svc>:$APP_SHA"` que el tag existe; salir con 10 si falta alguno, antes de editar
- [X] T013 [US1] En `.github/scripts/deploy-dev.sh`: dentro de `$INFRA_DIR/$OVERLAY` ejecutar `kustomize edit set image "$REGISTRY_PREFIX/<svc>=$REGISTRY_PREFIX/<svc>:$APP_SHA"` por servicio y verificar con `yq` que cada entrada queda con `newTag == APP_SHA`, sin `digest` y sin `latest`
- [X] T014 [US1] En `.github/scripts/deploy-dev.sh`: ejecutar `kustomize build "$OVERLAY"` y `scripts/policy-check.sh` de `$INFRA_DIR` (ruta configurable por `POLICY_CHECK`); salir con 12 si fallan y restaurar el árbol con `git checkout -- "$OVERLAY"`
- [X] T015 [US1] En `.github/scripts/deploy-dev.sh`: hacer `git add` solo de `$OVERLAY/kustomization.yaml`, commit con el formato de `contracts/deploy-commit-contract.md` (asunto `chore(dev): [LAB-81] despliega <sha7> a dev`, cuerpo con SHA completo, `RUN_URL` y servicios) con autor `shoplab-deploy[bot]`, y `git push origin HEAD:main`
- [X] T016 [US1] En `.github/workflows/images.yml`: añadir el job `deploy` según `contracts/deploy-job-contract.md` (`needs: publish`, `if: github.event_name == 'push' && github.ref_type == 'branch'`, `environment: dev`, `timeout-minutes: 10`, permisos `contents: read` y `packages: read`) con los pasos: token de la App (`owner`/`repositories` limitados a `shoplab-infra`), `imranismail/setup-kustomize` con la versión de `versions.env`, login en GHCR, clonado de `shoplab-infra`, llamada al script, y resumen en `$GITHUB_STEP_SUMMARY`
- [X] T017 [P] [US1] INFRA: `.github/rulesets/main-protection.json` añadir `bypass_actors` con la App `shoplab-deploy` (`actor_type: Integration`, `bypass_mode: always`) y actualizar el comentario de cabecera de `scripts/apply-ruleset.sh` para explicarlo
- [X] T018 [P] [US1] INFRA: `k8s/overlays/dev/kustomization.yaml` reemplazar el comentario de LAB-80 sobre los digests por uno que explique que el despliegue continuo (LAB-81) fija `newTag` con el SHA
- [ ] T019 [MANUAL] [US1] INFRA: tras fusionar T017, ejecutar `scripts/apply-ruleset.sh leandroAmariles/shoplab-infra` y comprobar con `gh api repos/leandroAmariles/shoplab-infra/rulesets` que la App figura como bypass

**Checkpoint**: las pruebas de T008-T011 pasan; un merge real deja el commit en `shoplab-infra` y `dev` converge

---

## Phase 4: User Story 2 - Acceso mínimo y trazabilidad del cambio (Priority: P1)

**Goal**: credencial de alcance mínimo, nunca expuesta, y commits que enlazan SHA y ejecución de CI.

**Independent Test**: revisar permisos de la App y el mensaje del commit (quickstart §5).

### Tests for User Story 2 ⚠️

- [X] T020 [US2] Caso "formato del commit" en `.github/scripts/tests/deploy-dev.test.sh`: el mensaje cumple `contracts/deploy-commit-contract.md` (asunto con `[LAB-81]` y 7 caracteres del SHA; cuerpo con el SHA de 40, `RUN_URL` y la lista de servicios; autor `shoplab-deploy[bot]`; un solo archivo modificado)
- [X] T021 [US2] Caso "sin credencial" en `.github/scripts/tests/deploy-dev.test.sh`: con el remoto configurado para rechazar el push, el script sale con 13 sin imprimir ninguna URL con token (el token nunca aparece en la salida)

### Implementation for User Story 2

- [X] T022 [US2] En `.github/scripts/deploy-dev.sh`: configurar `user.name`/`user.email` del bot solo en el repositorio clonado, usar el token vía `http.extraheader` o URL temporal sin imprimirla, y `set +x` alrededor de cualquier comando que lo reciba
- [X] T023 [US2] En `.github/workflows/images.yml` (job `deploy`): pasar `RUN_URL` como `${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}`, usar `add-mask` para el token y no exponer secretos a pasos que no los necesitan
- [X] T024 [P] [US2] Crear `docs/despliegue-continuo.md` con las secciones "Credencial" (permisos exactos de la App, dónde viven `DEPLOY_APP_ID` y `DEPLOY_APP_PRIVATE_KEY`, cómo rotar la clave y cómo revocar desinstalando la App) y "Trazabilidad" (cómo leer un commit de despliegue)

**Checkpoint**: la credencial solo alcanza `shoplab-infra` y cada commit es auditable

---

## Phase 5: User Story 4 - Despliegues concurrentes y repetidos son seguros (Priority: P2)

**Goal**: merges seguidos, reejecuciones y conflictos no dejan `dev` inconsistente.

**Independent Test**: quickstart §4.

### Tests for User Story 4 ⚠️

- [X] T025 [US4] Caso "idempotencia" en `.github/scripts/tests/deploy-dev.test.sh`: ejecutar dos veces con el mismo `APP_SHA` crea un solo commit y la segunda sale con 0 y `result=no-change`
- [X] T026 [US4] Caso "servicio no declarado" en `.github/scripts/tests/deploy-dev.test.sh`: `SERVICES` incluye `reports` que el overlay no declara; sale con 11 y el remoto no cambia
- [X] T027 [US4] Caso "conflicto con reintento" en `.github/scripts/tests/deploy-dev.test.sh`: otro commit llega al remoto entre el clonado y el push; el script integra con `pull --rebase` y termina en `pushed`; con el conflicto persistente tras `PUSH_RETRIES` sale con 13
- [X] T028 [US4] Caso "orden de SHA" en `.github/scripts/tests/deploy-dev.test.sh`: dos despliegues secuenciales (SHA A, luego B) dejan el overlay en B (FR-011)

### Implementation for User Story 4

- [X] T029 [US4] En `.github/scripts/deploy-dev.sh`: antes de editar, comprobar con `yq` que cada servicio de `SERVICES` figura en `images:` del overlay; salir con 11 si no
- [X] T030 [US4] En `.github/scripts/deploy-dev.sh`: tras `set image`, si `git diff --quiet` no hay cambios, escribir `result=no-change` y salir con 0 sin commit
- [X] T031 [US4] En `.github/scripts/deploy-dev.sh`: bucle de push con `git pull --rebase origin main` entre intentos, hasta `PUSH_RETRIES` (por defecto 3) con espera creciente de 2 s, 4 s, 8 s; re-aplicar `set image` si el rebase conflictúa en `kustomization.yaml`; salir con 13 al agotarlos
- [X] T032 [US4] En `.github/workflows/images.yml` (job `deploy`): añadir `concurrency: { group: deploy-dev, cancel-in-progress: false }` según R5

**Checkpoint**: T025-T028 pasan; dos merges seguidos terminan en el SHA más reciente

---

## Phase 6: User Story 3 - Rollback documentado (Priority: P2)

**Goal**: guía verificable para volver a la versión anterior de `dev`.

**Independent Test**: quickstart §6.

- [X] T033 [US3] Completar `docs/despliegue-continuo.md` con la sección "Versión desplegada en dev" (leer `newTag` del overlay y `kubectl -n shoplab-dev get deploy` para comparar)
- [X] T034 [US3] Completar `docs/despliegue-continuo.md` con la sección "Rollback": identificar el commit con `git log -- k8s/overlays/dev/kustomization.yaml`, `git revert`, PR en `shoplab-infra` (el ruleset exige PR a humanos), cómo verificar que `dev` vuelve a la versión anterior y la advertencia de que el siguiente merge de la aplicación avanza `dev` de nuevo (R8)
- [X] T035 [P] [US3] Crear `docs/adr/0007-despliegue-continuo-dev-app-con-bypass.md` con la plantilla `docs/adr/0000-plantilla.md`: contexto (ruleset sin bypass), decisión (GitHub App + bypass + validación previa), alternativas (PR automática con auto-merge, PAT) y consecuencias (R1, R2, R9); añadirlo al índice de `docs/adr/README.md`
- [X] T036 [P] [US3] INFRA: enlazar la guía y el rollback desde `docs/gitops.md` en la sección "Tareas habituales" y actualizar la frase del ADR-0006 sobre "actualización automática de las imágenes tras un release" solo si contradice el nuevo flujo (`docs/adr/0006-gitops-pull-repo-infra-separado.md`)

**Checkpoint**: un desarrollador ajeno sigue la guía y revierte el último despliegue

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T037 [P] Añadir `actionlint` a la validación de workflows: ejecutar `actionlint .github/workflows/images.yml` y corregir hallazgos; no añadir `kubectl` ni `:latest` en ningún archivo tocado
- [X] T038 [P] Ejecutar `bash .github/scripts/tests/deploy-dev.test.sh` completo y `shellcheck .github/scripts/deploy-dev.sh .github/scripts/tests/deploy-dev.test.sh`
- [ ] T039 Revisar `git status --short` y `git diff --staged` en ambos repositorios (sin archivos temporales, solo cambios previstos) según la lista de pre-commit de `CLAUDE.md`
- [ ] T040 Abrir la PR hermana de `shoplab-infra` primero (T017, T018, T036) con título `chore(ci): [LAB-81] permite el bypass de la App de despliegue en el ruleset de main` y fusionarla; ejecutar T019
- [ ] T041 Abrir la PR de `shoplab-helpdesk` con título `feat(ci): [LAB-81] despliegue continuo a dev con kustomize edit set image`
- [ ] T042 Tras fusionar, ejecutar la validación de `quickstart.md` §2-§6 con un merge real y registrar los resultados en `specs/LAB-81-continuous-deploy-dev/quickstart.md` (sección "Resultados"), como se hizo en LAB-79
- [ ] T043 No cerrar LAB-81 en Jira hasta que el primer despliegue real haya convergido en `dev` (T042)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 antes de T002, T017 y T019
- **Foundational (Phase 2)**: depende de T004; bloquea todas las historias
- **US1 (Phase 3)**: depende de Foundational; T019 depende de T001 y de T017 fusionada
- **US2 (Phase 4)**: depende de US1 (extiende el script y el job)
- **US4 (Phase 5)**: depende de US1 (extiende el script y el job); independiente de US2 salvo por edición del mismo archivo, así que se hace después
- **US3 (Phase 6)**: documentación; puede empezar tras US1, pero se cierra al final para describir el comportamiento real
- **Polish (Phase 7)**: depende de todo lo anterior; T040 va antes de que la PR de la aplicación despliegue

### Within Each Story

- Pruebas primero y en rojo; después el script; después el workflow
- Todas las tareas de `deploy-dev.sh` y de `images.yml` editan el mismo archivo: **no** son paralelas entre sí

### Parallel Opportunities

- T003 y T004 en paralelo
- T017 y T018 (repositorio `shoplab-infra`) en paralelo con T008-T016
- T024, T035 y T036 en paralelo (archivos distintos)
- T037 y T038 en paralelo

---

## Parallel Example: User Story 1

```bash
# Tras T007, en paralelo (repositorios distintos):
Task: "T017 INFRA ruleset con bypass en .github/rulesets/main-protection.json"
Task: "T018 INFRA comentario del overlay dev en k8s/overlays/dev/kustomization.yaml"
Task: "T008-T011 pruebas del script en .github/scripts/tests/deploy-dev.test.sh"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup + Foundational
2. US1 (T008-T019)
3. **STOP y validar** con un merge real (quickstart §2-§3). Sin US4 el MVP aún puede perder un orden raro de merges, por lo que no se activa el bypass (T019) antes de completar US4.

### Incremental Delivery

1. US1 → commit y convergencia
2. US2 → credencial y trazabilidad endurecidas
3. US4 → concurrencia e idempotencia (condición para activar T019 en producción)
4. US3 → guía y ADR
5. Polish → PR hermana, PR de la aplicación y validación en vivo

---

## Notes

- Dos PR: `shoplab-infra` primero (bypass), después `shoplab-helpdesk`. Activar el bypass (T019) hasta tener US4 listo
- Mientras no exista la App ni el bypass, el job `deploy` fallará visiblemente al fusionar; por eso T040 precede a T041
- Commit tras cada tarea o grupo lógico, con Conventional Commits

## Registro de implementación (2026-10-26)

- Pendientes de una persona: T001, T002 y T019 (`[MANUAL]`, ajustes de GitHub), T039 (revisión del diff antes de
  commitear), T040-T042 (PR hermana, PR de la aplicación y validación en vivo) y T043 (Jira).
- Desviaciones respecto a lo planeado:
  - T014/T013: la comprobación se hace sobre el render de `kustomize build`, sin `yq`; `policy-check.sh` sí usa el `yq`
    preinstalado en `ubuntu-latest`. Ver `research.md`, "Ajustes de implementación".
  - T017: `main-protection.json` conserva `bypass_actors: []` porque el ID de la App no existe hasta T001;
    `scripts/apply-ruleset.sh` de `shoplab-infra` inyecta el actor con `DEPLOY_APP_ID=<id>`.
  - T018: `kustomize edit` reformatea todo el archivo y desplaza los comentarios, así que la PR de infra normaliza una
    vez el overlay `dev` al formato canónico (render idéntico, verificado) para que cada despliegue solo toque las
    líneas de `images:`.
  - T031: en cada reintento se descarta el commit propio y se reaplica la edición sobre el remoto en lugar de
    `pull --rebase`, evitando conflictos en `kustomization.yaml`.
  - T037/T038: `actionlint` y `shellcheck` se ejecutaron con sus imágenes de Docker; ambos limpios.
