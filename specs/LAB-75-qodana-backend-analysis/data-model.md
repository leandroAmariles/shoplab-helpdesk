# Data Model: Análisis estático con Qodana

No hay datos persistentes de aplicación. Las entidades describen los elementos del pipeline.

## Job `qodana` (en `.github/workflows/ci.yml`)

| Campo | Valor |
|---|---|
| Dependencia | `needs: changes` |
| Condición de ejecución | `changes.backend == 'true'` o `changes.ci == 'true'` |
| Runner / timeout | `ubuntu-latest` / 20 min |
| Permisos del job | `contents: read`, `security-events: write`, `pull-requests: write`, `checks: write` |
| Secretos | `QODANA_TOKEN` opcional (solo si Community lo exigiera o se adopta Cloud) |
| Agregado en | `needs` de `ci-gate` |

### Pasos

| Orden | Paso | Condición |
|---|---|---|
| 1 | `actions/checkout@v4` | siempre |
| 2 | `JetBrains/qodana-action` con `args: --project-dir,backend,--baseline,qodana.sarif.json` | siempre |
| 3 | `github/codeql-action/upload-sarif@v4` | `always()` y PR no proveniente de fork |

### Resultado

`queued -> in_progress -> success | failure | cancelled`; omitido (`skipped`) si no cambió el
backend. Un fallo del gate y un fallo de infraestructura son ambos `failure`, pero se distinguen en
el log: el gate lo declara el paso de Qodana con el umbral excedido; un error de herramienta aparece
como error del paso antes de producir resultados (FR-016).

## Configuración `backend/qodana.yaml`

| Clave | Valor |
|---|---|
| `version` | `"1.0"` |
| `linter` | `qodana-jvm-community` |
| `profile.name` | `qodana.recommended` |
| `exclude` (vía `inspections` + `ignore`, grupo `ALL`) | `target/`, código generado |
| `failureConditions.severityThresholds` | `critical: 0`, `high: 0` |

Regla de validación: sin umbral para `moderate`, `low`, `info` (solo informativos, FR-006).

## Baseline `backend/qodana.sarif.json`

| Campo | Valor |
|---|---|
| Formato | SARIF producido por Qodana |
| Contenido | problemas existentes al adoptar la herramienta |
| Efecto | los problemas presentes en el baseline se tratan como heredados |
| Regeneración | ejecución sobre `main` + commit en PR dedicado |

## Resultados publicados

| Destino | Origen | Cuándo |
|---|---|---|
| Pestaña Security | `${{ runner.temp }}/qodana/results/qodana.sarif.json` vía `upload-sarif` | siempre, salvo forks |
| Comentario del PR | `post-pr-comment: true` | PRs no provenientes de fork |
| Anotaciones | `use-annotations: true` | PRs no provenientes de fork |
| Artefacto `qodana-report` | `upload-result: true` | siempre (respaldo) |

## Severidad y gate

| Severidad | Umbral | Efecto |
|---|---|---|
| critical | 0 | falla el check |
| high | 0 | falla el check |
| moderate, low, info | sin umbral | solo informativo |

Los umbrales se evalúan contra problemas nuevos respecto al baseline (por validar, research 4).
