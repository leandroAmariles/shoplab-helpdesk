# Contract: check de Trivy y sus resultados

Contrato observable por desarrolladores y por la protección de rama de GitHub. Extiende
[`ci-checks.md` de LAB-15](../../LAB-15-ci-github-actions/contracts/ci-checks.md).

## Check publicado

| Check (nombre del job) | Exigir en protección de rama | Se ejecuta cuando |
|---|---|---|
| `trivy` | No (lo cubre `ci-gate`) | Siempre: todo PR y todo push a `main` |
| `ci-gate` | **Sí (sigue siendo el único requerido)** | Siempre; ahora también agrega el resultado de `trivy` |

## Semántica del job `trivy`

| Condición | Resultado |
|---|---|
| Sin hallazgos HIGH/CRITICAL (o solo vulnerabilidades sin corrección publicada) | Verde |
| Hallazgos de severidad LOW/MEDIUM | Verde, no bloquean |
| Vulnerabilidad HIGH/CRITICAL con corrección disponible | Rojo, el paso "Trivy gate" lista paquete, versión y versión corregida |
| Secreto o misconfig HIGH/CRITICAL | Rojo, el log indica archivo y regla; el valor va enmascarado |
| Hallazgo listado en `.trivyignore` | No afecta |
| Error de herramienta o descarga de la base de datos | Rojo en el paso "Trivy report" (anterior al gate) |

## Salidas

| Salida | Dónde se ve | Notas |
|---|---|---|
| Resultado SARIF | Pestaña Security > Code scanning (categoría `trivy`) | requiere Code Scanning; se omite en forks y PRs de Dependabot |
| Log del gate (tabla) | Paso "Trivy gate" del job | siempre disponible |
| Artefacto `trivy-report` | Resumen de la ejecución | SARIF completo; siempre disponible |

## Configuración versionada

| Archivo | Propósito |
|---|---|
| `.trivyignore` | excepciones justificadas, un id por línea precedido de comentario |
| `.github/dependabot.yml` | ecosistemas, directorios, frecuencia y límite de PRs |

## Permisos del job

`contents: read`, `security-events: write`. Sin secretos.

## Convención de excepciones

```text
# Motivo: falso positivo, la clase afectada no se usa (solo test scope)
# Responsable: @equipo-backend | Revisar antes de: 2027-01-31
CVE-0000-00000
```
