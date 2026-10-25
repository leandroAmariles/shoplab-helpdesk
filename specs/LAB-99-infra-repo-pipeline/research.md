# Research: LAB-99 Pipeline del repo de infraestructura

Lo que no está verificado se marca **a validar en T001 (spike)**; no se inventan versiones.

## R1. Estructura del workflow y check requerido

- **Decisión**: un solo `ci.yml` con jobs `changes`, `static`, `kustomize`, `trivy`, `plan`, `smoke` y un
  agregador `ci-gate` (`if: always()`, falla si algún `needs` terminó en `failure` o `cancelled`; los omitidos
  por filtro de rutas no bloquean). Solo `ci-gate` y `pr-title` son checks requeridos.
- **Justificación**: es el patrón del repo de la app (contrato LAB-77). Un único contexto estable evita tocar el
  ruleset cada vez que cambia un job y garantiza que siempre haya estado (edge case de PR solo de docs).
- **Alternativas**: exigir cada job en el ruleset (frágil con filtros de rutas: un job omitido deja el check
  pendiente); un workflow por control (más archivos, mismos problemas de agregación).

## R2. Herramientas y versiones únicas

- **Decisión**: `versions.env` (`TERRAFORM_VERSION`, `TFLINT_VERSION`, `KUSTOMIZE_VERSION`, `KUBECONFORM_VERSION`,
  `YQ_VERSION`, `TRIVY_VERSION`, `FLOCI_IMAGE`, `KUSTOMIZE_DIR`). Cada job la vuelca a `$GITHUB_ENV` y
  `scripts/check-tools.sh` la usa en local para comparar versiones instaladas e indicar qué falta.
- **Justificación**: cumple FR-020 con un solo archivo; Dependabot/Renovate pueden actualizarla después.
- **Alternativas**: `.tool-versions` de asdf (no lo lee `setup-terraform`; añade un gestor más); contenedor
  único con todas las herramientas (garantiza paridad pero obliga a construir y mantener una imagen).
- **A validar en T001**: versiones exactas disponibles de cada herramienta y compatibilidad de TFLint con el
  provider `aws` fijado en LAB-98.

## R3. Políticas de manifiestos

- **Decisión**: `scripts/policy-check.sh` recibe los manifiestos ya construidos (`kustomize build`) y los analiza
  con `yq`: imágenes en `containers`, `initContainers` y `ephemeralContainers` de cualquier workload; `repoURL` en
  `spec.source`, `spec.sources[]` y `ApplicationSet`; `targetRevision` en los mismos puntos. Además busca el
  literal `CAMBIA-ESTE-OWNER` en todo el texto. Cada violación imprime `regla | archivo/recurso | valor`.
- **Reglas de imagen**: falla si no hay tag, si el tag es `latest`, o si hay `@sha256:` junto a `latest`; digest
  sin tag es válido.
- **Justificación**: tres reglas simples no justifican un motor de políticas; `yq` + shell se ejecuta igual en
  local y CI y se prueba con fixtures (`scripts/tests/`).
- **Alternativas**: Conftest/OPA (más expresivo, pero añade herramienta y lenguaje Rego para tres reglas);
  Kyverno CLI (pensado para admisión en clúster); Checkov custom (más pesado). Se reevalúa si las reglas crecen.
- **Límite conocido**: no ve imágenes que solo aparecen dentro de charts de Helm que Argo CD renderiza.

## R4. kubeconform y CRDs

- **Decisión**: `kubeconform -strict -summary` con `-schema-location default` más el catálogo comunitario de
  CRDs (Argo CD `Application`, etc.) vía plantilla de URL; los recursos sin esquema se rechazan
  (sin `-ignore-missing-schemas`) salvo lista explícita de excepciones en el script.
- **Justificación**: ignorar esquemas ausentes dejaría pasar manifiestos inválidos en silencio.
- **A validar en T001**: si el catálogo cubre las versiones de CRD instaladas por LAB-98 (Argo CD, ESO,
  kube-prometheus-stack); de lo contrario, vendorizar esquemas necesarios en `schemas/`.
- **Overlay vacío**: `kustomize-build.sh` falla si el overlay no existe o la salida tiene 0 recursos (FR-008).

## R5. `terraform plan` contra Floci y comentario en el PR

- **Decisión**: Floci como `services:` del job, con `image` de `FLOCI_IMAGE`, puerto 4566, volumen
  `/var/run/docker.sock` y healthcheck; el job espera `/_floci/health` (o el puerto) con tiempo límite de 2 min.
  Pasos: bootstrap del estado (LAB-98) → `init` con backend S3 de Floci → `plan -no-color -out` →
  `tf-plan-comment.sh`. El comentario lleva el marcador `<!-- shoplab-infra-tf-plan -->`; `github-script` busca
  un comentario con ese marcador y lo actualiza, o crea uno. Se trunca a ~60 000 caracteres con aviso y enlace a
  la ejecución.
- **Sin permiso de escritura (forks, Dependabot)**: se omite el comentario y el plan se escribe en
  `$GITHUB_STEP_SUMMARY`.
- **Limitación**: el estado es efímero, así que el plan siempre es "crear todo" (ver plan.md, Riesgos). Sirve para
  detectar errores de plan y ver el conjunto de recursos, no un diff contra un entorno vivo.
- **Alternativas**: `terraform plan` sin Floci (no resuelve data sources ni providers que consultan AWS); acción de
  terceros de comentarios de plan (dependencia extra para algo que `github-script` resuelve en 20 líneas).
- **A validar en T001**: que `services.<id>.volumes` permita montar el socket de Docker; plan B:
  `docker compose up -d --wait` del `docker-compose.yml` de LAB-98 en un paso del job.

## R6. Prueba de humo

- **Decisión**: job `smoke` que ejecuta `scripts/smoke.sh` reusando `scripts/up.sh`/`down.sh` y `verify-clean.sh`
  de LAB-98: apply completo, espera con `kubectl wait` a que todas las `Application` de Argo CD tengan
  `sync.status=Synced` y `health.status=Healthy` (tiempo límite configurable, 10 min), y destroy en un paso
  `if: always()`. Si falla, imprime `kubectl get applications -A` y los eventos recientes; sube el log como
  artefacto.
- **Cuándo corre**: en PR que toque `terraform/`, `kubernetes/`, `scripts/` o el workflow; en push a `main` y en
  `workflow_dispatch`. Si el filtro lo omite, `ci-gate` no se bloquea.
- **Riesgo de recursos**: el runner estándar es limitado; si el humo no cabe, se restringe a `main` +
  `workflow_dispatch` y se documenta (decisión a tomar tras medir en T001).
- **Limpieza**: Floci crea contenedores y redes fuera de compose (hallazgo de LAB-98); `verify-clean.sh` los
  elimina y su fallo hace fallar el job.

## R7. Trivy config con SARIF

- **Decisión**: mismo patrón de dos pasadas que `ci.yml` de la app: informe (`format: sarif`, `exit-code: 0`) →
  subida con `upload-sarif` (`category: trivy-config`, `if: always()` y solo si `CAN_WRITE`) → compuerta
  (`format: table`, `exit-code: 1`). `scan-type: config`, `severity: HIGH,CRITICAL`, sobre `terraform/` y el
  directorio de Kustomize, con `trivyignores: .trivyignore` (formato con motivo/responsable/fecha de LAB-76).
- **Justificación**: reutiliza una configuración ya probada; la pasada de informe separa errores de la herramienta
  de hallazgos reales.
- **Nota**: Trivy config analiza los YAML fuente; para overlays se escanea además la salida de `kustomize build`
  guardada en un directorio temporal, para ver el resultado final.

## R8. Ruleset de `main` en `shoplab-infra`

- **Decisión**: copiar `.github/rulesets/main-protection.json` del repo de la app (mismas reglas: `deletion`,
  `non_fast_forward`, `required_linear_history`, PR con squash, checks estrictos, sin bypass) y aplicarlo con
  `gh api` (`repos/<owner>/shoplab-infra/rulesets`). Checks requeridos: `ci-gate` y `pr-title`. Ajustes del
  repositorio como en LAB-77 (`squash_merge_commit_title=PR_TITLE`, borrar rama al fusionar).
- **Orden**: aplicar el ruleset **después** de que `ci-gate` haya corrido al menos una vez en el repo, para evitar
  dejar `main` bloqueado por un check que no existe todavía.
- **Alternativa**: configurarlo a mano en la UI; descartado por no ser reproducible ni revisable.

## R9. `make check` y paridad con CI

- **Decisión**: `make check` encadena `check-tools`, `fmt-check`, `validate`, `lint`, `tf-test`, `kustomize`,
  `policies` y `trivy` (si está instalado; si falta, falla con instrucción, no se omite en silencio). `plan` y
  `smoke` son targets aparte (`make plan`, `make smoke`) por requerir Docker y tiempo. Cada target llama al mismo
  script que el job de CI.
- **Windows**: los scripts se ejecutan con Git Bash; se documenta `MSYS_NO_PATHCONV=1` donde aplique (hallazgo
  de LAB-98).

## R10. Seguridad del pipeline

- **Decisión**: `permissions: contents: read` por defecto; `security-events: write` solo en `trivy`;
  `pull-requests: write` solo en `plan`. Acciones de terceros fijadas por SHA (como Trivy en la app). Credenciales
  AWS ficticias en variables del job, nunca secretos reales. `concurrency` con `cancel-in-progress` solo en PR.
- **Justificación**: mínimo privilegio y reproducibilidad; el plan no necesita secretos del repositorio.
