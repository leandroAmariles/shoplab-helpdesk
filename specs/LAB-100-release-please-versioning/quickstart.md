# Quickstart: validar el versionado automático

## Prerrequisitos

- Esta rama fusionada en `main` (LAB-77 ya activo: PR obligatorio y checks `ci-gate` y `pr-title`).
- Secreto `RELEASE_PLEASE_TOKEN` creado (token de grano fino, solo este repo, `Contents` y
  `Pull requests` en lectura/escritura) y "Allow GitHub Actions to create and approve pull requests"
  habilitado en *Settings → Actions → General*.
- `gh` autenticado. Opcional: `actionlint`.

## 0. Ensayo previo a la fusión

```bash
actionlint .github/workflows/release-please.yml
python -c "import json; [json.load(open(f)) for f in ('release-please-config.json', '.release-please-manifest.json')]"
```

Esperado: sin errores. Abrir el PR de LAB-100 con título válido
(`chore(ci): [LAB-100] automatiza versiones con release-please`) y comprobar `ci-gate` y `pr-title`
en verde.

## 1. Primer PR de release

Fusionar el PR de LAB-100 (squash). Esperado en < 5 min:

```bash
gh run list --workflow release-please.yml --limit 1
gh pr list --head release-please--branches--main --json number,title,labels
```

- Existe un único PR `chore(main): [LAB-100] release 0.1.0` con etiqueta `autorelease: pending`.
- Su diff cambia `CHANGELOG.md`, `.release-please-manifest.json` (`0.1.0`) y `backend/pom.xml`
  (versión `0.1.0`, `<parent>` intacto).
- `ci-gate` y `pr-title` corren sobre él y quedan en verde.

## 2. Reglas de versión

Con `release-as` retirado (tras el paso 3) y desde `0.1.0`:

| Fusionar a `main` | Esperado en el PR de release |
|---|---|
| `fix: [LAB-xxx] ...` | `0.1.1` |
| `feat: [LAB-xxx] ...` | `0.2.0` |
| `feat!: [LAB-xxx] ...` | `0.2.0` (por `0.x`) |
| `docs`/`chore`/`test` solos | no se abre PR |

Un segundo cambio con el PR abierto actualiza el mismo PR (no abre otro).

## 3. Publicar `v0.1.0`

Fusionar el PR de release (squash). Esperado:

```bash
git fetch --tags && git tag --list 'v*'
gh release view v0.1.0 --json tagName,isDraft,body
```

- Tag `v0.1.0` y GitHub Release no borrador con las notas del `CHANGELOG.md`.
- Si LAB-78 ya existe, su workflow arranca con ese tag.
- Re-ejecutar el workflow (`gh run rerun`) no crea tag ni release adicionales.

Después, abrir un PR `chore(ci): [LAB-100] retira release-as` que quite `release-as` de la
configuración, y comprobar que un `fix` posterior propone `0.1.1`.

## 4. Documentación

- Seguir solo el README: redactar un título que genere una entrada de changelog.
- Comparar `README.md`, `CLAUDE.md` y `.ai/rules/development-guidelines.md`: misma tabla de tipos y
  mismo ejemplo `feat(chat): [LAB-12] ...`, sin contradicciones.

## 5. Verificación pendiente de research §1.1

En una rama de prueba, añadir `frontend/package.json` y `ingest/pyproject.toml` a `extra-files`
sin crear los archivos y observar si release-please falla. Anotar el resultado en
[research.md](research.md) y ajustar la configuración.
