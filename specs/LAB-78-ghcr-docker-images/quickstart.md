# Quickstart: validar imágenes en GHCR

Guía para comprobar la historia de principio a fin. Contratos en [contracts/image-pipeline.md](contracts/image-pipeline.md),
entidades en [data-model.md](data-model.md).

## Prerrequisitos

- Docker con BuildKit, `cosign` y (opcional) `trivy` y `actionlint` instalados localmente.
- Rama `feature/LAB-78-ghcr-docker-images` con los Dockerfiles y `images.yml`.
- Acceso de lectura a `ghcr.io/leandroamariles/*` (`docker login ghcr.io` si los paquetes son privados).

## 1. Construcción local y usuario no root (Historia 1)

```bash
docker build -t shoplab/backend:local backend
docker build -t shoplab/frontend:local frontend
docker build -t shoplab/ingest:local ingest
for s in backend frontend ingest; do docker run --rm --entrypoint id shoplab/$s:local; done
```

Esperado: las tres construyen sin pasos manuales; `id` muestra un uid distinto de `0`.
Arranque del backend: `docker run --rm -p 8080:8080 shoplab/backend:local` y
`curl localhost:8080/actuator/health` devuelve `UP` (arranca sin base de datos según el estado actual
del backend; si el contexto exige variables de entorno, documentarlas aquí al implementar).

## 2. Ensayo del escaneo (Historia 3)

```bash
trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile .trivyignore --exit-code 1 shoplab/backend:local
```

Esperado: exit code 0 con la imagen limpia. Para probar el gate, usar temporalmente una base con un CVE
corregible conocido y comprobar exit code 1 y que en CI no aparece nada en GHCR.

## 3. Pull request (solo construir y escanear)

1. Abrir un PR que toque `backend/**` (o el workflow).
2. Esperado: corren `build-scan (backend|frontend|ingest)`; **no** corre `publish`; GHCR no cambia.

## 4. Merge a `main` (Historia 2, 4 y 5)

1. Fusionar el PR.
2. Esperado en la ejecución de `Images`: `build-scan` verde en los tres servicios, luego `publish` verde y
   un resumen con servicio / tags / digest.
3. Comprobar tags y atestaciones:

```bash
docker buildx imagetools inspect ghcr.io/leandroamariles/backend:latest
docker buildx imagetools inspect ghcr.io/leandroamariles/backend:<sha-completo>
docker buildx imagetools inspect ghcr.io/leandroamariles/backend:latest --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/leandroamariles/backend:latest --format '{{ json .Provenance }}'
```

   Esperado: `latest` y `<sha-completo>` tienen el mismo digest; SBOM y provenance presentes y con el
   commit fusionado.
4. Verificar la firma:

```bash
cosign verify ghcr.io/leandroamariles/backend:latest \
  --certificate-identity-regexp '^https://github.com/leandroamariles/shoplab-helpdesk/\.github/workflows/images\.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

   Esperado: verificación correcta. Repetir con `frontend` e `ingest`.

## 5. Release (`vX.Y.Z`)

1. Fusionar el PR de release de release-please (LAB-100).
2. Esperado: el tag `vX.Y.Z` dispara `Images`; las tres imágenes quedan con el tag `vX.Y.Z`; `latest` no
   cambia de digest.
3. Reejecutar la ejecución del tag: el job `publish` falla informando que `vX.Y.Z` ya existe y no
   sobrescribe.

## 6. Gate de vulnerabilidades en CI (Historia 3)

1. En un PR de prueba, cambiar la imagen base del `backend` a una versión con un CVE HIGH/CRITICAL
   corregible conocido.
2. Esperado: `build-scan (backend)` falla con la tabla de hallazgos; `publish` no corre (en un PR nunca
   corre; en `main` tampoco publica ninguna de las tres imágenes).
3. Añadir el id a `.trivyignore` con motivo y responsable: el check pasa; quitarlo: vuelve a fallar.

## 7. Permisos y secretos

- `images.yml` no referencia secretos distintos de `GITHUB_TOKEN`; `packages: write` e `id-token: write`
  solo en `publish`.
- El repositorio no tiene claves de firma (Settings → Secrets) para este flujo.

## Resultados de validación (2026-10-24)

- **PR #36** (build + scan): los tres `build-scan` en verde, `publish` omitido, GHCR sin cambios.
- **Merge a `main` (`489754e`)**: ejecución de `Images` en verde; las tres imágenes con `latest` y el SHA
  completo apuntando al mismo digest; SBOM (SPDX) y provenance (SLSA) presentes; `cosign verify` correcto
  con la identidad de `images.yml` y el emisor de GitHub Actions, y fallido con otra identidad. Los
  paquetes son legibles sin autenticación.
- **Prueba negativa (PR #43, cerrado sin fusionar)**: con `python:3.8-slim` como base de `ingest`,
  `build-scan (ingest)` falló con 62 hallazgos corregibles (55 HIGH, 7 CRITICAL), los otros dos servicios
  pasaron y `publish` no corrió. La rama de excepciones no se ejercitó: con 62 hallazgos una entrada en
  `.trivyignore` no basta para pasar; el mecanismo de excepciones es el de LAB-76.
- **Pendiente**: flujo de tag `vX.Y.Z` (quickstart §5) con el próximo PR de release de release-please.
