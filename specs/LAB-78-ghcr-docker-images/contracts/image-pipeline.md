# Contract: pipeline de imágenes y artefactos publicados

Contrato observable por desarrolladores, por la protección de rama y por el clúster (LAB-83). Extiende
[`ci-checks.md` de LAB-15](../../LAB-15-ci-github-actions/contracts/ci-checks.md) y
[`trivy-check.md` de LAB-76](../../LAB-76-trivy-security-scan/contracts/trivy-check.md).

## Workflow `Images` (`.github/workflows/images.yml`)

| Evento | `build-scan` | `publish` | Tags publicados |
|---|---|---|---|
| `pull_request` (rutas de servicios) | Sí | No | Ninguno |
| `push` a `main` | Sí | Sí | `<sha completo>`, `latest` |
| `push` de tag `vX.Y.Z` | Sí | Sí | `vX.Y.Z` (nunca `latest`) |
| `push` a otras ramas | No corre | No corre | Ninguno |

Checks: `build-scan (backend|frontend|ingest)` y `publish (backend|frontend|ingest)`. **No** se exigen en
la protección de rama (el único requerido sigue siendo `ci-gate`).

## Semántica de `build-scan`

| Condición | Resultado |
|---|---|
| Imagen sin HIGH/CRITICAL corregibles | Verde |
| HIGH/CRITICAL sin corrección publicada, o LOW/MEDIUM | Verde; visibles en el log |
| HIGH/CRITICAL con corrección disponible | Rojo; la tabla muestra paquete, versión y versión corregida |
| Id presente en `.trivyignore` | No afecta |
| Error de construcción | Rojo; no se publica nada |

`publish` solo arranca si las tres instancias de `build-scan` terminaron en verde.

## Imágenes publicadas

| Servicio | Referencia | Puerto | Usuario |
|---|---|---|---|
| backend | `ghcr.io/leandroamariles/backend` | 8080 | no root |
| frontend | `ghcr.io/leandroamariles/frontend` | 8080 | no root |
| ingest | `ghcr.io/leandroamariles/ingest` | 8000 | no root |

Cada referencia incluye: manifiesto `linux/amd64`, atestación SBOM, atestación de provenance y firma
cosign sobre el digest. Etiquetas OCI: `org.opencontainers.image.source`, `.revision`, `.version`.

## Verificación de la firma (consumidores, p. ej. Kyverno en LAB-83)

```text
cosign verify ghcr.io/leandroamariles/<servicio>@sha256:<digest> \
  --certificate-identity-regexp '^https://github.com/<owner>/<repo>/\.github/workflows/images\.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

La identidad es la del workflow `images.yml` de este repositorio; el emisor es el de GitHub Actions. Quien
verifique debe fijar ambos y no aceptar firmas de otro workflow.

## Resumen de ejecución

El job `publish` escribe en el job summary una tabla con servicio, tags y digest de cada imagen publicada.

## Supuestos del contrato

- Los commits de `main` y los tags `v*` provienen de PRs que pasaron `ci-gate` (LAB-77/LAB-15).
- Los tags `vX.Y.Z` los crea release-please con un token que dispara workflows (LAB-100).
- Un tag `vX.Y.Z` ya publicado no se sobrescribe: el job `publish` falla informando el conflicto.
