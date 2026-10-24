# Quickstart: validar el pipeline de CI

## Prerrequisitos

- Workflow publicado en una rama y acceso a la pestaña *Actions* del repositorio.
- JDK 21 local y Docker (para reproducir el job Java).
- Opcional: `actionlint` para validar la sintaxis del workflow antes de subirlo.

## 1. Validación local previa

```text
actionlint .github/workflows/ci.yml
cd backend && ./mvnw -B -ntp clean verify
git ls-files -s backend/mvnw          # debe mostrar modo 100755
```

Resultado esperado: sin errores de sintaxis; `BUILD SUCCESS`; modo `100755`.

## 2. Escenarios en GitHub (un PR de prueba por escenario)

| # | Cambio en el PR | Resultado esperado | Spec |
|---|---|---|---|
| 1 | Editar un archivo de `backend/` | Corre `backend`; `ci-gate` verde; artefacto `jacoco-report` | US1, US2 |
| 2 | Añadir una violación de Checkstyle | `backend` rojo; `ci-gate` rojo | US1.3 |
| 3 | Borrar pruebas hasta bajar de 80 % de cobertura | `backend` rojo por `jacoco:check`; artefacto presente | US2.2, US2.3 |
| 4 | Solo editar `docs/` o `README.md` | Ningún job de componente; `ci-gate` verde | US3.4 |
| 5 | Editar `.github/workflows/ci.yml` | Corren todos los jobs | US3.5 |
| 6 | Editar solo `frontend/` (sin proyecto) | `frontend` verde con aviso | Edge cases |
| 7 | Re-ejecutar sin cambiar dependencias | Los pasos de caché reportan *cache hit* | US1.4 |
| 8 | Empujar un segundo commit al PR | La ejecución anterior se cancela | FR-014 |
| 9 | Merge a `main` con cambios en `backend/` | Corre `backend` sobre `main`; badge verde | US1.5, US6 |

## 3. Badge

Abrir el README en GitHub: el badge muestra el estado de `main` y enlaza a las ejecuciones.

## 4. Protección de rama (manual, fuera del repo)

Exigir únicamente el check `ci-gate` en *Settings > Branches*.
