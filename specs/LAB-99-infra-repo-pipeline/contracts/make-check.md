# Contract: `make check` y paridad con CI

## Targets

| Target | Script / comando | Job de CI equivalente | Incluido en `make check` |
|---|---|---|---|
| `check-tools` | `scripts/check-tools.sh` | pasos de instalación | sí (primero) |
| `fmt-check` | `terraform fmt -check -recursive terraform/` | `static` | sí |
| `validate` | `terraform init -backend=false` + `terraform validate` por módulo y entorno | `static` | sí |
| `lint` | `tflint --recursive` con `.tflint.hcl` | `static` | sí |
| `tf-test` | `terraform test` por módulo | `static` | sí |
| `kustomize` | `scripts/kustomize-build.sh` (build + kubeconform de `dev`, `staging`, `prod`) | `kustomize` | sí |
| `policies` | `scripts/policy-check.sh` sobre cada overlay renderizado | `kustomize` | sí |
| `test-policies` | `scripts/tests/policy-check.test.sh` | `kustomize` | sí |
| `trivy` | `trivy config` sobre `terraform/` y overlays renderizados, con `.trivyignore` | `trivy` | sí |
| `plan` | Floci + bootstrap + `terraform plan` | `plan` | no (requiere Docker) |
| `smoke` | `scripts/smoke.sh` | `smoke` | no (requiere Docker y tiempo) |

## Reglas

- Cada target ejecuta el mismo script con los mismos argumentos que el job; el workflow no contiene lógica propia.
- `make check` se detiene en el primer fallo y devuelve su código; `make -k check` ejecuta todo y resume.
- `check-tools` compara cada herramienta con `versions.env`: si falta imprime `falta <herramienta> <versión>` con la
  forma de instalarla; si la versión difiere, avisa y falla (salvo `ALLOW_TOOL_DRIFT=1`, documentado como solo local).
- El mensaje de una violación de política es idéntico en local y en CI (ver [policy-rules.md](policy-rules.md)).
- Sin Docker, `make check` funciona completo; solo `plan` y `smoke` lo requieren.

## Códigos de salida

`0` todo correcto; distinto de 0 si algún control falla o falta una herramienta.
