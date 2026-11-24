#!/usr/bin/env bash
# Despliegue GitOps reutilizable: actualiza la imagen en el overlay dev de shoplab-infra (LAB-103)
# Entradas (variables de entorno obligatorias):
#   SERVICE_NAME    : Nombre del servicio tal como figura en kustomization.yaml (ej: chat-service)
#   IMAGE_TAG       : Tag de versión recién publicado (ej: v1.0.0)
#   INFRA_DIR       : Ruta local del clon de shoplab-infra
#   IMAGE_NAMESPACE : Prefijo del registro (ej: ghcr.io/leandroamariles)
#   RUN_URL         : URL del run de CI que ejecuta el despliegue
#   APP_SHA         : SHA del commit que generó la release
set -euo pipefail

EXIT_USAGE=2
EXIT_MISSING_IMAGE=10
EXIT_UNDECLARED=11
EXIT_CHECKS=12
EXIT_PUSH=13

log() { printf '%s\n' "$*"; }
die() { local code="$1"; shift; printf 'error: %s\n' "$*" >&2; exit "$code"; }

for var in SERVICE_NAME IMAGE_TAG INFRA_DIR IMAGE_NAMESPACE RUN_URL APP_SHA; do
  [ -n "${!var:-}" ] || die $EXIT_USAGE "Falta la variable de entorno obligatoria: $var"
done

[[ "$IMAGE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die $EXIT_USAGE "IMAGE_TAG debe ser un tag semántico vX.Y.Z, recibido: $IMAGE_TAG"

OVERLAY="${OVERLAY:-k8s/overlays/dev}"
FULL_IMAGE="${IMAGE_NAMESPACE}/${SERVICE_NAME}"
KUSTOMIZATION="${INFRA_DIR}/${OVERLAY}/kustomization.yaml"
POLICY_CHECK="${INFRA_DIR}/scripts/policy-check.sh"

log "==> Iniciando despliegue de $FULL_IMAGE:$IMAGE_TAG en $OVERLAY..."

[ -f "$KUSTOMIZATION" ] || die $EXIT_USAGE "No existe el archivo $KUSTOMIZATION"

# 1. Verificar que el servicio esté declarado en kustomization.yaml
if ! grep -Eq "^[[:space:]]*(-[[:space:]]+)?name:[[:space:]]+${FULL_IMAGE}[[:space:]]*$" "$KUSTOMIZATION"; then
  die $EXIT_UNDECLARED "El servicio ${FULL_IMAGE} no figura declarado en la sección images: de $KUSTOMIZATION"
fi

# 2. Verificar que la imagen exista en el registro GHCR
if ! docker buildx imagetools inspect "${FULL_IMAGE}:${IMAGE_TAG}" >/dev/null 2>&1; then
  die $EXIT_MISSING_IMAGE "La imagen ${FULL_IMAGE}:${IMAGE_TAG} no existe en el registro GHCR"
fi

# 3. Actualizar la imagen con Kustomize
(cd "${INFRA_DIR}/${OVERLAY}" && kustomize edit set image "${FULL_IMAGE}=${FULL_IMAGE}:${IMAGE_TAG}")

# 4. Validar el render de Kustomize y políticas
RENDER_OUT="$(mktemp)"
trap 'rm -f "$RENDER_OUT"' EXIT

if ! kustomize build "${INFRA_DIR}/${OVERLAY}" >"$RENDER_OUT"; then
  (cd "$INFRA_DIR" && git checkout -- "$OVERLAY")
  die $EXIT_CHECKS "Fallo al ejecutar kustomize build sobre $OVERLAY"
fi

if grep -Eq 'image:.*:latest([[:space:]]|$)' "$RENDER_OUT"; then
  (cd "$INFRA_DIR" && git checkout -- "$OVERLAY")
  die $EXIT_CHECKS "El manifiesto generado contiene una referencia prohibida a tag :latest"
fi

if [ -f "$POLICY_CHECK" ]; then
  if ! bash "$POLICY_CHECK" "$RENDER_OUT" dev; then
    (cd "$INFRA_DIR" && git checkout -- "$OVERLAY")
    die $EXIT_CHECKS "policy-check.sh rechazó el manifiesto renderizado para dev"
  fi
fi

# 5. Commit y Push con reintentos
cd "$INFRA_DIR"
if git diff --quiet -- "$KUSTOMIZATION"; then
  log "==> No se detectaron cambios en $KUSTOMIZATION (idempotente)."
  exit 0
fi

git add -- "$KUSTOMIZATION"
git -c user.name='shoplab-deploy[bot]' -c user.email='shoplab-deploy[bot]@users.noreply.github.com' commit -q \
  -m "chore(dev): [LAB-103] despliega ${SERVICE_NAME} a dev (${IMAGE_TAG})" \
  -m "Versión: ${IMAGE_TAG}
Servicio: ${SERVICE_NAME}
Imagen: ${FULL_IMAGE}:${IMAGE_TAG}
Commit origen: ${APP_SHA}
CI Run: ${RUN_URL}"

PUSHED=0
for i in {1..3}; do
  if git push origin main; then
    PUSHED=1
    break
  fi
  log "Colisión al hacer push, reintentando ($i/3) con pull --rebase..."
  git pull --rebase origin main
  sleep 2
done

[ $PUSHED -eq 1 ] || die $EXIT_PUSH "No se pudo realizar push a shoplab-infra tras agotar reintentos"

log "==> Despliegue de ${SERVICE_NAME}:${IMAGE_TAG} completado y publicado exitosamente en shoplab-infra."
exit 0
