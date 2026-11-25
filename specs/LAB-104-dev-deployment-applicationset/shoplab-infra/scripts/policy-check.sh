#!/usr/bin/env bash
set -euo pipefail

TARGET_PATH="${1:-.}"

echo "==> Ejecutando comprobación de políticas sobre: ${TARGET_PATH}"

# 1. Prohibición estricta de tags :latest
LATEST_MATCHES=$(grep -rnE "image:[ \t]*[^:]+:latest" "${TARGET_PATH}" || true)
if [ -n "${LATEST_MATCHES}" ]; then
  echo "ERROR: Se detectaron referencias prohibidas al tag :latest:" >&2
  echo "${LATEST_MATCHES}" >&2
  exit 1
fi

# 2. Prohibición de marcadores de plantilla no reemplazados
PLACEHOLDER_MATCHES=$(grep -rn "SERVICE_NAME" "${TARGET_PATH}" | grep -v "apps/_template" || true)
if [ -n "${PLACEHOLDER_MATCHES}" ]; then
  echo "ERROR: Se detectó el marcador sin parametrizar SERVICE_NAME fuera de la plantilla:" >&2
  echo "${PLACEHOLDER_MATCHES}" >&2
  exit 1
fi

echo "==> Políticas verificadas exitosamente. Manifiestos conformes."
