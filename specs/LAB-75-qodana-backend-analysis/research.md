# Research: Análisis estático con Qodana en el backend

Fuentes: README de `JetBrains/qodana-action` (tag v2026.2.2, publicado 2026-09-29) y documentación de
`qodana.yaml` de JetBrains. Lo que la documentación no confirma está marcado como **Por validar** y
tiene su verificación en [quickstart.md](quickstart.md).

## 1. Dónde vive el job

- **Decisión**: job `qodana` dentro de `.github/workflows/ci.yml`, con `needs: changes`, la misma
  condición que `backend` (`backend == 'true' || ci == 'true'`) y agregado a `needs` de `ci-gate`.
- **Rationale**: reutiliza filtros por ruta, concurrencia y el check estable `ci-gate` (FR-001,
  FR-013, FR-014). Al ser un job paralelo es independiente del job `backend` (FR-011). Como
  `qodana.yaml` está bajo `backend/`, un cambio de configuración ya activa el filtro (Escenario 4 de
  la historia 4) y no hace falta un filtro nuevo.
- **Alternativas**: workflow separado con `paths:` en el trigger (descartado en LAB-15: un check
  requerido que no se dispara deja el PR pendiente); job con `needs: backend` (descartado: serializa
  y un fallo de estilo ocultaría los hallazgos de Qodana).

## 2. Linter y token

- **Decisión**: `linter: qodana-jvm-community` en `backend/qodana.yaml`, sin `QODANA_TOKEN`.
- **Rationale**: la historia pide "linter de JVM" y la spec asume Qodana sin Cloud (FR-010). La
  edición Community es gratuita.
- **Por validar**: el README de la acción no indica qué linters exigen token. Si Community no
  ejecutara sin token, el plan B es registrar `QODANA_TOKEN` como secreto del repo y pasarlo en `env`
  del paso (FR-009); el job debe seguir sin fallar en forks (research 5).
- **Alternativas**: `qodana-jvm` (de pago, requiere token); Qodana CLI directo (más control, más
  mantenimiento; la historia exige explícitamente `qodana-action`).

## 3. Ubicación de configuración y baseline

- **Decisión**: `backend/qodana.yaml` y `backend/qodana.sarif.json`; el paso usa
  `args: --project-dir,backend,--baseline,qodana.sarif.json`.
- **Rationale**: la acción documenta `--baseline` como argumento CLI y no existe una clave `baseline`
  en `qodana.yaml`. La acción corre desde la raíz del repo, por lo que `--project-dir backend` limita
  el análisis a ese proyecto (FR-001, FR-003).
- **Por validar**: si la ruta del baseline se resuelve respecto a la raíz o a `backend/`; se ajusta
  en la primera ejecución (`--baseline,backend/qodana.sarif.json` si fuera necesario).
- **Alternativas**: archivos en la raíz (mezcla el config del backend con futuros Angular/Python).

## 4. Quality gate por severidad

- **Decisión**: en `qodana.yaml`, `failureConditions.severityThresholds` con `critical: 0` y
  `high: 0`; `moderate`, `low` e `info` sin umbral.
- **Rationale**: cubre FR-006 (falla solo por severidad alta; las inferiores quedan informativas).
- **Por validar**: la documentación indica que, en modo baseline, el umbral total (`failThreshold`)
  cuenta solo problemas nuevos y ausentes, pero no lo afirma para `severityThresholds`. Se verifica
  en el quickstart: con baseline vigente, un PR limpio debe pasar aunque el baseline contenga
  problemas altos. Si los umbrales contaran también lo heredado, el plan B es una de estas:
  excluir esas inspecciones heredadas en el YAML con `ignore` (dejar constancia en el PR) o
  reemplazar el gate por `--fail-threshold` sobre el total de nuevos.
- **Alternativas**: solo `failThreshold` total (no distingue severidad, contradice FR-006).

## 5. Publicación de resultados

- **Decisión**:
  - Anotaciones y comentario resumen: inputs nativos `use-annotations: true` y
    `post-pr-comment: true` (FR-008; la acción actualiza su comentario).
  - Pestaña Security: paso `github/codeql-action/upload-sarif@v3` con
    `${{ runner.temp }}/qodana/results/qodana.sarif.json`, con `if: always()` para que se publique
    también cuando el gate falla (FR-007).
  - Respaldo: `upload-result: true` sube los resultados como artefacto `qodana-report`.
- **Rationale**: la acción no sube a Code Scanning por sí sola; su README lo hace con un paso
  separado de CodeQL.
- **Por validar**: que Code Scanning esté habilitado (gratis en repos públicos; en privados requiere
  GitHub Advanced Security). Si no lo está, el paso se marca `continue-on-error` y el artefacto
  queda como único soporte (supuesto documentado en la spec).
- **Alternativas**: solo artefacto (no cumple la historia); API de Checks propia (innecesario).

## 6. Pull requests desde forks

- **Decisión**: en PRs de fork el `GITHUB_TOKEN` es de solo lectura, por lo que se desactivan
  `post-pr-comment` y `use-annotations` y se omite `upload-sarif` con la condición
  `github.event.pull_request.head.repo.fork != true` (evaluada en el job). El análisis y el gate
  siguen ejecutándose; los resultados quedan en el artefacto.
- **Rationale**: FR-010 y edge case de forks: sin falsos fallos ni secretos expuestos.

## 7. Permisos del job

- **Decisión**: permisos a nivel de job `contents: read`, `security-events: write`,
  `pull-requests: write`, `checks: write`; el workflow mantiene `contents: read` por defecto (FR-012).
- **Rationale**: el README de la acción usa `contents: write` solo para `push-fixes`, que aquí está
  en `none`; basta lectura de contenido.

## 8. Modo de análisis (PR vs. completo)

- **Decisión**: `pr-mode: false` (análisis completo con baseline), `use-caches: true`.
- **Rationale**: `pr-mode` analiza solo archivos cambiados y requiere historial completo
  (`fetch-depth: 0`); con baseline, el análisis completo es determinista y compara contra la misma
  referencia que `main` (edge cases de `main` y baseline). El backend es pequeño, así que el costo es
  bajo. Se puede reevaluar `pr-mode` si el tiempo supera el objetivo de 15 min.
- **Alternativas**: `pr-mode: true` (más rápido en repos grandes, más piezas móviles).

## 9. Versión de la acción

- **Decisión**: `JetBrains/qodana-action@v2026.2` (tag mayor de calendario; la última publicada es
  v2026.2.2).
- **Resultado**: el tag móvil no existe; se fija `v2026.2.2`.

## 10. Falsos positivos y regeneración del baseline

- **Decisión**: los falsos positivos se suprimen con `ignore` en `qodana.yaml` o anotación en el
  código, revisados en el PR; el baseline se regenera con una ejecución de la acción sobre `main` y
  se commitea en un PR propio. El procedimiento queda documentado en el README.
- **Rationale**: evita desactivar el gate (edge cases de la spec).

## Resultados de validación local (2026-10-24)

Ejecutado con `docker run jetbrains/qodana-jvm-community:2026.2` (Qodana for JVM 2026.2.4) sobre
`backend/`, sin `QODANA_TOKEN`.

| Punto "Por validar" | Resultado |
|---|---|
| Token (decisión 2) | **No hace falta**: la edición Community corre y evalúa el gate sin token. No se añade `env: QODANA_TOKEN` al job. |
| Umbrales con baseline (decisión 4) | **Confirmado** el contador por severidad: con un problema alto deliberado el gate reportó `Detected 2 problems for severity HIGH, fail threshold: 0` y salió con código 255. Con la base actual (0 problemas) no hay deuda alta heredada; el comportamiento con baseline no vacío queda por comprobar en CI. |
| Ruta del baseline (decisión 3) | **Respecto a `backend/`** en la CLI (`--baseline qodana.sarif.json` con proyecto `backend/`); el resumen mostró `Baseline summary`. En la acción con `--project-dir,backend` falta confirmarlo en CI; si no resolviera, usar `backend/qodana.sarif.json`. |
| Tag de la acción (decisión 9) | `v2026.2` **no existe** (solo `v2026.2.0`, `v2026.2.1`, `v2026.2.2`). Se fija `JetBrains/qodana-action@v2026.2.2`. |
| Code Scanning (decisión 5) | Repo **público**; la API responde "no analysis found", es decir, habilitado y sin análisis aún. No se usa `continue-on-error`. |
| `codeql-action` | Existen `v3` y `v4`; se usa `github/codeql-action/upload-sarif@v4`. |

### Ajustes respecto al plan

- `qodana.yaml` usa `exclude` (con `name: All` y `paths`), no `inspections` + `ignore`: la versión
  2026.2 rechaza `inspections` como clave de primer nivel (`Unexpected keys in qodana.yaml`).
- El análisis inicial detectó **0 problemas**, por lo que el baseline es un SARIF mínimo sin
  resultados (el informe completo pesa ~3,9 MB por las reglas y taxonomías); la CLI lo acepta.
- El tiempo de apertura del proyecto fue ~3 min en local; el análisis, 2 s.
