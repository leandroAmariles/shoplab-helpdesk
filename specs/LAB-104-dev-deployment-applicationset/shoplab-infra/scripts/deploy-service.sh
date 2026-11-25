#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="${1:-}"
IMAGE_TAG="${2:-}"
REGISTRY="${3:-ghcr.io/leandroamariles}"
MAX_RETRIES=3

if [ -z "${SERVICE_NAME}" ] || [ -z "${IMAGE_TAG}" ]; then
  echo "Uso: $0 <servicio> <vX.Y.Z> [registry]" >&2
  exit 1
fi

TARGET_DIR="apps/${SERVICE_NAME}/overlays/dev"

echo "==> [LAB-104] Despliegue de ${SERVICE_NAME}:${IMAGE_TAG} a dev"

if [ ! -d "${TARGET_DIR}" ]; then
  echo "ERROR: Directorio ${TARGET_DIR} no encontrado en shoplab-infra." >&2
  exit 1
fi

# Actualizar el tag de imagen con kustomize
(cd "${TARGET_DIR}" && kustomize edit set image "${SERVICE_NAME}=${REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}")

# Validación sintáctica y de políticas
kustomize build "${TARGET_DIR}" > /dev/null
if [ -f "./scripts/policy-check.sh" ]; then
  ./scripts/policy-check.sh "${TARGET_DIR}"
fi

# Preparar commit
git add "${TARGET_DIR}/kustomization.yaml"
COMMIT_MSG="chore(dev): [LAB-104] despliega ${SERVICE_NAME} ${IMAGE_TAG} a dev"

if git diff --staged --quiet; then
  echo "==> La versión ${IMAGE_TAG} ya está fijada en ${SERVICE_NAME}. Nada que commitear."
  exit 0
fi

git commit -m "${COMMIT_MSG}"

# Push concurrente seguro con rebase y reintento
RETRY=0
while [ ${RETRY} -lt ${MAX_RETRIES} ]; do
  if git push origin main; then
    echo "==> Despliegue de ${SERVICE_NAME} completado exitosamente en origin/main."
    exit 0
  fi

  RETRY=$((RETRY + 1))
  echo "AVISO: Push rechazado por colisión concurrente. Reintentando (${RETRY}/${MAX_RETRIES}) tras rebase..."
  
  # Backoff con jitter aleatorio entre 2 y 5 segundos
  SLEEP_TIME=$(( (RANDOM % 4) + 2 ))
  sleep "${SLEEP_TIME}"

  git pull --rebase origin main
done

echo "ERROR: No se pudo completar el push tras ${MAX_RETRIES} reintentos." >&2
exit 1
