# Research: Protección de main con checks requeridos

## 1. Qué checks se exigen: `ci-gate` + `pr-title`

- **Decision**: El ruleset exige exactamente dos checks: `ci-gate` y `pr-title`.
- **Rationale**: `ci-gate` (LAB-15) depende de `changes`, `backend`, `qodana`, `trivy`, `frontend` y
  `python`, corre con `if: always()` y falla si algún job falló o se canceló; un job omitido por
  el filtro de rutas no cuenta como fallo. Así cubre "build y tests con gate de cobertura, Qodana y
  Trivy" (FR-004) sin bloquear PRs que no tocan un componente (FR-005/006), y los jobs futuros
  (RAG eval, imagen) se incorporan añadiéndolos a sus `needs`, sin tocar el ruleset. Es la misma
  decisión ya documentada en el README y en LAB-75/LAB-76.
- **Alternatives considered**: Exigir `backend`, `qodana` y `trivy` por separado: un job omitido por
  `if` se reporta como "skipped" (satisfecho), así que funcionaría, pero cada job nuevo exigiría
  editar el ruleset y un cambio de nombre dejaría PRs esperando para siempre (edge case de la spec).
  Se descarta por fragilidad.

## 2. Validación del título en un workflow aparte

- **Decision**: Nuevo `.github/workflows/pr-title.yml`, un único job `pr-title`, disparado por
  `pull_request` con `types: [opened, edited, synchronize, reopened]`.
- **Rationale**: Editar el título solo emite el evento `edited`. Añadirlo a `ci.yml` relanzaría todo
  el pipeline (Maven, Qodana, Trivy) por un cambio de texto y, además, `concurrency` cancelaría
  ejecuciones útiles. Un workflow independiente se reevalúa en segundos y no consume los minutos del
  CI. `synchronize` y `reopened` aseguran que el check exista también en PRs nuevos o reabiertos.
  El job no hace checkout ni ejecuta código del PR; solo lee el título vía API con
  `pull-requests: read`.
- **Alternatives considered**: `pull_request_target` (recomendado por la acción para forks con
  secretos): innecesario, la acción solo lee el título y el repositorio no recibe PRs de forks de
  confianza dudosa; evita una superficie de ataque conocida. Añadir `edited` a `ci.yml`: descartado
  por coste.

## 3. Formato del título y reglas de validación

- **Decision**: `type(scope): [LAB-<n>] descripción`. Tipos permitidos: `feat`, `fix`, `perf`,
  `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert`. Alcance opcional. Se valida con
  `types` de la acción y un `subjectPattern` `^\[LAB-\d+\] .+$` con mensaje de error explicativo.
- **Rationale**: Es el formato de la US (`feat(chat): [LAB-12] respuesta en streaming`). Los tipos
  son los que release-please interpreta para versionar/changelog (`feat`, `fix`, `perf`, `revert`
  generan entradas; `docs`, `chore`, `refactor`, `test`, `build`, `ci` no suben versión). `hotfix`
  no es un tipo Conventional Commits: un hotfix usa `fix`. Un único formato fijo con corchetes lo
  hace parseable (edge case de la spec).
- **Alternatives considered**: Clave al inicio (`LAB-12 feat(...)`, formato actual del README para
  commits de rama): rompe Conventional Commits y release-please no parsea el commit; descartado para
  el título del PR. Los commits intermedios no se validan porque se aplastan.

## 4. Pull requests de Dependabot

- **Decision**: Dos pasos condicionados por actor. Para personas, tipo + `subjectPattern` con clave
  LAB. Para `dependabot[bot]`, solo el tipo (sin exigir clave LAB). Ambos publican el mismo check
  `pr-title`.
- **Rationale**: Dependabot ya usa el prefijo `chore(deps)` (LAB-76) pero no puede conocer una clave
  LAB. Seguir validando el tipo mantiene los commits legibles por release-please y es el tratamiento
  diferenciado documentado que pide FR-020. Un job omitido con `if` también contaría como satisfecho,
  pero validar el tipo es más estricto y barato.
- **Alternatives considered**: Omitir por completo la validación para bots (menos garantía); exigir
  la clave LAB a Dependabot (imposible sin cambiar el flujo); `ignoreLabels` (una etiqueta puesta a
  mano saltaría la regla, abre un bypass).

## 5. Dónde vive la definición del ruleset

- **Decision**: `.github/rulesets/main-protection.json` en el formato de la API de rulesets de
  GitHub, con comandos `gh api` documentados en el README para crear o actualizar el ruleset y para
  compararlo con el activo.
- **Rationale**: GitHub no lee rulesets desde el repositorio; versionar el JSON da auditoría,
  revisión por PR y restauración (FR-017, SC-008). Sin script: crear = un `gh api -X POST`,
  actualizar = un `gh api -X PUT .../rulesets/<id>`, comparar = `gh api` + `diff`. Dos comandos no
  justifican un script (YAGNI).
- **Contenido**: `target: branch` sobre `~DEFAULT_BRANCH`; `enforcement: active`; `bypass_actors: []`;
  reglas `deletion`, `non_fast_forward`, `required_linear_history`, `pull_request`
  (`allowed_merge_methods: ["squash"]`, 0 aprobaciones requeridas), `required_status_checks`
  (`strict_required_status_checks_policy: true`, contextos `ci-gate` y `pr-title` fijados a la app
  GitHub Actions para impedir que otra integración publique un check homónimo).
- **Alternatives considered**: Branch protection clásico: sin `bypass_actors` explícito, sin
  `non_fast_forward` separado y sin versionado exportable tan directo; GitHub recomienda rulesets.
  Terraform/Probot settings: dependencia nueva desproporcionada para un solo repositorio.

## 6. Squash merge y mensaje del commit

- **Decision**: Además del ruleset, ajustar el repositorio con `gh api -X PATCH repos/{owner}/{repo}`:
  `allow_squash_merge: true`, `allow_merge_commit: false`, `allow_rebase_merge: false`,
  `squash_merge_commit_title: PR_TITLE`, `squash_merge_commit_message: BLANK`,
  `delete_branch_on_merge: true`.
- **Rationale**: El ruleset limita los métodos permitidos, pero el título del commit de squash es un
  ajuste del repositorio: por defecto, un PR de un solo commit usa el título de ese commit, no el del
  PR. `PR_TITLE` garantiza que `main` reciba el título validado (FR-014); `BLANK` evita que el cuerpo
  del PR (plantilla con checklist) ensucie el mensaje. Borrar la rama al fusionar evita ramas
  muertas (no afecta a la regla de borrado, que es solo de `main`).
- **Alternatives considered**: Dejar el título por defecto: rompería a release-please en PRs de un
  commit con un mensaje de rama no validado.

## 7. Orden de activación

- **Decision**: Fusionar primero la rama (con `pr-title.yml` en `main`) y luego aplicar ruleset y
  ajustes del repositorio. Hasta entonces `main` no está protegido.
- **Rationale**: Un check requerido que nunca se ejecutó deja los PRs esperando indefinidamente. El
  primer PR que pase por `pr-title` es el de esta propia historia. Además LAB-75 y LAB-76 deben
  haber fusionado antes (`ci-gate` ya los incluye y la rama actual los contiene). Para el primer
  PR, que se fusiona antes de existir la protección, se verifica a mano que el título cumple el
  formato.
- **Alternatives considered**: Activar el ruleset antes de fusionar: bloquearía el propio PR por
  `pr-title` ausente en `main`; o requeriría un bypass temporal, que contradice FR-003.

## 8. Reconciliación del formato de commit en el README

- **Decision**: Mantener `LAB-<n> type(scope): description` para commits de rama (convención ya
  establecida en las historias anteriores), documentar que los commits de rama no se validan y que
  el título del PR sigue `type(scope): [LAB-<n>] description` porque es el que llega a `main`.
- **Rationale**: Reescribir la convención de commits existentes es un cambio de alcance mayor y sin
  beneficio: los commits de rama desaparecen en el squash. Se evita ambigüedad con una tabla
  de dos filas en el README (Constitution: "Commits" conserva el formato; el título del PR es nuevo).
- **Alternatives considered**: Unificar ambos formatos y validar commits de rama con commitlint:
  más fricción local sin valor, dado el squash.

## 9. Riesgos y mitigaciones

- **Check renombrado**: el nombre del job `ci-gate`/`pr-title` es parte del contrato
  ([contracts](contracts/)); cualquier cambio exige actualizar el JSON del ruleset en el mismo PR.
- **Plan del repositorio** (verificado en T002): el repositorio es público y el usuario es
  administrador, así que los rulesets están disponibles. Ya existe un ruleset activo `main-protect`
  (id 25783802: `deletion`, `non_fast_forward` y `ci-gate` con `strict_required_status_checks_policy`
  e `integration_id` 15368, sin bypass). El JSON versionado conserva ese nombre y la activación es un
  `PUT` sobre él, no un `POST`, para no duplicar reglas. Los ajustes del repositorio estaban en
  `allow_merge_commit: true`, `allow_rebase_merge: true` y `squash_merge_commit_title:
  COMMIT_OR_PR_TITLE`; el `PATCH` los corrige.
- **Bloqueo accidental del único mantenedor**: sin bypass, un ruleset mal configurado puede impedir
  fusionar. Mitigación: se edita desde Settings (el propietario puede editar el ruleset) y la
  definición versionada permite restaurarlo.
