# Data Model: Escaneo con Trivy

No hay persistencia propia: las entidades son archivos versionados y artefactos de ejecución.

## Escaneo de Trivy

| Campo | Descripción |
|---|---|
| commit | SHA analizado |
| evento | `pull_request` o `push` a `main` |
| alcance | raíz del repositorio, excluidos `node_modules`, `target`, `.venv` |
| escáneres | vulnerabilidades, secretos, configuración |
| veredicto | pasa / falla por gate / falla por infraestructura |

Transiciones: `iniciado → reporte SARIF generado → (subido a Security | artefacto) → gate evaluado →
pasa | falla`. Un error antes del gate es fallo de infraestructura.

## Hallazgo

| Campo | Descripción |
|---|---|
| tipo | vulnerabilidad / secreto / configuración |
| severidad | LOW, MEDIUM, HIGH, CRITICAL (solo HIGH y CRITICAL bloquean) |
| id | CVE/GHSA, regla de secreto o id de misconfig (p. ej. `AVD-DS-0002`) |
| ubicación | archivo (y línea en secretos/configuración) |
| paquete / versión / corregida en | solo vulnerabilidades; sin versión corregida no bloquea |

Regla del gate: bloquea ⇔ severidad ∈ {HIGH, CRITICAL} ∧ (tipo ≠ vulnerabilidad ∨ existe versión
corregida) ∧ id ∉ `.trivyignore`.

## Excepción (`.trivyignore`)

| Campo | Descripción |
|---|---|
| id | identificador del hallazgo, una línea |
| comentario previo | motivo, responsable y fecha de revisión (obligatorio por convención) |

Formato: bloque de comentarios `#` inmediatamente antes de cada id.

## Configuración de Dependabot

| Campo | Descripción |
|---|---|
| ecosistema | maven, npm, pip, github-actions, docker (+ docker-compose) |
| directorio | donde vive el manifiesto |
| frecuencia | semanal |
| límite de PRs abiertos | 5 por ecosistema |
