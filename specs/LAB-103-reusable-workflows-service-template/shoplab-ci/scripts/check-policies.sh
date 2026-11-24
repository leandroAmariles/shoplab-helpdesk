#!/usr/bin/env bash
# Script de validación de políticas en CI (anti :latest y anti CAMBIA-ESTE-OWNER)
# Uso: ./check-policies.sh [directorio]
set -euo pipefail

TARGET_DIR="${1:-.}"
echo "==> Ejecutando comprobación de políticas en: $TARGET_DIR"

FAILED=0

# 1. Comprobar presencia de texto prohibido CAMBIA-ESTE-OWNER
if grep -rn --exclude-dir={.git,target,node_modules,.venv,__pycache__} "CAMBIA-ESTE-OWNER" "$TARGET_DIR"; then
  echo "::error::Se detectó el marcador prohibido 'CAMBIA-ESTE-OWNER' en el repositorio."
  FAILED=1
fi

# 2. Comprobar uso de tags :latest en manifiestos YAML / Dockerfiles / Scripts
# Se excluyen comentarios y scripts de verificación de políticas mismos
if grep -rn --exclude-dir={.git,target,node_modules,.venv} \
  --include="*.yaml" --include="*.yml" --include="Dockerfile*" \
  -E "image:[[:space:]]*[^:]+:latest([[:space:]]|$)" "$TARGET_DIR" 2>/dev/null; then
  echo "::error::Se detectaron referencias a imágenes con tag ':latest' en manifiestos."
  FAILED=1
fi

if [ $FAILED -ne 0 ]; then
  echo "==> Fallo en la verificación de políticas de seguridad."
  exit 1
fi

echo "==> Todas las políticas de seguridad pasaron satisfactoriamente."
exit 0
