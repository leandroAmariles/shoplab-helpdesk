#!/usr/bin/env bash
# Doble de `docker buildx imagetools inspect <imagen>`: la imagen existe salvo que FAIL_IMAGE_MATCH la nombre.
[ -z "${FAIL_IMAGE_MATCH:-}" ] || case "${*: -1}" in *"$FAIL_IMAGE_MATCH"*) exit 1 ;; esac
exit 0
