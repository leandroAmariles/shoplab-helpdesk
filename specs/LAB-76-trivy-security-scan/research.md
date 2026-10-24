# Research: Escaneo con Trivy

## 1. Dónde vive el job

- **Decisión**: job `trivy` en `.github/workflows/ci.yml`, añadido a `needs` de `ci-gate`.
- **Rationale**: hereda concurrencia (cancela ejecuciones obsoletas, FR-015), permisos base y el check
  único exigible. Mismo patrón que `qodana` (LAB-75).
- **Alternativas**: workflow aparte (duplica concurrencia y obliga a exigir un segundo check).

## 2. Sin filtro por ruta

- **Decisión**: el job no usa `needs.changes` ni `if` por ruta; corre en todo PR y en push a `main`.
- **Rationale**: un secreto o una vulnerabilidad pueden entrar en cualquier archivo (spec, FR-001).
  El coste es bajo (escaneo de archivos, sin compilar).
- **Alternativas**: filtrar por manifiestos — pierde secretos y configuración.

## 3. Configuración de Trivy

- **Decisión**: `scan-type: fs`, `scan-ref: .`, `scanners: vuln,secret,misconfig`,
  `severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `skip-dirs: node_modules,target,.venv`,
  `trivyignores: .trivyignore`. Todo como inputs de la acción, sin `trivy.yaml`.
- **Rationale**: es exactamente lo que piden los criterios; los inputs son visibles en el workflow.
  Se excluyen directorios de artefactos para evitar ruido y tiempo. `ignore-unfixed` aplica solo a
  vulnerabilidades; secretos y misconfig no tienen versión "corregida" y siempre cuentan.
- **Alternativas**: `trivy.yaml` (otro archivo sin necesidad hoy).

## 4. Dos pasadas: reporte SARIF y gate

- **Decisión**: paso 1 `format: sarif`, `output: trivy-results.sarif`, `exit-code: 0`,
  `limit-severities-for-sarif: true`; paso `upload-sarif` con `if: always()`; paso 2 `format: table`,
  `exit-code: 1` como gate.
- **Rationale**: (a) el SARIF se publica siempre, también cuando el gate falla (FR-006); (b) el log
  del gate muestra paquete, versión afectada y corregida (escenario 3 de la historia 1); (c) un fallo
  del paso 1 (base de datos o herramienta no disponible) es un error de infraestructura, distinguible
  del fallo del paso 2 que es el gate (FR-016). Sin `limit-severities-for-sarif` el SARIF ignoraría
  el filtro de severidad. La segunda pasada reutiliza la base de datos ya descargada.
- **Alternativas**: una sola pasada en SARIF con `exit-code: 1` — el log no explica el fallo y no se
  distingue infraestructura de gate.

## 5. Valores de secretos

- **Decisión**: confiar en la redacción de Trivy (muestra regla, archivo y línea con el valor
  enmascarado) y no usar `--debug` ni volcar el SARIF al log.
- **Rationale**: FR-009. Se verifica en el quickstart con un secreto de prueba.

## 6. SARIF y PRs sin permisos de escritura

- **Decisión**: variable `CAN_WRITE` = PR no es de fork **y** actor distinto de `dependabot[bot]`;
  `upload-sarif` solo si `CAN_WRITE == 'true'`. El gate corre siempre. Se sube además el SARIF como
  artefacto (`trivy-report`) cuando no se puede subir a Security.
- **Rationale**: los PRs de forks y los de Dependabot reciben token de solo lectura, y `upload-sarif`
  fallaría falsamente. Los PRs de Dependabot son justo los que más necesitan el gate.
- **Alternativas**: `pull_request_target` — descartado por riesgo de ejecutar código no confiable con
  permisos de escritura.
- **Nota**: el job `qodana` de LAB-75 usa solo la condición de fork; los PRs de Dependabot podrían
  tener el mismo problema. Fuera de alcance aquí; se anotará en el PR.

## 7. Versión y procedencia de la acción

- **Decisión**: fijar `aquasecurity/trivy-action` por SHA de commit completo con el tag en un
  comentario, verificando en la implementación el último release publicado. Dependabot
  (`github-actions`) la mantiene actualizada.
- **Rationale**: es una herramienta de seguridad con acceso al código y al token del job; los tags
  mutables son un vector de cadena de suministro. Las demás acciones del repo usan tags mayores
  (convención de LAB-15); aquí se endurece solo esta.
- **Alternativas**: tag mayor como el resto — menos seguro para esta acción en concreto.

## 8. Dependabot

- **Decisión**: `.github/dependabot.yml` v2 con: `maven` en `/backend`, `npm` en `/frontend`, `pip`
  en `/ingest` y `/eval`, `github-actions` en `/`, `docker` en `/infra` (cuando existan Dockerfiles)
  y `docker-compose` en `/infra` (las imágenes actuales están en los compose). Frecuencia `weekly`,
  `open-pull-requests-limit: 5`, prefijo de commit `chore(deps)`.
- **Rationale**: cubre los cinco ecosistemas pedidos. Dependabot registra un error de configuración
  (no falla el pipeline) si un directorio aún no tiene manifiesto; es aceptable y se resuelve solo al
  aparecer `frontend/package.json`, `ingest/pyproject.toml`, etc. `docker-compose` es un ecosistema
  adicional pequeño que sí tiene contenido hoy (`pgvector`, `ollama`).
- **Riesgo conocido**: el ecosistema `pip` lee `pyproject.toml` pero no actualiza `uv.lock`, que usa
  el CI de LAB-15. Si ingest/eval adoptan `uv`, evaluar el ecosistema `uv`. Trivy sí escanea
  `uv.lock`, así que no hay punto ciego en detección.
- **Alternativas**: agrupar actualizaciones (`groups`) — se difiere hasta que haya volumen.

## 9. Escaneo programado

- **Decisión**: no añadir `schedule`; los hallazgos nuevos sobre dependencias existentes se detectan
  en el siguiente PR o push a `main` (borde de la spec).
- **Rationale**: YAGNI en F0; si el ruido por CVEs nuevos molesta, se añade después.

## 10. Imagen de contenedor

- **Decisión**: fuera de alcance (LAB-78). El `misconfig` de Trivy sí revisa Dockerfiles y manifiestos
  Kubernetes si aparecen; no analiza archivos compose.

## Verificación en la implementación (2026-10-24)

- `aquasecurity/trivy-action`: último release `v0.36.0`, tag anotado resuelto al commit
  `ed142fd0673e97e23eac54620cfb913e5ce36c25` (decisión 7).
- Code Scanning habilitado en el repo (`code-scanning/alerts` responde `[]`); el SARIF se sube a
  Security (decisión 6).
