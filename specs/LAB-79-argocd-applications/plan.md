# Implementation Plan: Argo CD con una Application por ambiente

**Branch**: `feature/LAB-79-argocd-applications` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-79-argocd-applications/spec.md`

## Summary

Hacer que Argo CD despliegue ShopLab desde git en el repositorio `shoplab-infra`. Hoy ya existen una
`Application` por ambiente en `k8s/argocd/<env>/application.yaml` (LAB-80/LAB-99), pero ninguna se aplica: la
`Application` inicial que instala Terraform sigue siendo el ejemplo `guestbook`. Esta historia (1) reorganiza
`k8s/argocd/` en archivos planos `application-{dev,staging,prod}.yaml` agrupados por un `kustomization.yaml`,
(2) añade la `Application` raíz `k8s/argocd/root.yaml` (app-of-apps) cuya ruta es `k8s/argocd`, y Terraform la
toma como su `Application` inicial leyendo ese mismo archivo (una sola fuente de verdad), (3) expone Argo CD
por el ingress local con `argocd.localtest.me` mediante valores del chart, (4) adapta el CI de LAB-99 al nuevo
diseño y (5) documenta el flujo en `shoplab-infra/docs/gitops.md` y registra la decisión en el ADR-0006 de este
repositorio. Ver [research.md](research.md).

## Technical Context

**Language/Version**: YAML de Kubernetes y Argo CD + Kustomize 5.8.2 (`versions.env`); Terraform 1.15.8 para el
arranque y el ingress de Argo CD; Bash para los ajustes de `scripts/`.

**Primary Dependencies**: Argo CD (chart `argo-cd` fijado en `terraform/modules/platform/variables.tf`);
ingress-nginx y External Secrets (LAB-98); overlays `dev`, `staging` y `prod` (LAB-80); `kubeconform` y
`policy-check.sh` (LAB-99).

**Storage**: N/A (estado declarativo en git y en el clúster).

**Testing**: `kustomize build` + `kubeconform` + `policy-check.sh` sobre `k8s/argocd` y `root.yaml`; casos
nuevos en `scripts/tests/` (layout plano, `root.yaml`, finalizer ausente); `terraform test` del módulo `platform`
(ingress de Argo CD y `initial_app`); validación en vivo con [quickstart.md](quickstart.md).

**Target Platform**: clúster Kubernetes emulado por Floci/k3s (LAB-98); portable a EKS real.

**Project Type**: repositorio de infraestructura (manifiestos y Terraform); sin código de aplicación.

**Performance Goals**: cambio en `main` reflejado en el clúster en < 5 min (SC-002, apoyado en el intervalo de
sondeo por defecto de Argo CD de 3 min); validación en CI < 5 min.

**Constraints**: `repoURL` real y `targetRevision: main` en todas las `Application` (reglas de LAB-99); sin
secretos en `k8s/argocd/`; Argo CD sigue instalándose solo por Terraform (FR-009); sin cambios en los overlays
de LAB-80 ni en el código del backend.

**Scale/Scope**: 1 `Application` raíz, 3 `Application` de ambiente, 1 ingress, 1 guía, 1 ADR.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | Cumple: se despliega el monolito ya definido en LAB-80; sin servicios nuevos. |
| II. Arquitectura hexagonal | N/A: manifiestos y Terraform; no se toca el código de aplicación. |
| III. Identificadores en inglés | Cumple: nombres de recursos y claves en inglés; documentación en español. |
| IV. Test-first con gate de cobertura | Cumple en espíritu: los casos de política y de `terraform test` se escriben antes de activar el cambio. JaCoCo no aplica. |
| V. RAG evaluado | N/A (F0). |
| VI. Observabilidad y manejo seguro | Cumple: sin credenciales en las `Application` ni en el ingress; la contraseña inicial de Argo CD se lee del clúster, no del repositorio. |
| VII. Simplicidad y decisiones documentadas | Cumple: Kustomize plano y una raíz, sin ApplicationSet ni AppProject propios. Decisión de plataforma (pull frente a push) registrada en ADR-0006 (FR-013). |
| Infraestructura: "Kubernetes llega en F7" | Misma desviación ya aceptada en LAB-98/LAB-80: historia de F0 que prepara el despliegue declarativo; el despliegue real de la aplicación sigue en F7. |
| Flujo / commits | Cumple: rama `feature/LAB-79-…`; título de PR `feat(argocd): [LAB-79] …`. |

Gate: **PASA**. Re-evaluado tras el diseño de Fase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-79-argocd-applications/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── application-contract.md   # campos obligatorios de cada Application y reglas de CI
│   └── bootstrap-contract.md     # Terraform ↔ root.yaml, ingress de Argo CD y acceso local
├── checklists/requirements.md
└── tasks.md                      # /speckit-tasks (no lo crea este comando)
```

### Source Code (repositorio `shoplab-infra`)

```text
shoplab-infra/
├── k8s/argocd/
│   ├── root.yaml                      # Application raíz; Terraform la lee; NO está en el kustomization
│   ├── kustomization.yaml             # resources: application-dev/staging/prod.yaml
│   ├── application-dev.yaml           # movida desde k8s/argocd/dev/application.yaml
│   ├── application-staging.yaml       # movida desde k8s/argocd/staging/application.yaml
│   └── application-prod.yaml          # movida desde k8s/argocd/prod/application.yaml
│
├── k8s/argocd-local/
│   └── kustomization.yaml             # parte de ../argocd y retira staging y prod: solo dev en local
├── terraform/
│   ├── modules/platform/
│   │   ├── argocd.tf                  # values del chart: ingress + server.insecure
│   │   ├── variables.tf               # argocd_host; argocd_initial_app sin default de guestbook
│   │   ├── outputs.tf                 # argocd_url por ingress
│   │   ├── charts/argocd-application/ # renderiza el manifiesto de root.yaml tal cual (toYaml)
│   │   └── tests/platform.tftest.hcl  # casos del ingress y de la initial app
│   └── envs/local/main.tf             # yamldecode(file(k8s/argocd/root.yaml)) → argocd_initial_app
├── scripts/
│   ├── kustomize-build.sh             # construye k8s/argocd una vez y valida root.yaml aparte
│   ├── smoke.sh                       # espera: raíz y dev Synced/Healthy; staging/prod creadas
│   └── tests/                         # fixtures de layout y finalizer
├── versions.env                       # ARGOCD_DIR=k8s/argocd (sin sufijo por ambiente)
└── docs/gitops.md                     # flujo GitOps, frontera Terraform/Argo CD, acceso y diagnóstico
```

En este repositorio (`shoplab-helpdesk`): `docs/adr/0006-gitops-pull-repo-infra-separado.md` y su fila en
`docs/adr/README.md`.

**Structure Decision**: archivos planos en `k8s/argocd/` (como pide la historia) con un `kustomization.yaml` que
lista solo las `Application` de ambiente; `root.yaml` convive en el directorio pero queda fuera del
`kustomization`, así la raíz no se gestiona a sí misma ni entra en bucle (FR-004, US2 escenario 4). La raíz sincroniza
`path: k8s/argocd`, que Argo CD detecta como Kustomize. Esta spec, plan y tareas permanecen en
`shoplab-helpdesk/specs/` y el código se implementa en `shoplab-infra`, como en LAB-80/LAB-98/LAB-99.

## Riesgos y puntos abiertos

- **Alcance de la prueba de humo (LAB-99), superado por R10 (en local solo se despliega `dev`; ver research.md)**: con la raíz, el clúster local recibe `dev`, `staging` y `prod`; los
  dos últimos son más grandes (más réplicas, HPA) y pueden no quedar Healthy en un runner de CI. El smoke exige
  raíz y `dev` Synced/Healthy y solo que `staging` y `prod` existan; la espera completa se valida en local
  (T-quickstart). Si en local los tres no caben en memoria, se reduce `prod`/`staging` en LAB-80, no aquí.
- **Acceso al ingress desde el host**: ingress-nginx es `NodePort` dentro del contenedor de k3s y no hay
  mapeo de puertos al host. Se usa `kubectl port-forward` al controlador del ingress y el host
  `argocd.localtest.me` (resuelve a 127.0.0.1), con `shoplab.localtest.me` funcionando igual. Un mapeo fijo de
  puertos exige cambios en la creación del clúster (LAB-98) y se descarta aquí (research R4).
- **Cambio de la raíz solo por Terraform**: como la raíz no se gestiona a sí misma, editar `root.yaml` requiere
  `make up`/`terraform apply`; se documenta en la guía. Todo lo demás cambia solo con git.
- **ExternalSecret de `staging`/`prod` en local**: dependen de las claves `/shoplab/<ambiente>/…` creadas en
  LAB-80; si faltan, el ambiente queda Degraded sin exponer valores (caso límite del spec). Se verifica en
  el quickstart.
- **Repositorio público**: Argo CD lee `shoplab-infra` sin credenciales; si pasa a privado hará falta un
  secreto de repositorio (fuera de alcance, FR-007).
- **Retraso de CRD**: las `Application` hijas dependen de CRD de External Secrets ya instalados por LAB-98;
  `retry` con backoff en cada `Application` cubre el caso en que el CRD aún no esté registrado.

## Complexity Tracking

| Violación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Terraform lee `root.yaml` con `yamldecode(file())` | Una sola fuente de verdad de la raíz en git y arranque sin pasos manuales (FR-009) | Duplicar los valores en `variables.tf` deja que git y Terraform diverjan sin que nadie lo note |
| `root.yaml` fuera del `kustomization` | Evita que la raíz se gestione a sí misma y borre su propia definición | Incluirla crea un bucle de autogestión con `prune` activo |
