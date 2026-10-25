# Implementation Plan: Manifiestos con Kustomize y overlays dev, staging y prod

**Branch**: `feature/LAB-80-kustomize-overlays` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-80-kustomize-overlays/spec.md`

## Summary

Crear en `shoplab-infra/k8s/` una base de Kustomize con los seis componentes de la plataforma (backend,
frontend, ingest, Postgres con pgvector, Ollama e ingress) y tres overlays (`dev`, `staging`, `prod`) que
solo ajustan namespace, réplicas, recursos, HPA, host del ingress y configuración por ambiente. Las imágenes
se fijan con la transformación `images:` de Kustomize (tag de versión liberada o digest, nunca `latest`) y los
secretos se declaran como `ExternalSecret` contra el `ClusterSecretStore` de Secrets Manager que instala
LAB-98, con claves `/shoplab/<ambiente>/<nombre>`. El backend usa las sondas de Actuator
(`/actuator/health/liveness` y `/readiness`). La validación de cada overlay (`kustomize build` + kubeconform +
políticas) la ejecuta el pipeline de LAB-99; esta historia le añade la regla "ningún `Secret` en claro" y
documenta la convención de servicios nuevos para F4+. Ver [research.md](research.md) para las decisiones.

## Technical Context

**Language/Version**: YAML de Kubernetes + Kustomize (versión fijada en `versions.env` de LAB-99, se
confirma en la tarea T001). Bash para la regla de política adicional.

**Primary Dependencies**: Kustomize; External Secrets Operator (instalado por LAB-98, API `external-secrets.io`);
ingress-nginx (LAB-98); metrics-server (incluido en k3s) para el HPA; Argo CD (LAB-98) como consumidor del
overlay. Imágenes: `ghcr.io/leandroamariles/{backend,frontend,ingest}` (LAB-78, tag `vX.Y.Z` inmutable),
`pgvector/pgvector:0.8.7-pg16` y `ollama/ollama:0.40.8` (las mismas versiones que `infra/docker-compose.yml`).

**Storage**: PVC para Postgres y para los modelos de Ollama (StorageClass por defecto del clúster; tamaños por
overlay). Sin cambios de esquema.

**Testing**: `kustomize build` de cada overlay + kubeconform (esquemas de Kubernetes y CRDs) + políticas de
LAB-99; casos de prueba de la nueva regla de `Secret` en claro con fixtures buenas/malas
(`scripts/tests/`); validación en vivo sobre el clúster Floci de LAB-98 siguiendo [quickstart.md](quickstart.md).

**Target Platform**: clúster Kubernetes emulado por Floci/k3s (LAB-98); los manifiestos son portables a EKS real.

**Project Type**: repositorio de infraestructura (manifiestos declarativos); sin código de aplicación nuevo.

**Performance Goals**: validación de los 3 overlays en CI < 5 min (SC-005); sincronización de Argo CD del overlay
`dev` en el tiempo ya presupuestado por la prueba de humo de LAB-99 (< 20 min en total).

**Constraints**: ningún `Secret` con valores en git; imágenes sin `latest`; sin cambios en el código del backend
(las sondas se activan por configuración); pasar Trivy config con HIGH/CRITICAL bloqueantes (LAB-99/LAB-76);
no incluir Kafka ni servicios de F4+.

**Scale/Scope**: 1 base, 3 overlays, 6 componentes, 3 HPA por overlay, 1 ExternalSecret por componente que
necesita credenciales.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | Cumple: se despliega un único backend (monolito); sin servicios extraídos ni Kafka. |
| II. Arquitectura hexagonal | N/A: manifiestos; no se toca el código de aplicación. |
| III. Identificadores en inglés | Cumple: nombres de recursos, claves y etiquetas en inglés; docs en español. |
| IV. Test-first con gate de cobertura | Cumple en espíritu: la regla de política nueva se escribe con fixtures antes de activarse. JaCoCo no aplica. |
| V. RAG evaluado | N/A (F0). |
| VI. Observabilidad y manejo seguro | Cumple: secretos solo por ExternalSecret, sin valores en git, `securityContext` restrictivo; las sondas dan salud observable. |
| VII. Simplicidad y decisiones documentadas | Cumple: Kustomize puro (sin Helm ni plantillas propias). Decisión de estructura en research R1–R3; no requiere ADR nuevo (es consecuencia de ADR-0005). |
| Infraestructura: "Kubernetes llega en F7" | Misma desviación ya aceptada en LAB-98/LAB-99: historia de F0 que solo prepara el despliegue declarativo; el despliegue real de la app sigue en F7. |
| Flujo / commits | Cumple: rama `feature/LAB-80-…`; título de PR `feat(k8s): [LAB-80] …`. |

Gate: **PASA**. Re-evaluado tras el diseño de Fase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-80-kustomize-overlays/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── kustomize-layout.md     # estructura de base/overlays y reglas de composición
│   ├── environment-values.md   # réplicas, recursos, HPA y configuración por ambiente
│   └── secrets-contract.md     # ExternalSecret: claves, store y consumo
├── checklists/requirements.md
└── tasks.md                    # /speckit-tasks (no lo crea este comando)
```

### Source Code (repositorio `shoplab-infra`)

```text
shoplab-infra/
├── k8s/
│   ├── base/
│   │   ├── kustomization.yaml           # lista los componentes y las etiquetas comunes
│   │   ├── backend/                     # deployment.yaml, service.yaml, configmap env, externalsecret.yaml
│   │   ├── frontend/                    # deployment.yaml, service.yaml
│   │   ├── ingest/                      # deployment.yaml, service.yaml, externalsecret.yaml
│   │   ├── postgres/                    # statefulset.yaml, service.yaml, init (vector), externalsecret.yaml
│   │   ├── ollama/                      # statefulset.yaml, service.yaml, entrypoint.sh (ConfigMap)
│   │   └── ingress/                     # ingress.yaml (host de marcador, reescrito por overlay)
│   └── overlays/
│       ├── dev/      # kustomization.yaml, namespace.yaml, hpa.yaml, patches/, config.env
│       ├── staging/
│       └── prod/
├── scripts/
│   ├── policy-check.sh                  # LAB-99; se añade la regla no-plain-secret
│   └── tests/fixtures/{good,bad-secret}/  # casos de la regla nueva
├── docs/
│   └── kustomize.md                     # estructura, build local, cómo añadir un servicio (FR-013)
└── versions.env                         # KUSTOMIZE_DIR=k8s/overlays (parámetro de LAB-99)
```

Además, en este repositorio (`shoplab-helpdesk`): la Definition of Done del proyecto (FR-014) y, si procede, un
párrafo en `README.md`/`docs/` apuntando a `docs/kustomize.md` de `shoplab-infra`.

**Structure Decision**: base por componente (un directorio con su propio `kustomization.yaml`) para que añadir un
servicio en F4 sea copiar un directorio y registrar una línea; overlays por ambiente solo con parches y
generadores, nunca copias de la base. Esta spec, plan y tareas permanecen en `shoplab-helpdesk/specs/` como en
LAB-98/LAB-99; el código se implementa en `shoplab-infra`. La ruta `k8s/` sustituye a la provisional
`kubernetes/overlays` del plan de LAB-99 (se ajusta con `KUSTOMIZE_DIR`).

## Riesgos y puntos abiertos

- **Entradas de Secrets Manager por ambiente**: LAB-98 solo crea secretos del ambiente `local`. Los ExternalSecret
  de `dev`, `staging` y `prod` necesitan sus entradas; en el clúster local solo se sincroniza `dev` (la prueba de
  humo de LAB-99 despliega ese overlay). Hay que confirmar el nombre del `ClusterSecretStore` y que el módulo
  `secrets` acepta crear las claves de `dev` (T001); si no, se añade un `environment = "dev"` en `envs/local`.
- **Tag de imagen de la aplicación**: no hay mecanismo automático que actualice el tag en `shoplab-infra` tras
  un release de la app. Los tags `v0.1.x` no existen en GHCR (solo `latest` y los tags por SHA), así que se fijan por digest de la imagen construida desde `main`; cuando haya tags de versión se promueve con `newTag`. La automatización queda fuera de alcance.
- **Sondas de Actuator**: hoy solo se expone `health`. Los grupos `liveness`/`readiness` se activan en Kubernetes
  por detección del entorno y se fuerzan por variable de entorno; se verifica con el backend real en el
  quickstart. Si `health` expuesto no incluye los grupos, se abre una tarea en `backend/` (fuera de este repo).
- **Ollama en el clúster local**: la descarga de modelos puede tardar y exigir memoria; la readiness de Ollama no
  bloquea al resto y `dev` usa solicitudes reducidas. `prod` y `staging` se validan solo con `kustomize build`.
- **Argo CD**: la `Application` inicial de LAB-98 debe apuntar a `k8s/overlays/dev` (cambio de una línea en
  `shoplab-infra`); se incluye como tarea de integración, no como alcance nuevo.
- **Trivy config**: algunas imágenes de terceros (Postgres, Ollama) corren como root; si Trivy reporta HIGH, se
  documenta en `.trivyignore` con motivo (mecanismo de LAB-99).

## Complexity Tracking

| Violación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Un directorio por componente en la base | Añadir servicios en F4 debe ser copiar un directorio (US5) | Un único `base.yaml` crece sin límite y dificulta los parches |
| HPA definido por overlay y no en la base | Mínimos y máximos distintos por ambiente (FR-005) | HPA en la base obligaría a parchear los tres valores en cada overlay |
