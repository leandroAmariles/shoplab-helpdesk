# Data Model: Imágenes Docker en GHCR

No hay persistencia propia: las entidades son artefactos del registro y de la ejecución del workflow.

## Imagen de servicio

| Campo | Descripción |
|---|---|
| servicio | `backend`, `frontend` o `ingest` |
| repositorio | `ghcr.io/leandroamariles/<servicio>` |
| digest | `sha256:...` del índice de manifiestos publicado; identidad real de la imagen |
| tags | `<sha completo>`, `latest`, `vX.Y.Z` (según el evento) |
| plataforma | `linux/amd64` |
| usuario | no root (uid 10001; 101 en el nginx del frontend) |

## Tag

| Tag | Evento que lo publica | Mutable | Notas |
|---|---|---|---|
| `<sha completo>` | push a `main` | No (por commit) | Reejecutar el mismo commit lo repite |
| `latest` | push a `main` | Sí | Siempre la última publicación con éxito de `main` |
| `vX.Y.Z` | push del tag de release-please | **No** | Si ya existe, el job falla sin sobrescribir |

## Ejecución del workflow

| Campo | Descripción |
|---|---|
| evento | `pull_request`, `push` a `main` o `push` de tag |
| commit | SHA construido |
| resultado de escaneo | por servicio: pasa / falla (hallazgos HIGH/CRITICAL corregibles) |
| publicación | solo si los 3 escaneos pasan y el evento no es `pull_request` |
| resumen | tabla servicio / tags / digest en el job summary (FR-017) |

Transiciones: `construida (sin publicar) → escaneada → (los 3 pasan) → publicada con SBOM y provenance →
firmada → firma verificada`. Cualquier fallo antes de "publicada" no deja nada en el registro; un fallo de
firma tras publicar hace fallar la ejecución (la imagen existe pero la ejecución queda en rojo).

## Atestaciones asociadas al digest

| Artefacto | Origen | Contenido |
|---|---|---|
| SBOM | `sbom: true` (BuildKit) | Paquetes del SO y de la aplicación |
| Provenance | `provenance: mode=max` | Repositorio, commit, workflow y argumentos de build |
| Firma cosign | `cosign sign` keyless | Identidad del workflow + emisor OIDC en el log de transparencia |

Regla de publicación: publica ⇔ evento ∈ {push a `main`, push de tag `v*`} ∧ los 3 escaneos pasaron ∧ (si
es tag) el tag no existe en el registro.
