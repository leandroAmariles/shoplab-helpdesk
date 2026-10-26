# Scripts de CI

- `deploy-dev.sh`: fija en el overlay `dev` de `shoplab-infra` el tag del SHA publicado y empuja el commit (LAB-81).
- Pruebas: `bash .github/scripts/tests/deploy-dev.test.sh` (necesitan `git` y `kustomize`; sin red).
