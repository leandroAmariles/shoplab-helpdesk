# Contrato de Despliegue con Concurrencia y Rebase (LAB-104)

Este documento especifica el contrato de ejecución del job reutilizable de despliegue continuo hacia `shoplab-infra` para soportar múltiples microservicios concurrentes sin pérdida de commits.

---

## 1. Precondiciones de Ejecución

1. La imagen de contenedor del servicio ha sido publicada y firmada exitosamente en GHCR con un tag semántico inmutable `vX.Y.Z` (LAB-103).
2. El runner dispone del token efímero de la GitHub App `shoplab-deploy` (`contents: write` exclusivamente sobre `shoplab-infra`).
3. El directorio `apps/<servicio>/overlays/dev` existe en la rama `main` de `shoplab-infra`.

---

## 2. Algoritmo de Despliegue con Rebase y Reintento

El script ejecutado por el job de despliegue DEBE implementar el siguiente flujo determinista:

```bash
#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="$1"
IMAGE_TAG="$2"
REGISTRY="${3:-ghcr.io/leandroamariles}"
MAX_RETRIES=3

TARGET_DIR="apps/${SERVICE_NAME}/overlays/dev"

echo "==> Iniciando despliegue de ${SERVICE_NAME} versión ${IMAGE_TAG} a dev"

if [ ! -d "${TARGET_DIR}" ]; then
  echo "ERROR: Directorio ${TARGET_DIR} no encontrado en shoplab-infra." >&2
  exit 1
fi

# Aplicar la nueva versión con Kustomize
cd "${TARGET_DIR}"
kustomize edit set image "${SERVICE_NAME}=${REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}"
cd - > /dev/null

# Validación previa con Kustomize build y policy-check
kustomize build "${TARGET_DIR}" > /dev/null
if [ -f "./scripts/policy-check.sh" ]; then
  ./scripts/policy-check.sh "${TARGET_DIR}"
fi

# Commit local
git add "${TARGET_DIR}/kustomization.yaml"
COMMIT_MSG="chore(dev): [LAB-104] despliega ${SERVICE_NAME} ${IMAGE_TAG} a dev"

# Si no hay cambios, terminar limpiamente (versión ya fijada)
if git diff --staged --quiet; then
  echo "==> La versión ${IMAGE_TAG} ya está fijada en ${SERVICE_NAME}. Nada que desplegar."
  exit 0
fi

git commit -m "${COMMIT_MSG}"

# Bucle de push con rebase ante colisiones concurrentes
RETRY_COUNT=0
while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
  if git push origin main; then
    echo "==> Despliegue de ${SERVICE_NAME} ${IMAGE_TAG} publicado con éxito."
    exit 0
  fi

  RETRY_COUNT=$((RETRY_COUNT + 1))
  echo "AVISO: Push rechazado por colisión concurrente. Reintento ${RETRY_COUNT}/${MAX_RETRIES} tras rebase..."
  
  # Espera aleatoria con backoff para evitar resonancia (jitter de 2 a 5 segundos)
  SLEEP_TIME=$(( (RANDOM % 4) + 2 ))
  sleep ${SLEEP_TIME}

  # Rebase sobre el nuevo HEAD remoto
  git pull --rebase origin main
done

echo "ERROR: No se pudo completar el push a shoplab-infra tras ${MAX_RETRIES} reintentos." >&2
exit 1
```

---

## 3. Garantías del Contrato

* **Atomicidad**: Cada commit toca exclusivamente `apps/<servicio>/overlays/dev/kustomization.yaml`.
* **No-interferencia**: Debido a que dos servicios nunca editan el mismo archivo, `git pull --rebase origin main` siempre reordena los commits limpiamente sin generar conflictos de fusión.
* **Trazabilidad**: Todo despliegue deja registro del autor `shoplab-deploy[bot]` y del tag semántico.
