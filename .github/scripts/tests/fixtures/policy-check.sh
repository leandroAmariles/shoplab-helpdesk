#!/usr/bin/env bash
# Doble de scripts/policy-check.sh de shoplab-infra: falla si FAIL_POLICY=1 o si el render contiene ":latest".
[ "${FAIL_POLICY:-}" != 1 ] || { echo "IMAGE_LATEST | dev | fixture" >&2; exit 1; }
! grep -q 'image: .*:latest' "$1" || { echo "IMAGE_LATEST | $2 | latest" >&2; exit 1; }
exit 0
