# Data Model: LAB-99 Pipeline del repo de infraestructura

No hay almacenamiento persistente. Las entidades son configuración y resultados que el pipeline produce o consume.

## `ToolVersions` (`versions.env`)

| Campo | Descripción | Regla |
|---|---|---|
| `TERRAFORM_VERSION`, `TFLINT_VERSION`, `KUSTOMIZE_VERSION`, `KUBECONFORM_VERSION`, `YQ_VERSION`, `TRIVY_VERSION` | Versión exacta de cada herramienta | Sin rangos ni `latest` (FR-020) |
| `FLOCI_IMAGE` | Imagen de Floci con tag fijo | Mismo valor que el `docker-compose.yml` de LAB-98 |
| `KUSTOMIZE_DIR` | Directorio base de overlays | Contiene `overlays/{dev,staging,prod}` |
| `ARGOCD_TIMEOUT`, `FLOCI_READY_TIMEOUT` | Tiempos límite en segundos | Positivos |

## `Overlay`

| Campo | Descripción |
|---|---|
| `name` | `dev`, `staging` o `prod` (lista cerrada) |
| `path` | `${KUSTOMIZE_DIR}/overlays/<name>` |
| `rendered` | Salida de `kustomize build` (temporal) |

Reglas: debe existir; la salida debe tener >= 1 recurso (FR-008); cada recurso valida contra su esquema (FR-007).

## `PolicyViolation`

| Campo | Descripción |
|---|---|
| `rule` | `IMAGE_LATEST`, `IMAGE_NO_TAG`, `TEMPLATE_REPO_URL`, `TARGET_REVISION_NOT_MAIN` |
| `overlay` | Overlay donde se detectó |
| `resource` | `kind/namespace/name` y ruta del campo |
| `value` | Valor encontrado |

Salida: una línea `rule | overlay | resource | value` por violación (FR-012); código de salida distinto de 0 si hay alguna.
Ver [contracts/policy-rules.md](contracts/policy-rules.md).

## `JobResult` (de `ci-gate`)

| Campo | Valores |
|---|---|
| `job` | `static`, `kustomize`, `trivy`, `plan`, `smoke` |
| `result` | `success`, `failure`, `cancelled`, `skipped` |

Transiciones del gate: `success` si todos son `success` o `skipped`; en otro caso `failure`.

## `PlanComment`

| Campo | Descripción |
|---|---|
| `marker` | `<!-- shoplab-infra-tf-plan -->`, único por PR |
| `body` | Plan en bloque de código, truncado a ~60 000 caracteres con enlace a la ejecución |
| `action` | `create` si no existe, `update` si ya existe |

## `SecurityReport`

SARIF de Trivy (`category: trivy-config`) subido a code scanning; excepciones en `.trivyignore`, cada una con
motivo, responsable y fecha de revisión.

## `Ruleset`

Documento `main-protection.json` con reglas y checks requeridos (`ci-gate`, `pr-title`); ver
[contracts/ci-workflow.md](contracts/ci-workflow.md).
