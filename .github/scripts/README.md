# Scripts de CI

- `deploy-dev.sh`: fija en el overlay `dev` de `shoplab-infra` el tag de la versión publicada (`vX.Y.Z`) y empuja el commit (LAB-81,
  ADR-0012).
- Pruebas: `bash .github/scripts/tests/deploy-dev.test.sh` (necesitan `git` y `kustomize`; sin red).
