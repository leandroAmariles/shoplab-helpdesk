# Implementation Plan: Métricas base con Prometheus y Grafana

**Branch**: `feature/LAB-102-base-metrics-prometheus-grafana` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-102-base-metrics-prometheus-grafana/spec.md`

## Summary

El backend ya tiene Actuator pero solo expone `health`; compose reserva el perfil `observability` sin servicios; el
clúster de LAB-98 instala `kube-prometheus-stack` **sin Alertmanager** y con selectores de descubrimiento por
defecto. La historia lo cierra en tres frentes:

1. **Backend** (`shoplab-helpdesk`): se añade el registro de Prometheus de Micrometer, se expone
   `/actuator/prometheus`, se etiqueta toda métrica con `application=helpdesk-backend` y se publica el histograma de
   `http.server.requests` para calcular p95 ([research.md](research.md) D1, D2).
2. **Local** (`shoplab-helpdesk`): el perfil `observability` arranca Prometheus y Grafana con imágenes fijadas;
   Prometheus recoge el backend que corre en el host y Grafana arranca con datasource y dashboards aprovisionados
   desde `infra/observability/` (D3, D4).
3. **Clúster** (`shoplab-infra`): Terraform activa Alertmanager y hace que Prometheus seleccione todos los
   `ServiceMonitor` y `PrometheusRule`; el overlay base del backend añade un `ServiceMonitor` y la regla
   `BackendDown`; una nueva `Application` de Argo CD, `shoplab-observability`, sincroniza los **mismos JSON** de
   dashboards desde `shoplab-helpdesk` (`infra/observability/`), de modo que hay una sola copia (D5, D6, D7).

La validación se reparte: pruebas del backend con `MockMvc`, un job de CI nuevo en `shoplab-helpdesk` (JSON de
dashboards, `kustomize build`, `docker compose config`), `promtool` para la regla en `shoplab-infra` y una validación
en vivo (compose y clúster local) registrada en `validation.md` al implementar, como en LAB-101.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 4.1.2 (backend); Terraform 1.15.8 (HCL), Kustomize 5.8.2, bash
(`shoplab-infra`); YAML de compose, Prometheus y Grafana provisioning; dashboards en JSON de Grafana.

**Primary Dependencies**: `io.micrometer:micrometer-registry-prometheus` (versión gestionada por el BOM de Boot; es la
única dependencia nueva del backend: `spring-boot-starter-actuator` ya arrastra `spring-boot-starter-micrometer-metrics`);
`kube-prometheus-stack` 92.13.1 (ya instalado, sin cambio de versión); imágenes de Prometheus y Grafana para compose
(tags exactos fijados al implementar, alineados con los que despliega el chart, D3); `promtool` (misma versión de
Prometheus) en `shoplab-infra` para validar la regla.

**Storage**: Series temporales de Prometheus: volumen con nombre en compose (`prometheus_data`) y retención de 6 h en
el clúster (ya configurada). Estado de Grafana en compose: volumen `grafana_data`, pero los dashboards salen de git.
Sin cambios de base de datos.

**Testing**: Backend: llamadas HTTP reales (`@SpringBootTest` con puerto aleatorio, JUnit 5) sobre `/actuator/prometheus` y
`/actuator/health` (D2). Repo helpdesk: job de CI `observability` (jq sobre dashboards, `kustomize build`
de `infra/observability`, `docker compose --profile observability config`). Repo infra: `terraform test` del módulo
`platform` (Alertmanager y selectores), `kustomize build` + `kubeconform` + políticas existentes, `promtool test rules`
de `BackendDown`. Validación en vivo con `make up` y `docker compose --profile observability up`.

**Target Platform**: Máquina local con Docker (compose) y clúster k3s de Floci (ambiente `dev`).

**Project Type**: Aplicación web con infraestructura como código en un repositorio separado (ADR-0006): cambio
repartido en `shoplab-helpdesk` y `shoplab-infra`.

**Performance Goals**: Datos visibles en dashboards < 2 min tras arrancar (SC-001); alerta disparada < 5 min y
resuelta < 5 min (SC-004). Intervalo de recogida de 30 s en el clúster y 15 s en compose.

**Constraints**: El k3s de Floci tiene ~2 CPU (ADR-0006/0008): Alertmanager con requests mínimos (10m CPU / 32Mi) y
sin PVC (el k3s no trae `StorageClass`). `/actuator/prometheus` no debe salir por el ingress (el ingress solo
publica `/api` y `/`, verificado). Cobertura JaCoCo ≥ 80 %, Checkstyle y PMD sin violaciones. Imágenes y charts sin
`latest`. Las políticas de CI de infra exigen `targetRevision: main`, `repoURL` real y finalizer en cada Application.

**Scale/Scope**: 1 dependencia y ~6 líneas de configuración en el backend; 2 servicios nuevos de compose; 3
dashboards JSON; 1 `ServiceMonitor`, 1 `PrometheusRule` y 1 `Application` nueva; ~3 archivos de Terraform/tests
editados; 1 ADR; 2 documentos.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Evaluación |
|---|---|
| I. Monolito modular primero | PASS: no se crean módulos ni se tocan fronteras; la configuración vive en `application.yml` y el `pom.xml`. |
| II. Hexagonal y pureza del dominio | PASS: no hay código de dominio nuevo. Las métricas de negocio con puertos llegarán con LAB-65. |
| III. Identificadores en inglés | PASS: nombres de servicio, archivos, métricas, etiquetas y reglas en inglés (`BackendDown`, `observability`); documentación y ADR en español. |
| IV. Test-first y cobertura | PASS: las pruebas de `MockMvc` se escriben antes de la configuración (fallan sin ella); no se añade código de producción con lógica, así que JaCoCo no baja. La regla de alerta tiene prueba unitaria (`promtool`). |
| V. RAG evaluado | N/A (F2+). |
| VI. Observabilidad y manejo seguro | PASS: es el primer paso del principio (métricas con latencia desde F0). Sin secretos en git: la contraseña de Grafana local es un marcador `change-me-local-only` solo válido en local; `/actuator/prometheus` queda en red interna. |
| VII. Simplicidad y decisiones documentadas | PASS con una decisión registrada: dashboards con fuente única entre dos repositorios y activación de Alertmanager → **ADR-0009**. No se añaden OpenTelemetry, Loki ni Tempo (YAGNI). |
| Restricciones: infraestructura | PASS: `docker compose -f infra/docker-compose.yml up` sigue siendo el arranque; el perfil nuevo es opcional. Kubernetes ya es F0 (LAB-79/80/98). |
| Estilo y calidad | PASS: líneas ≤ 121, llaves en todos los bloques, Checkstyle/PMD aplican al test nuevo. |
| Flujo: rama, historia, PR | PASS: rama `feature/LAB-102-…`, directorio de la spec igual al último segmento. Dos PR (helpdesk e infra); título `feat(observability): [LAB-102] …`. |

Sin violaciones; **Complexity Tracking** queda vacío. Re-evaluación tras el diseño: sin cambios. La única complejidad
añadida (la `Application` que lee otro repositorio) se justifica en D5 y se registra en el ADR-0009.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-102-base-metrics-prometheus-grafana/
├── plan.md              # Este archivo
├── research.md          # Fase 0: decisiones D1-D9
├── data-model.md        # Fase 1: entidades, etiquetas y reglas de validación
├── quickstart.md        # Fase 1: escenarios de validación de punta a punta
├── contracts/
│   ├── metrics-endpoint.md      # Contrato del punto de métricas y etiquetas
│   ├── observability-layout.md  # Archivos, servicios de compose y recursos del clúster
│   └── backend-down-alert.md    # Regla, umbrales y pruebas de la alerta
├── checklists/requirements.md
└── tasks.md             # Fase 2 (/speckit-tasks; no lo crea este comando)
```

### Source Code (repository root)

```text
shoplab-helpdesk/
├── backend/
│   ├── pom.xml                               # + micrometer-registry-prometheus
│   ├── src/main/resources/application.yml    # exposición, etiqueta común, histograma
│   └── src/test/java/com/shoplab/helpdesk/
│       └── MetricsEndpointTests.java         # prometheus, health y cardinalidad
├── infra/
│   ├── docker-compose.yml                    # perfil observability: prometheus + grafana
│   └── observability/
│       ├── kustomization.yaml                # configMapGenerator de dashboards (lo lee Argo CD)
│       ├── prometheus/prometheus.yml         # scrape del backend en el host
│       ├── grafana/provisioning/
│       │   ├── datasources/prometheus.yml
│       │   └── dashboards/dashboards.yml
│       └── dashboards/
│           ├── jvm.json
│           ├── http.json
│           └── pods.json
├── .github/workflows/ci.yml                  # + job observability (y en ci-gate)
├── docs/
│   ├── adr/0009-observabilidad-base-fuente-unica-de-dashboards.md  (+ índice)
│   └── observabilidad.md
└── README.md                                 # perfil observability deja de ser "reservado"

shoplab-infra/                                # PR aparte (ver D5 para el orden)
├── terraform/modules/platform/
│   ├── monitoring.tf                         # Alertmanager + selectores abiertos
│   └── tests/platform.tftest.hcl             # aserciones nuevas
├── k8s/base/backend/
│   ├── servicemonitor.yaml
│   ├── prometheusrule.yaml
│   └── kustomization.yaml                    # + los dos recursos
├── k8s/argocd/
│   ├── application-observability.yaml        # lee shoplab-helpdesk/infra/observability
│   └── kustomization.yaml
├── rules-tests/backend-down.test.yaml        # promtool test rules
├── scripts/                                  # promtool en check-tools, ci-install-tools y make check
├── versions.env                              # + PROMTOOL_VERSION (pin)
└── docs/observability.md
```

**Structure Decision**: Los dashboards, la configuración de compose y el kustomization que los empaqueta viven
juntos en `shoplab-helpdesk/infra/observability/` porque es la fuente única (FR-016). El `ServiceMonitor` y la regla
viven con el despliegue del backend en `shoplab-infra/k8s/base/backend/`, porque describen cómo se recoge *ese*
despliegue y deben cambiar junto a su `Service`. El kustomization de dashboards está en la raíz de
`infra/observability/` (no en una subcarpeta) para que `configMapGenerator` pueda leer `dashboards/*.json` sin
`--load-restrictor LoadRestrictionsNone`, que Argo CD no activa por defecto.

## Orden de entrega

1. PR de `shoplab-helpdesk` primero (backend, compose, dashboards, CI, ADR, docs): sin él, la `Application`
   `shoplab-observability` apuntaría a una ruta inexistente en `main`.
2. PR de `shoplab-infra` después (Terraform, `ServiceMonitor`, regla, `Application`, `promtool`).
3. El backend desplegado en `dev` solo publica `/actuator/prometheus` tras el despliegue continuo de la PR 1
   (LAB-81); antes de eso el objetivo aparece caído y la alerta lo señala, que es el comportamiento esperado.

## Complexity Tracking

> Sin violaciones de la constitución que justificar.
