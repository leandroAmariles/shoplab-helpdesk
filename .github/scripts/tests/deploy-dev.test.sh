#!/usr/bin/env bash
# Pruebas de deploy-dev.sh (LAB-81) contra un remoto git local; no usan red ni credenciales.
# Uso: bash .github/scripts/tests/deploy-dev.test.sh
# shellcheck disable=SC2016,SC2015 # los bash -c con comillas simples y el A && B || C son intencionados
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../deploy-dev.sh"
FIXTURES="$HERE/fixtures"
command -v git >/dev/null && command -v kustomize >/dev/null || { echo "faltan git o kustomize" >&2; exit 2; }

export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

VER_A="v0.4.0"
VER_B="v0.4.1"
APP_SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" # commit del tag: solo trazabilidad
PREFIX="ghcr.io/leandroamariles"
OVERLAY_FILE="k8s/overlays/dev/kustomization.yaml"
RUN_URL="https://github.com/leandroAmariles/shoplab-helpdesk/actions/runs/42"

pass=0; fail=0
check() { # descripción, comando...
  local desc="$1"; shift
  if "$@"; then pass=$((pass + 1)); echo "ok   - $desc"; else fail=$((fail + 1)); echo "FAIL - $desc"; fi
}

new_remote() { # crea $WORK/<caso>/{remote.git,clone} con main sembrado desde las fixtures
  local case_dir="$WORK/$1"
  mkdir -p "$case_dir"
  git init -q --bare -b main "$case_dir/remote.git"
  {
    git clone -q "$case_dir/remote.git" "$case_dir/seed"
    cp -r "$FIXTURES/infra/k8s" "$case_dir/seed/k8s"
    git -C "$case_dir/seed" add -A
    git -C "$case_dir/seed" -c user.name=seed -c user.email=seed@example.com commit -q -m "seed"
    git -C "$case_dir/seed" push -q origin HEAD:main
    git clone -q "$case_dir/remote.git" "$case_dir/clone"
  } >"$case_dir/setup.log" 2>&1 || { cat "$case_dir/setup.log" >&2; exit 2; }
  echo "$case_dir"
}

run_deploy() { # case_dir version [VAR=valor...] -> deja la salida en $case_dir/out.log y devuelve el código del script
  local case_dir="$1" version="$2"; shift 2
  env APP_VERSION="$version" APP_SHA="$APP_SHA" RUN_URL="$RUN_URL" INFRA_DIR="$case_dir/clone" REGISTRY_PREFIX="$PREFIX" \
    SERVICES="backend,frontend,ingest" IMAGE_CHECK_CMD="$FIXTURES/image-check-ok.sh" \
    POLICY_CHECK="$FIXTURES/policy-check.sh" PUSH_RETRY_WAIT=0 GITHUB_OUTPUT="$case_dir/output" "$@" \
    bash "$SCRIPT" >"$case_dir/out.log" 2>&1
}

remote_commits() { git -C "$1/remote.git" rev-list --count main; }
remote_file() { git -C "$1/remote.git" show "main:$2"; }
render_remote() { # case_dir -> render del overlay dev en el remoto
  git clone -q "$1/remote.git" "$1/verify" 2>/dev/null || git -C "$1/verify" pull -q
  kustomize build "$1/verify/k8s/overlays/dev"
}

# --- US1: éxito ------------------------------------------------------------------------------------------------
c="$(new_remote success)"
run_deploy "$c" "$VER_A"; code=$?
check "éxito: sale con 0" test "$code" -eq 0
check "éxito: crea un commit nuevo" test "$(remote_commits "$c")" -eq 2
check "éxito: solo toca el kustomization del overlay dev" \
  test "$(git -C "$c/remote.git" diff --name-only main~1 main)" = "$OVERLAY_FILE"
render="$(render_remote "$c")"
for svc in backend frontend ingest; do
  check "éxito: $svc se renderiza con el tag de la versión" grep -q "image: $PREFIX/$svc:$VER_A\$" <<<"$render"
done
check "éxito: el overlay ya no conserva digest" bash -c '! grep -q "digest:" <<<"$1"' _ "$(remote_file "$c" "$OVERLAY_FILE")"
check "éxito: resultado pushed" grep -q '^result=pushed$' "$c/output"

# --- US1: sin latest ni kubectl --------------------------------------------------------------------------------
check "sin latest: el overlay no contiene latest" bash -c '! grep -qi "latest" <<<"$1"' _ "$(remote_file "$c" "$OVERLAY_FILE")"
check "sin kubectl: ni el script ni el workflow lo usan" \
  bash -c '! grep -n "kubectl" "$1" "$2"' _ "$SCRIPT" "$HERE/../../workflows/images.yml"

# --- US1: imagen inexistente -----------------------------------------------------------------------------------
c="$(new_remote missing-image)"
run_deploy "$c" "$VER_A" FAIL_IMAGE_MATCH="/ingest:"; code=$?
check "imagen inexistente: sale con 10" test "$code" -eq 10
check "imagen inexistente: el remoto no cambia" test "$(remote_commits "$c")" -eq 1

# --- US1: build o política fallan ------------------------------------------------------------------------------
c="$(new_remote policy)"
run_deploy "$c" "$VER_A" FAIL_POLICY=1; code=$?
check "política: sale con 12" test "$code" -eq 12
check "política: el remoto no cambia" test "$(remote_commits "$c")" -eq 1
check "política: el árbol local queda restaurado" test -z "$(git -C "$c/clone" status --porcelain)"

# --- US1: policy-check sin bit de ejecución (en shoplab-infra el archivo se versionó como 100644) -------------------
c="$(new_remote policy-no-exec)"
cp "$FIXTURES/policy-check.sh" "$c/policy-check-no-exec.sh"
chmod 644 "$c/policy-check-no-exec.sh"
run_deploy "$c" "$VER_A" POLICY_CHECK="$c/policy-check-no-exec.sh"; code=$?
check "policy sin bit de ejecución: se ejecuta con bash y sale con 0" test "$code" -eq 0
check "policy sin bit de ejecución: crea el commit" test "$(remote_commits "$c")" -eq 2

check "policy se invoca con bash, no directamente" grep -Eq 'bash "\$POLICY_CHECK"' "$SCRIPT"

# --- US2: formato del commit -----------------------------------------------------------------------------------
c="$(new_remote commit-format)"
run_deploy "$c" "$VER_A" >/dev/null
msg="$(git -C "$c/remote.git" log -1 --format=%B main)"
check "commit: asunto con [LAB-81] y la versión" \
  test "$(git -C "$c/remote.git" log -1 --format=%s main)" = "chore(dev): [LAB-81] despliega $VER_A a dev"
check "commit: cuerpo con la versión" grep -q "^Versión: $VER_A\$" <<<"$msg"
check "commit: cuerpo con el SHA completo del tag" grep -q "shoplab-helpdesk@$APP_SHA" <<<"$msg"
check "commit: cuerpo con el enlace a la ejecución" grep -q "^CI: $RUN_URL\$" <<<"$msg"
check "commit: cuerpo con los servicios" grep -q "^Servicios: backend, frontend, ingest\$" <<<"$msg"
check "commit: autor shoplab-deploy[bot]" \
  test "$(git -C "$c/remote.git" log -1 --format=%an main)" = "shoplab-deploy[bot]"

# --- US2: sin filtrar credenciales -----------------------------------------------------------------------------
c="$(new_remote no-leak)"
mkdir -p "$c/remote.git/hooks"
printf '#!/bin/sh\necho "rechazado por la prueba token=SECRET-TOKEN-123" >&2\nexit 1\n' >"$c/remote.git/hooks/pre-receive"
chmod +x "$c/remote.git/hooks/pre-receive"
run_deploy "$c" "$VER_A" DEPLOY_TOKEN=SECRET-TOKEN-123 PUSH_RETRIES=1; code=$?
check "sin credencial: sale con 13" test "$code" -eq 13
check "sin credencial: el token no aparece en la salida" bash -c '! grep -q "SECRET-TOKEN-123" "$1"' _ "$c/out.log"

# --- US4: idempotencia -----------------------------------------------------------------------------------------
c="$(new_remote idempotent)"
run_deploy "$c" "$VER_A"; run_deploy "$c" "$VER_A"; code=$?
check "idempotencia: la segunda ejecución sale con 0" test "$code" -eq 0
check "idempotencia: un solo commit de despliegue" test "$(remote_commits "$c")" -eq 2
check "idempotencia: resultado no-change" grep -q '^result=no-change$' "$c/output"

# --- US4: servicio no declarado --------------------------------------------------------------------------------
c="$(new_remote undeclared)"
run_deploy "$c" "$VER_A" SERVICES="backend,frontend,ingest,reports"; code=$?
check "no declarado: sale con 11" test "$code" -eq 11
check "no declarado: el remoto no cambia" test "$(remote_commits "$c")" -eq 1

# --- US4: conflicto con reintento ------------------------------------------------------------------------------
c="$(new_remote conflict)"
git clone -q "$c/remote.git" "$c/other" 2>/dev/null
echo "# otro cambio" >>"$c/other/k8s/base/kustomization.yaml"
git -C "$c/other" add -A
git -C "$c/other" -c user.name=other -c user.email=other@example.com commit -q -m "otro cambio"
git -C "$c/other" push -q origin HEAD:main
# el clon del despliegue sigue en el commit sembrado, así que el primer push es rechazado
run_deploy "$c" "$VER_A"; code=$?
check "conflicto: integra el cambio ajeno y sale con 0" test "$code" -eq 0
check "conflicto: el remoto tiene el cambio ajeno y el despliegue" test "$(remote_commits "$c")" -eq 3
check "conflicto: el despliegue quedó fijado" grep -q "image: $PREFIX/backend:$VER_A\$" <<<"$(render_remote "$c")"

c="$(new_remote conflict-exhausted)"
mkdir -p "$c/remote.git/hooks"
printf '#!/bin/sh\nexit 1\n' >"$c/remote.git/hooks/pre-receive"
chmod +x "$c/remote.git/hooks/pre-receive"
run_deploy "$c" "$VER_A" PUSH_RETRIES=2; code=$?
check "reintentos agotados: sale con 13" test "$code" -eq 13
check "reintentos agotados: el remoto no cambia" test "$(remote_commits "$c")" -eq 1

# --- US4: orden de versiones -----------------------------------------------------------------------------------------
c="$(new_remote order)"
run_deploy "$c" "$VER_A"; run_deploy "$c" "$VER_B"
check "orden: dev queda en la versión más reciente" grep -q "image: $PREFIX/backend:$VER_B\$" <<<"$(render_remote "$c")"
check "orden: dos commits de despliegue" test "$(remote_commits "$c")" -eq 3

# --- Entradas ----------------------------------------------------------------------------------------------------
c="$(new_remote bad-input)"
run_deploy "$c" "$APP_SHA"; code=$?
check "entrada: un SHA en lugar de una versión se rechaza con 2" test "$code" -eq 2
run_deploy "$c" "v0.4.0-rc1"; code=$?
check "entrada: una versión con sufijo se rechaza con 2" test "$code" -eq 2
run_deploy "$c" "$VER_A" APP_SHA="no-es-un-sha"; code=$?
check "entrada: un APP_SHA que no es de 40 hex se rechaza con 2" test "$code" -eq 2
check "entrada: el remoto no cambia" test "$(remote_commits "$c")" -eq 1

echo
echo "resultado: $pass ok, $fail fallidas"
[ "$fail" -eq 0 ]
