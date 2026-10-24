# Contract: check de Qodana y sus resultados

Contrato observable por desarrolladores y por la protección de rama de GitHub. Extiende
[`ci-checks.md` de LAB-15](../../LAB-15-ci-github-actions/contracts/ci-checks.md).

## Check publicado

| Check (nombre del job) | Exigir en protección de rama | Se ejecuta cuando |
|---|---|---|
| `qodana` | No (lo cubre `ci-gate`) | Cambia `backend/**` (incluye `backend/qodana.yaml`) o el workflow |
| `ci-gate` | **Sí (sigue siendo el único requerido)** | Siempre; ahora también agrega el resultado de `qodana` |

## Semántica del job `qodana`

| Condición | Resultado |
|---|---|
| Sin problemas nuevos de severidad alta/crítica respecto al baseline | Verde |
| Problemas nuevos solo de severidad moderada o inferior | Verde, reportados como informativos |
| Al menos un problema nuevo de severidad alta o crítica | Rojo |
| Problemas heredados presentes en el baseline | No afectan |
| Error de herramienta o servicio (sin resultados) | Rojo, con error de paso explícito |
| PR sin cambios en backend ni en el workflow | Omitido (`skipped`), válido para `ci-gate` |

## Salidas

| Salida | Dónde se ve | Notas |
|---|---|---|
| Resultado SARIF | Pestaña Security > Code scanning | requiere Code Scanning habilitado; se omite en forks |
| Comentario resumen | Conversación del PR | se actualiza en cada ejecución; se omite en forks |
| Anotaciones | Pestaña "Files changed" | se omiten en forks |
| Artefacto `qodana-report` | Resumen de la ejecución | siempre disponible |

## Configuración versionada

| Archivo | Propósito |
|---|---|
| `backend/qodana.yaml` | linter, perfil, exclusiones y umbrales del gate |
| `backend/qodana.sarif.json` | baseline de problemas heredados |

## Secretos

| Secreto | Obligatorio | Uso |
|---|---|---|
| `QODANA_TOKEN` | No | Solo si se adopta Qodana Cloud o Community lo exigiera; nunca en código ni en logs |

## Comando de verificación local equivalente

```text
qodana scan --project-dir backend --baseline qodana.sarif.json
```
