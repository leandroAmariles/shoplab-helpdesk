# Quickstart: validar LAB-98

Guía de validación de extremo a extremo. Contratos en [contracts/module-contracts.md](contracts/module-contracts.md);
entidades en [data-model.md](data-model.md). Se ejecuta en el repositorio `shoplab-infra`; la guía de uso completa
está en su `docs/terraform-local.md`.

Cada comando tiene su equivalente directo en `scripts/` (`make up` = `bash scripts/up.sh`, etc.), útil si no hay
`make` instalado.

## Requisitos previos

- Docker (con acceso a `/var/run/docker.sock`), Terraform `~> 1.15.8`, bash (en Windows, Git Bash). Opcionales:
  `make` y `kubectl`.
- Sin cuenta ni credenciales reales de AWS. Recomendado: 16 GB de RAM y 4 CPU.

## 1. Preparar valores (fuera de git)

```bash
cp terraform/envs/local/secrets.auto.tfvars.example terraform/envs/local/secrets.auto.tfvars
# editar con valores locales de prueba; el archivo está en .gitignore
```

Esperado: `git status` no lo muestra. Sin este archivo ni `TF_VAR_secrets`, `make up` se detiene antes de levantar
nada y explica qué hacer. (US4-1, US4-3, SC-006)

## 2. Levantar el entorno (US1)

```bash
make up                  # bash scripts/up.sh
```

Esperado: Floci sano, bucket de estado creado, clúster activo, cuatro componentes de plataforma instalados y Argo CD
con su aplicación inicial `Synced/Healthy`.

```bash
export KUBECONFIG=terraform/envs/local/kubeconfig.yaml
kubectl get pods -A
kubectl -n argocd get applications
```

## 3. Idempotencia (US1-3, SC-004)

```bash
terraform -chdir=terraform/envs/local plan -detailed-exitcode   # código 0 = sin cambios
```

## 4. Bloqueo de estado (US3-2, SC-009)

Con un `apply` en curso, lanzar `terraform -chdir=terraform/envs/local plan -lock-timeout=0s` desde otra terminal:
debe fallar con `Error acquiring the state lock` (`412 PreconditionFailed`).

## 5. Secretos hacia el clúster (US4-2)

```bash
kubectl -n demo get externalsecret demo-postgres      # SecretSynced
kubectl -n demo get secret demo-postgres
```

## 6. Pruebas y escaneo (US5)

```bash
make test               # fmt, estructura, pines, terraform test con mocks de cada módulo, validate
make test-integration   # aplica un ambiente "itest" completo contra Floci y lo destruye (≈6 min)
make scan               # gitleaks sobre historial y staging (vía Docker)
```

## 7. Destruir y reconstruir (US2, SC-003)

```bash
make down               # bash scripts/down.sh; termina con verify-clean.sh
make up                 # estado equivalente al inicial
```

## 8. Respaldo con kind (US6)

```bash
make up CLUSTER_MODE=kind      # o: CLUSTER_MODE=kind bash scripts/up.sh
make down                      # recuerda el modo con el que se levantó
```

Esperado: mismos componentes y versiones sobre kind; en modo por defecto no se crea ningún clúster de kind.

## 9. Sin contacto con AWS real (SC-005)

Ejecutar el paso 2 con `TF_LOG=DEBUG TF_LOG_PATH=<ruta Windows>` y comprobar que no hay peticiones a
`*.amazonaws.com` (las menciones restantes son espacios de nombres XML y ARNs).

## Resultados de la validación (2026-10-24)

Máquina: Windows 11, Docker 29.6.1 con 16 GB, Terraform 1.15.8, sin `make` visible desde la sesión (se usaron los
scripts). Imágenes ya descargadas salvo en el primer arranque.

| Comprobación | Resultado |
| --- | --- |
| SC-002: 3 ciclos `up` desde cero | 3/3 correctos; `up` ≈ 250 s; 19 pods sanos; Argo CD `Synced/Healthy` |
| SC-003: `down` y reconstrucción | `down` 88–140 s, 0 residuos; estado equivalente al reconstruir |
| SC-004: re-aplicar sin cambios | `plan -detailed-exitcode` = 0 en todos los ciclos |
| SC-005: contacto con AWS real | 0 peticiones a `amazonaws.com` en la traza (modo DEBUG) |
| SC-006: credenciales en el repo | 0 hallazgos (gitleaks, historial y staging) |
| SC-007: versiones fijadas | `check-pins.sh` correcto; lock file con 4 plataformas |
| SC-008: pruebas en local | `make test` correcto (15 pruebas con mocks + validate); integración 1/1 |
| SC-009: bloqueo concurrente | Operación concurrente rechazada con 412 sobre el entorno real |
| US3-3: estados por ambiente | `staging` con estado propio vacío; `local` conserva sus 20 recursos |
| US6: respaldo con kind | `up` ≈ 240 s, mismos 4 componentes, Argo CD `Synced/Healthy`, secreto sincronizado; `down` limpio |
| Recorrido final (modo por defecto) | `up` 246 s, 18 pods sanos, `ExternalSecret` sincronizado, `down` 101 s, 0 residuos |

Pendiente de comprobar en una máquina con `make`: la capa `make up` / `make down` (envoltorios de una línea sobre
los scripts).
