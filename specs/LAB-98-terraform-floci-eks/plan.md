# Implementation Plan: Infraestructura como código sobre un AWS emulado en local (EKS local)

**Branch**: `feature/LAB-98-terraform-floci-eks` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-98-terraform-floci-eks/spec.md`

## Summary

Crear en el repositorio `shoplab-infra` una base de Terraform (`terraform/`) que, contra Floci (emulador AWS
MIT, imagen fijada, en docker compose), levante de cero un clúster EKS emulado (k3s) con ingress-nginx,
External Secrets Operator, Argo CD y kube-prometheus-stack, con estado remoto en S3 de Floci y bloqueo nativo.
El código se organiza en cuatro módulos (`state`, `eks`, `secrets`, `platform`), un bootstrap para el estado y un
entorno raíz `envs/local`. Un envoltorio (`Makefile`) encadena compose → bootstrap → apply para que "un solo
comando" funcione, y `destroy` lo revierte. El módulo `eks` admite un modo de respaldo con kind. Se acompaña de
pruebas `terraform test`, documentación en `docs/` y un ADR. Ver [research.md](research.md) para decisiones y
riesgos (en especial: bloqueo `use_lockfile` sobre S3 emulado y proveedores `helm`/`kubernetes` configurados con
un clúster creado en el mismo apply).

## Technical Context

**Language/Version**: HCL / Terraform >= 1.10 (requisito de `use_lockfile`); versión exacta fijada con
`required_version` y verificada en la implementación. Scripts de apoyo en `make` + shell.

**Primary Dependencies**: proveedores `hashicorp/aws`, `hashicorp/helm`, `hashicorp/kubernetes` (y `tehcyx/kind`
solo para el respaldo); charts ingress-nginx, external-secrets, argo-cd y kube-prometheus-stack; imagen
`floci/floci` con tag de versión (no `latest`) y `rancher/k3s` fijada por `FLOCI_SERVICES_EKS_DEFAULT_IMAGE`.
Las versiones exactas se resuelven y fijan en la implementación (tarea T001) y quedan en `versions.tf` y
`.terraform.lock.hcl`.

**Storage**: estado remoto en un bucket S3 emulado por Floci (`use_lockfile = true`); Secrets Manager y SSM
emulados para secretos/parámetros. N/A base de datos.

**Testing**: `terraform test` (`*.tftest.hcl`) con `command = plan` y `mock_provider` para contrato de módulos,
más una prueba de integración `command = apply` contra Floci; `terraform validate`, `terraform fmt -check`, y
un escaneo de credenciales (gitleaks) sobre el repo.

**Target Platform**: Docker Desktop / Docker Engine en Windows, macOS o Linux (con acceso a
`/var/run/docker.sock` para que Floci cree el k3s).

**Project Type**: repositorio de infraestructura (Terraform); sin servicios propios ni endpoints HTTP.

**Performance Goals**: apply desde cero y destroy completos en un tiempo razonable para una sesión de práctica
(objetivo orientativo < 15 min en una máquina de 16 GB; se mide en `quickstart.md`).

**Constraints**: sin cuenta ni credenciales reales de AWS; red hacia AWS real no requerida; ningún valor de
secreto en git; todo fijado (sin `latest` ni rangos abiertos); idempotente al re-aplicar.

**Scale/Scope**: un ambiente (`local`), un clúster de un nodo, cuatro módulos, cuatro charts.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | N/A: no hay código de aplicación. |
| II. Arquitectura hexagonal | N/A: infraestructura; los módulos Terraform separan contratos (variables/outputs) de implementación. |
| III. Identificadores en inglés | Cumple: recursos, variables y outputs en inglés; docs y ADR en español. |
| IV. Test-first con gate de cobertura | Cumple en espíritu: `terraform test` define el contrato de cada módulo antes/junto con la implementación. El umbral JaCoCo no aplica (sin Java). |
| V. RAG evaluado | N/A (fase F0). |
| VI. Observabilidad y manejo seguro | Cumple: secretos fuera de git (variables de entorno / `*.auto.tfvars` ignorado), plataforma instala monitorización base, sin datos reales de clientes. |
| VII. Simplicidad y decisiones documentadas | Cumple con ADR (FR-016). Complejidad añadida (bootstrap separado, respaldo kind) justificada abajo. |
| Infraestructura: "Kubernetes llega en F7" | Desviación justificada: la historia está en F0 (épica LAB-1) y es solo práctica local, sin desplegar la app; el despliegue de ShopLab sigue en F7. |
| Flujo / commits | Cumple: rama `feature/LAB-98-…`; commits `LAB-98 type(scope): …`; título de PR `type(scope): [LAB-98] …`. |

Gate: **PASA** (sin violaciones sin justificar). Re-evaluado tras el diseño de Fase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-98-terraform-floci-eks/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── module-contracts.md
├── checklists/requirements.md
└── tasks.md              # /speckit-tasks (no lo crea este comando)
```

### Source Code (repositorio `shoplab-infra`)

```text
shoplab-infra/
├── docker-compose.yml            # Floci con imagen fijada y docker.sock
├── Makefile                      # up / down / test / fmt: encadena compose, bootstrap y apply
├── .gitignore                    # excluye *.tfstate, .terraform/, secrets.auto.tfvars, kubeconfig
├── terraform/
│   ├── modules/
│   │   ├── state/                # bucket S3 de estado (versionado)
│   │   ├── eks/                  # EKS emulado (+ modo kind de respaldo); output kubeconfig
│   │   ├── secrets/              # Secrets Manager + SSM (valores por variable sensible)
│   │   └── platform/             # helm/kubernetes: ingress-nginx, ESO, Argo CD, kube-prometheus-stack
│   │       └── (cada módulo: main.tf, variables.tf, outputs.tf, versions.tf, tests/*.tftest.hcl)
│   └── envs/
│       └── local/
│           ├── bootstrap/        # aplica `state` con backend local (una vez por ambiente)
│           ├── main.tf           # compone eks + secrets + platform
│           ├── providers.tf      # aws → Floci; helm/kubernetes → salidas de `eks`
│           ├── backend.tf        # s3 + use_lockfile (endpoints Floci)
│           ├── variables.tf
│           ├── secrets.auto.tfvars.example
│           └── .terraform.lock.hcl
└── docs/
    ├── terraform-local.md        # requisitos, up/down, recuperación, modo kind
    └── adr/NNNN-terraform-floci.md   # copia/enlace al ADR (registro en shoplab-helpdesk/docs/adr)
```

**Structure Decision**: repositorio de infraestructura con módulos reutilizables y un entorno raíz por
ambiente (`envs/local`). El bootstrap del estado va aparte porque el backend remoto necesita que el bucket ya
exista. El ADR se redacta con la plantilla del registro de este repositorio (`docs/adr/`, LAB-16) y se enlaza
desde `docs/` de `shoplab-infra`. Esta spec/plan/tasks permanecen en `shoplab-helpdesk/specs/`.

## Complexity Tracking

| Violación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Raíz de bootstrap separada del entorno | El backend S3 no puede crear su propio bucket en el mismo apply | Un solo apply con backend local no cumple FR-004 (estado remoto) |
| Modo de respaldo kind en `eks` | Riesgo conocido: el EKS de Floci puede fallar (FR-017, ADR) | Sin respaldo, un fallo del emulador bloquea toda la historia |
| Envoltorio `Makefile` | "Un solo comando" requiere encadenar compose + bootstrap + apply | Pedir al desarrollador tres pasos manuales incumple SC-001 |

## Desviaciones respecto al diseño inicial (registradas al implementar)

- **Orquestación**: la lógica vive en `scripts/*.sh` y el `Makefile` solo las invoca, de modo que todo funciona sin
  `make` (Windows con Git Bash).
- **Kubeconfig**: se construye con el certificado de cliente de k3s (`docker exec` sobre `floci-aws-eks-<cluster>`),
  porque el token de `aws_eks_cluster_auth` se rechaza (research R4).
- **Endpoint de Secrets Manager para los pods**: los pods de k3s no resuelven `host.docker.internal` ni nombres de
  contenedor; se usa la IP de Floci en la red de compose (`scripts/floci-ip.sh`). En kind sigue valiendo
  `host.docker.internal`.
- **ingress-nginx** como `NodePort`: en kind un `LoadBalancer` no recibe IP y Helm esperaba hasta el timeout.
- **node-exporter** desactivado: falla con `CreateContainerError` en k3s dentro de Docker.
- **CRDs**: `ClusterSecretStore`, `Application` de Argo CD y `ExternalSecret` se instalan con charts locales para
  no depender de `kubernetes_manifest` durante el plan (R6).
- **Un solo apply** de cero a Argo CD funcionó con `helm`/`kubernetes` configurados desde las salidas de `eks`; no
  hizo falta dividir en dos raíces (riesgo R5 no materializado).
- **Modo de clúster recordado**: `up.sh` guarda `.cluster-mode` (ignorado por git) y `down.sh` lo usa para destruir
  con el mismo modo.
