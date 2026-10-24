# Contract: checks y artefactos del pipeline

Contrato observable por desarrolladores y por la protección de rama de GitHub.

## Disparadores

| Evento | Condición |
|---|---|
| `pull_request` | Cualquier PR (los filtros por ruta se aplican dentro del workflow) |
| `push` | Solo rama `main` |

## Checks publicados

| Check (nombre del job) | Exigir en protección de rama | Se ejecuta cuando |
|---|---|---|
| `ci-gate` | **Sí (único requerido)** | Siempre |
| `changes` | No | Siempre |
| `backend` | No (lo cubre `ci-gate`) | Cambia `backend/**` o el workflow |
| `frontend` | No | Cambia `frontend/**` o el workflow |
| `python (ingest)` | No | Cambia `ingest/**` o el workflow |
| `python (eval)` | No | Cambia `eval/**` o el workflow |

## Semántica de `ci-gate`

- Verde: todos los jobs de componente terminaron en `success` o `skipped`.
- Rojo: algún job terminó en `failure` o `cancelled`.

## Salida del job `backend`

| Condición | Resultado |
|---|---|
| Checkstyle, PMD, Modulith, pruebas y cobertura >= 80 % OK | Verde |
| Cualquier violación, prueba fallida o cobertura < 80 % | Rojo |
| Cualquiera de los anteriores | Artefacto `jacoco-report` descargable |

## Comando de verificación local equivalente

```text
cd backend && ./mvnw -B -ntp clean verify
```
