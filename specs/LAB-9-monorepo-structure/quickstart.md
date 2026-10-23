# Quickstart: validar LAB-9

Guía de validación del monorepo. Se ejecuta desde la raíz de un clon limpio de la rama
`chore/LAB-9-monorepo-structure`.

## Prerrequisitos

- git y un shell POSIX (Git Bash en Windows).

## 1. Estructura (FR-001, FR-002, SC-001)

```bash
for d in frontend backend ingest eval infra docs; do
  git ls-files "$d" | grep -q . && echo "OK $d" || echo "FALTA $d"
done
```

Resultado esperado: seis líneas `OK`.

## 2. README coincide con la estructura (FR-003, SC-003)

```bash
git ls-tree --name-only -d HEAD | grep -v '^\.' | sort > /tmp/real.txt
grep -oE '^\| `[a-z]+/` ' README.md | tr -d '|` /' | sort > /tmp/readme.txt
diff /tmp/real.txt /tmp/readme.txt && echo "README coincide"
```

Resultado esperado: sin diferencias. Las carpetas `specs` y similares de herramientas se ignoran o
documentan si el diff las reporta.

## 3. Arranque documentado (FR-004, FR-005, FR-008)

```bash
grep -n -i -E "arrancar|prerrequisitos|F0-02|LAB-|licencia" README.md
```

Resultado esperado: coincidencias en las secciones de arranque, convenciones y licencia; el
comando `docker compose` aparece marcado como pendiente de F0-02.

## 4. Editor y licencia (FR-006, FR-007, SC-005)

```bash
test -f .editorconfig && grep -q "root = true" .editorconfig && echo "editorconfig OK"
head -3 LICENSE
```

Resultado esperado: `editorconfig OK`; `LICENSE` empieza con `MIT License` y
`Copyright (c) 2026 Leandro Amariles`. Tras el push, GitHub muestra "MIT license" en el repo.

## 5. Exclusiones (FR-009, SC-004)

```bash
git status --short
```

Resultado esperado: sin archivos de `data/raw/`, `.claude/settings.local.json` ni
`specs/*/.token-usage.json`.
