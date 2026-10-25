# Quickstart: validar LAB-98

Guía de validación de extremo a extremo. Contratos en [contracts/module-contracts.md](contracts/module-contracts.md);
entidades en [data-model.md](data-model.md). Se ejecuta en el repositorio `shoplab-infra`.

## Requisitos previos

- Docker (con acceso a `/var/run/docker.sock`), Terraform >= 1.10, `make`, `kubectl`, `gitleaks`.
- Sin cuenta ni credenciales reales de AWS. Mínimo orientativo: 16 GB de RAM libres y 4 CPU (se confirma en T001).

## 1. Preparar valores (fuera de git)

```bash
cp terraform/envs/local/secrets.auto.tfvars.example terraform/envs/local/secrets.auto.tfvars
# editar con valores locales de prueba; el archivo está en .gitignore
```

Esperado: `git status` no muestra `secrets.auto.tfvars`. (US4-1, SC-006)

## 2. Levantar el entorno (US1)

```bash
make up
```

Esperado: Floci sano, bucket de estado creado, clúster activo, cuatro componentes de plataforma instalados y
Argo CD con su aplicación inicial `Synced/Healthy`.

```bash
export KUBECONFIG="$(terraform -chdir=terraform/envs/local output -raw kubeconfig_path)"
kubectl get pods -A
kubectl -n argocd get applications
```

## 3. Idempotencia (US1-3, SC-004)

```bash
terraform -chdir=terraform/envs/local plan -detailed-exitcode   # código 0 = sin cambios
```

## 4. Bloqueo de estado (US3-2, SC-009)

Lanzar dos `terraform apply` simultáneos en `envs/local`: el segundo debe fallar indicando que el estado está
bloqueado.

## 5. Secretos hacia el clúster (US4-2)

Comprobar el secreto en el emulador y su sincronización por External Secrets:

```bash
kubectl get externalsecret -A
kubectl get secret -n <namespace-demo> <nombre>
```

## 6. Tests y escaneo (US5)

```bash
make test               # fmt, validate, terraform test con mocks
make test-integration   # apply contra Floci
make scan               # 0 credenciales reales
```

## 7. Destruir y reconstruir (US2, SC-003)

```bash
make down
docker ps                # sin contenedores de Floci ni del clúster
make up                  # estado equivalente al inicial
```

## 8. Respaldo con kind (US6)

```bash
make up CLUSTER_MODE=kind
```

Esperado: mismos componentes y versiones sobre kind; sin clúster de kind en el modo por defecto.

## 9. Sin red hacia AWS (SC-005)

Repetir el paso 2 con la salida a Internet hacia `*.amazonaws.com` bloqueada: el flujo debe completarse igual.
