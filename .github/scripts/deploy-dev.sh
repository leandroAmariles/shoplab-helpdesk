#!/usr/bin/env bash
# Fija en el overlay dev de shoplab-infra el tag del SHA publicado y empuja el commit (LAB-81).
# Contrato: specs/LAB-81-continuous-deploy-dev/contracts/deploy-job-contract.md
#
# Entradas (entorno): APP_SHA, RUN_URL, INFRA_DIR (clon de shoplab-infra con `origin` autenticado), REGISTRY_PREFIX,
#   SERVICES (separados por comas); opcionales: OVERLAY, BRANCH, PUSH_RETRIES, PUSH_RETRY_WAIT (segundos base),
#   IMAGE_CHECK_CMD, POLICY_CHECK, DEPLOY_TOKEN (solo para enmascararlo en la salida).
# Salida: 0 pushed | 0 no-change | 10 falta una imagen | 11 servicio no declarado | 12 build o política fallan
#   | 13 no se pudo empujar | 2 entrada inválida. Escribe result= y commit= en $GITHUB_OUTPUT y un resumen.
set -euo pipefail

EXIT_USAGE=2 EXIT_MISSING_IMAGE=10 EXIT_UNDECLARED=11 EXIT_CHECKS=12 EXIT_PUSH=13

log() { printf '%s\n' "$*"; }
die() { local code="$1"; shift; printf 'error: %s\n' "$*" >&2; set_result failed "$*"; exit "$code"; }

# Nunca imprime el token: cualquier salida de git pasa por aquí.
redact() { if [ -n "${DEPLOY_TOKEN:-}" ]; then sed "s|${DEPLOY_TOKEN}|***|g"; else cat; fi; }

set_result() { # resultado, detalle
  [ -z "${GITHUB_OUTPUT:-}" ] || printf 'result=%s\n' "$1" >>"$GITHUB_OUTPUT"
  # shellcheck disable=SC2016 # los acentos graves son Markdown, no expansión
  [ -z "${GITHUB_STEP_SUMMARY:-}" ] || {
    printf '### Despliegue a dev\n- Resultado: `%s`\n- SHA: `%s`\n- Servicios: %s\n' \
      "$1" "${APP_SHA:-?}" "${SERVICES:-?}"
    [ -z "${2:-}" ] || printf '%s\n' "- Detalle: $2"
  } >>"$GITHUB_STEP_SUMMARY"
}

for var in APP_SHA RUN_URL INFRA_DIR REGISTRY_PREFIX SERVICES; do
  [ -n "${!var:-}" ] || die $EXIT_USAGE "falta la variable $var"
done
[[ "$APP_SHA" =~ ^[0-9a-f]{40}$ ]] || die $EXIT_USAGE "APP_SHA debe ser el SHA completo de 40 caracteres hexadecimales"

OVERLAY="${OVERLAY:-k8s/overlays/dev}"
BRANCH="${BRANCH:-main}"
PUSH_RETRIES="${PUSH_RETRIES:-3}"
PUSH_RETRY_WAIT="${PUSH_RETRY_WAIT:-2}"
IMAGE_CHECK_CMD="${IMAGE_CHECK_CMD:-docker buildx imagetools inspect}"
POLICY_CHECK="${POLICY_CHECK:-$INFRA_DIR/scripts/policy-check.sh}"
IFS=',' read -r -a SERVICE_LIST <<<"$SERVICES"
KUSTOMIZATION="$OVERLAY/kustomization.yaml"

export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false
cd "$INFRA_DIR"
[ -f "$KUSTOMIZATION" ] || die $EXIT_USAGE "no existe $KUSTOMIZATION en $INFRA_DIR"
RENDER_DIR="$(mktemp -d)"
trap 'rm -rf "$RENDER_DIR"' EXIT

# 1. Cada servicio publicado debe estar declarado en el overlay: si no, se fallaría en silencio al ignorarlo.
for svc in "${SERVICE_LIST[@]}"; do
  grep -Eq "^[[:space:]]*-[[:space:]]+name:[[:space:]]+${REGISTRY_PREFIX}/${svc}[[:space:]]*$" "$KUSTOMIZATION" \
    || die $EXIT_UNDECLARED "el servicio $svc no figura en images: de $KUSTOMIZATION"
done

# 2. Cada imagen con el tag del SHA debe existir en el registro (FR-006).
for svc in "${SERVICE_LIST[@]}"; do
  # shellcheck disable=SC2086 # IMAGE_CHECK_CMD es un comando con argumentos
  $IMAGE_CHECK_CMD "${REGISTRY_PREFIX}/${svc}:${APP_SHA}" >/dev/null 2>&1 \
    || die $EXIT_MISSING_IMAGE "la imagen ${REGISTRY_PREFIX}/${svc}:${APP_SHA} no existe en el registro"
done

# Edita el overlay, comprueba el render y las políticas. Deja el árbol limpio si algo falla.
apply_and_check() {
  for svc in "${SERVICE_LIST[@]}"; do
    (cd "$OVERLAY" && kustomize edit set image "${REGISTRY_PREFIX}/${svc}=${REGISTRY_PREFIX}/${svc}:${APP_SHA}")
  done
  local rendered="$RENDER_DIR/dev.yaml"
  if ! kustomize build "$OVERLAY" >"$rendered"; then
    git checkout -- "$OVERLAY"; die $EXIT_CHECKS "kustomize build falló en $OVERLAY"
  fi
  for svc in "${SERVICE_LIST[@]}"; do
    if ! grep -Eq "image:[[:space:]]+${REGISTRY_PREFIX}/${svc}:${APP_SHA}[[:space:]]*$" "$rendered"; then
      git checkout -- "$OVERLAY"; die $EXIT_CHECKS "el render de $svc no usa el tag $APP_SHA"
    fi
  done
  if grep -Eq 'image:.*:latest([[:space:]]|$)' "$rendered"; then
    git checkout -- "$OVERLAY"; die $EXIT_CHECKS "el render contiene una imagen :latest"
  fi
  if ! "$POLICY_CHECK" "$rendered" dev; then
    git checkout -- "$OVERLAY"; die $EXIT_CHECKS "policy-check rechazó el render de $OVERLAY"
  fi
}

commit_change() {
  local short="${APP_SHA:0:7}" list="${SERVICES//,/, }"
  git add -- "$KUSTOMIZATION"
  git -c user.name='shoplab-deploy[bot]' -c user.email='shoplab-deploy[bot]@users.noreply.github.com' commit -q \
    -m "chore(dev): [LAB-81] despliega ${short} a dev" \
    -m "Aplicación: leandroAmariles/shoplab-helpdesk@${APP_SHA}
CI: ${RUN_URL}
Servicios: ${list}"
}

apply_and_check
if git diff --quiet -- "$OVERLAY"; then
  log "dev ya está fijado a $APP_SHA; no hay nada que desplegar"
  set_result no-change "el SHA ya estaba fijado"
  exit 0
fi
commit_change

attempt=0
while :; do
  if git push origin "HEAD:${BRANCH}" 2>&1 | redact; [ "${PIPESTATUS[0]}" -eq 0 ]; then
    commit="$(git rev-parse HEAD)"
    log "desplegado $APP_SHA en dev (commit $commit)"
    [ -z "${GITHUB_OUTPUT:-}" ] || printf 'commit=%s\n' "$commit" >>"$GITHUB_OUTPUT"
    set_result pushed "commit \`$commit\` en shoplab-infra"
    exit 0
  fi
  attempt=$((attempt + 1))
  [ "$attempt" -le "$PUSH_RETRIES" ] || die $EXIT_PUSH "no se pudo empujar tras $PUSH_RETRIES reintentos"
  log "push rechazado; reintento $attempt de $PUSH_RETRIES"
  sleep $((PUSH_RETRY_WAIT * (1 << (attempt - 1))))
  # Se descarta el commit propio y se reaplica sobre el remoto actual: evita conflictos en kustomization.yaml.
  git fetch -q origin "$BRANCH" 2>&1 | redact || true
  git reset -q --hard "origin/${BRANCH}"
  apply_and_check
  if git diff --quiet -- "$OVERLAY"; then
    log "otro despliegue ya fijó $APP_SHA"
    set_result no-change "el SHA ya estaba fijado"
    exit 0
  fi
  commit_change
done
