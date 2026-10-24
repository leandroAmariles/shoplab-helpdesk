# Contract: check `pr-title`

## Formato aceptado

`type(scope): [LAB-<n>] descripción` — `scope` opcional.

| Ejemplo | Resultado |
|---|---|
| `feat(chat): [LAB-12] respuesta en streaming` | Verde |
| `fix: [LAB-80] corrige el redondeo` | Verde |
| `arreglos varios` | Rojo: no sigue Conventional Commits |
| `feat(chat): respuesta en streaming` | Rojo: falta la clave `[LAB-<n>]` |
| `feat(chat): LAB-12 respuesta` | Rojo: la clave debe ir entre corchetes |
| `hotfix: [LAB-9] urgente` | Rojo: tipo no permitido (usar `fix`) |
| `chore(deps): Bump x from 1 to 2` (autor `dependabot[bot]`) | Verde: solo se valida el tipo |

## Tipos permitidos

`feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert`.

## Comportamiento

| Aspecto | Contrato |
|---|---|
| Disparadores | `pull_request`: `opened`, `edited`, `synchronize`, `reopened` |
| Reevaluación | Editar el título reevalúa el check sin nuevo commit |
| Permisos | `pull-requests: read`; sin checkout ni código del PR |
| Concurrencia | La ejecución obsoleta del mismo PR se cancela |
| Mensaje de error | Indica qué incumple (tipo o clave) y muestra el formato esperado |
| Nombre del check | `pr-title` (estable; requerido por el ruleset) |
