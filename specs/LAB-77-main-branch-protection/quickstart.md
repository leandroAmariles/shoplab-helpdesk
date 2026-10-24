# Quickstart: validar la protección de main

## Prerrequisitos

- Esta rama fusionada en `main` (el check `pr-title` debe existir allí), junto con LAB-75 y LAB-76.
- `gh` autenticado con permisos de administrador sobre el repositorio.
- Opcional: `actionlint` para validar el workflow en local.

## 0. Ensayo previo a la fusión

```bash
actionlint .github/workflows/pr-title.yml
python -c "import json,sys; json.load(open('.github/rulesets/main-protection.json'))"
```

Esperado: sin errores. Abrir el PR de LAB-77 con título válido
(`chore(ci): [LAB-77] protege main con checks requeridos`) y comprobar que `pr-title` y `ci-gate`
quedan en verde. Aún no hay protección activa.

## 1. Activar (tras fusionar)

```bash
OWNER_REPO=leandroAmariles/shoplab-helpdesk
ID=$(gh api repos/$OWNER_REPO/rulesets --jq '.[] | select(.name=="main-protect") | .id')
gh api -X PUT repos/$OWNER_REPO/rulesets/$ID --input .github/rulesets/main-protection.json
gh api -X PATCH repos/$OWNER_REPO \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true -f squash_merge_commit_title=PR_TITLE \
  -f squash_merge_commit_message=BLANK
```

El ruleset `main-protect` ya existe (deletion, non_fast_forward y `ci-gate` estricto); el `PUT` lo
completa con PR obligatorio, solo squash, historial lineal y `pr-title`. Si no existiera, usar
`POST repos/$OWNER_REPO/rulesets`.

## 2. Sin push directo ni force push (historia 1)

```bash
git checkout main && git commit --allow-empty -m "test" && git push origin main
git push --force origin main
```

Esperado: ambos rechazados. Descartar el commit local de prueba. Probar también con el propietario
(sin bypass).

## 3. Checks requeridos (historia 2)

1. PR con una prueba rota o cobertura bajo el umbral → `ci-gate` rojo y botón de fusión bloqueado.
2. Mientras los checks corren o no se han reportado → bloqueado.
3. PR que solo cambia `README.md` → `ci-gate` verde (jobs omitidos) y fusión habilitada.

## 4. Título del PR (historia 3)

1. Título `arreglos varios` → `pr-title` rojo con el formato esperado en el mensaje.
2. Editar a `feat(chat): [LAB-12] respuesta en streaming` → verde sin nuevo commit en < 2 min.
3. `feat(chat): respuesta` (sin clave) → rojo; `hotfix: [LAB-9] x` → rojo.
4. PR de Dependabot (`chore(deps): ...`) → verde sin clave LAB.

## 5. Rama al día y squash (historia 4)

1. Abrir dos PRs; fusionar el primero.
2. El segundo exige "Update branch" y volver a pasar los checks.
3. El menú de fusión solo ofrece "Squash and merge".
4. Tras fusionar, `git log --oneline main -3` muestra un commit por PR con el título del PR y sin
   merge commits.

## 6. Documentación y auditoría (historia 5)

1. Seguir el README para abrir y fusionar un PR válido sin ayuda externa.
2. Comparar el ruleset activo con el versionado:

```bash
gh api repos/$OWNER_REPO/rulesets/<id> \
  | jq '{name,target,enforcement,bypass_actors,conditions,rules}' \
  | diff - <(jq '{name,target,enforcement,bypass_actors,conditions,rules}' \
      .github/rulesets/main-protection.json)
```

Esperado: sin diferencias.
