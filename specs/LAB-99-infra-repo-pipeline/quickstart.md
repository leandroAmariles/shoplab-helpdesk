# Quickstart: validar el pipeline de `shoplab-infra`

Guía para comprobar de extremo a extremo que LAB-99 funciona. Los comandos se ejecutan en el repositorio
`shoplab-infra` (Git Bash en Windows).

## Prerrequisitos

- LAB-98 integrada (Terraform, `docker-compose.yml` de Floci, scripts `up/down/verify-clean`) y overlays
  `dev`, `staging`, `prod` presentes.
- Herramientas con las versiones de `versions.env`: Terraform, TFLint, kustomize, kubeconform, yq, Trivy, make.
- Docker solo para `make plan` y `make smoke`.
- Acceso de administrador al repositorio para aplicar el ruleset.

## 1. Controles estáticos en local

```bash
make check-tools
make check
```

Esperado: termina con código 0 y ejecuta, en orden, formato, validación, lint, pruebas de Terraform, overlays,
políticas, pruebas de políticas y Trivy (SC-006).

## 2. Una violación por regla (SC-002)

En una rama temporal, introducir una a una y ejecutar `make policies`:

| Cambio | Resultado esperado |
|---|---|
| Imagen `...:latest` en un workload | falla con `IMAGE_LATEST` |
| Imagen sin tag | falla con `IMAGE_NO_TAG` |
| `repoURL` con `CAMBIA-ESTE-OWNER` | falla con `TEMPLATE_REPO_URL` |
| `targetRevision: develop` en una `Application` | falla con `TARGET_REVISION_NOT_MAIN` |
| Imagen con `@sha256:` y sin tag | pasa |

Revertir cada cambio. Las mismas violaciones deben fallar el PR en CI con el mismo mensaje.

## 3. Fallos de las validaciones estáticas

1. Desformatear un `.tf` → `make fmt-check` falla nombrando el archivo.
2. Referenciar una variable inexistente → `make validate` falla.
3. Renombrar un overlay → `make kustomize` falla indicando el overlay ausente (FR-008).
4. Romper una aserción en una prueba de módulo → `make tf-test` falla.

## 4. Plan contra Floci

```bash
make plan
```

Esperado: levanta Floci, hace el bootstrap del estado y muestra el plan sin errores; al terminar no deja
contenedores. En un PR con cambios en `terraform/`, aparece un único comentario con el marcador
`<!-- shoplab-infra-tf-plan -->`; un segundo push lo actualiza (SC-005).

## 5. Prueba de humo

```bash
make smoke
```

Esperado: apply, todas las `Application` de Argo CD en `Synced` y `Healthy` dentro del límite, destroy y
`verify-clean` sin recursos remanentes (SC-004). Para el camino de fallo, romper a propósito el `targetRevision`
o el `repoURL` de la `Application` inicial en una rama y comprobar que el paso falla mostrando el estado de las
aplicaciones y que la limpieza se ejecuta igualmente.

## 6. Seguridad (Trivy)

Introducir una mala configuración conocida (por ejemplo un recurso con acceso público no justificado) → el job
`trivy` falla y el hallazgo aparece en Security → Code scanning (`trivy-config`). Añadirlo a `.trivyignore` con
motivo, responsable y fecha → el job pasa.

## 7. Ruleset de `main` (después de que `ci-gate` haya corrido una vez)

```bash
gh api -X POST repos/<owner>/shoplab-infra/rulesets --input .github/rulesets/main-protection.json
git push origin main            # rechazado
git push --force origin main    # rechazado
```

Abrir un PR con un job en rojo → la fusión queda bloqueada; con todo en verde → solo "Squash and merge" con título
válido (SC-001, SC-007).

## 8. PR que no toca infraestructura

Abrir un PR que solo cambie `README.md` → `ci-gate` pasa (los jobs omitidos no bloquean) y el PR se puede fusionar.

## Criterios de salida

- `make check` en verde en un clon limpio.
- Las cuatro violaciones de política fallan en local y en CI con el mensaje esperado.
- Comentario de plan único y actualizable; SARIF visible en Security.
- Prueba de humo en verde y sin recursos remanentes, y el camino de fallo limpia igual.
- Ruleset activo con `ci-gate` y `pr-title` requeridos.
