# Research: Métricas base con Prometheus y Grafana

Decisiones de la Fase 0. No quedan `NEEDS CLARIFICATION`. Los hechos marcados con (verificado) se comprobaron en el
repositorio o en el chart; los marcados con (a confirmar) se comprueban al implementar, con la tarea indicada en el
plan de tareas.

## D1. Exposición de métricas en el backend

- **Decisión**: añadir `io.micrometer:micrometer-registry-prometheus` y exponer el endpoint `prometheus` junto a
  `health` (`management.endpoints.web.exposure.include: health,prometheus`). No se añade `spring-boot-starter-*`
  nuevo.
- **Rationale**: (verificado) `spring-boot-starter-actuator` 4.1.2 ya arrastra `spring-boot-starter-micrometer-metrics`;
  lo único que falta es el registro de Prometheus. Se enumera cada endpoint en vez de `*` para no abrir `env`,
  `beans` ni `heapdump` (FR-006). La versión la gestiona el BOM de Boot.
- **Alternativas**: `include: "*"` (rechazada: expone endpoints sensibles); OpenTelemetry/OTLP (rechazada: exige un
  colector que la historia no pide; las trazas llegan en otra historia); agente de JMX exporter (rechazada: no da
  métricas HTTP por ruta).

## D2. Etiqueta común, histograma y cardinalidad

- **Decisión**: `management.metrics.tags.application: ${spring.application.name}` y
  `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.
- **Rationale**: la etiqueta `application` filtra los dashboards sin depender del nombre del `job` (que difiere entre
  compose y clúster, D6). El histograma publica `*_bucket`, necesario para `histogram_quantile(0.95, …)`; los
  percentiles calculados en el cliente no se pueden agregar entre réplicas. Spring MVC etiqueta `uri` con la plantilla
  de la ruta (`/tickets/{id}`) y colapsa las rutas no resueltas, así que la cardinalidad queda acotada (FR-005); se
  comprueba con una prueba en lugar de suponerlo.
- **Riesgo resuelto (T004, 2026-10-26)**: en Boot 3 `@SpringBootTest` desactivaba la exportación de métricas. En
  Spring Boot 4.1.2 esa lógica vive en el módulo de pruebas de Micrometer, que no está en el classpath del proyecto
  (solo `spring-boot-starter-test`); se comprobó que `/actuator/prometheus` responde `200` en `@SpringBootTest` sin
  anotación ni propiedad extra. Si se añade ese módulo en el futuro, habrá que reactivar la exportación en la prueba.
- **Hallazgo**: las rutas no resueltas se agrupan en `uri="/**"` (verificado en ejecución real), no en `NOT_FOUND`.
  El histograma publica unos 70 cubos por serie; con pocas rutas es aceptable, y se revisa si crece la cantidad de rutas.
- **Alternativas**: `slo:` con cubos fijos (rechazada: añade nombres de cubo que mantener sin necesidad todavía);
  etiquetar con `management.observations.key-values` (rechazada: no afecta al tag de todas las métricas).

## D3. Imágenes de Prometheus y Grafana en compose

- **Decisión**: `prom/prometheus` y `grafana/grafana` con tag exacto, sobrescribible por `PROMETHEUS_IMAGE` y
  `GRAFANA_IMAGE` (mismo patrón que `POSTGRES_IMAGE` y `OLLAMA_IMAGE`). El tag se elige al implementar para que
  coincida con la versión de Prometheus y de Grafana que despliega `kube-prometheus-stack` 92.13.1
  (`helm template`/`helm show chart`), así los mismos JSON de dashboard se comportan igual en ambos lados.
- **Rationale**: SC-006/FR-008 prohíben `latest`; alinear versiones evita sorpresas de esquema de dashboard. **Confirmado (T002, 2026-10-26)**: el chart 92.13.1 despliega Prometheus `v3.15.0` y Grafana `13.2.4`; se fijan
  `prom/prometheus:v3.15.0` y `grafana/grafana:13.2.4` (ambos tags existen en Docker Hub) y `promtool` 3.15.0.
- **Alternativas**: usar siempre la última versión (rechazada: rompe la reproducibilidad); levantar kube-prometheus
  completo en compose (rechazada: pesado para el perfil local).

## D4. Aprovisionamiento local de Prometheus y Grafana

- **Decisión**: archivos montados en solo lectura: `prometheus.yml` con un `scrape_config` (`job_name:
  helpdesk-backend`, `metrics_path: /actuator/prometheus`, objetivo `host.docker.internal:8080`), más `extra_hosts:
  host.docker.internal:host-gateway` para que funcione también en Linux. Grafana monta
  `provisioning/datasources`, `provisioning/dashboards` y la carpeta `dashboards/`; contraseña de administrador
  `change-me-local-only` por variable. Healthchecks con `wget --spider` a `/-/healthy` y `/api/health`.
- **Rationale**: el backend corre en el host durante el desarrollo (compose solo tiene Postgres y Ollama), por lo que
  Prometheus debe alcanzar el host (FR-009). Los dashboards salen de archivos, no de la interfaz (FR-010). Prometheus
  no expande variables de entorno en su configuración, así que el puerto del backend es fijo (8080) y cambiarlo se
  documenta; los puertos publicados de Prometheus y Grafana sí son configurables (`PROMETHEUS_PORT`, `GRAFANA_PORT`).
- **Alternativas**: servir el `scrape_config` con `file_sd` generado (rechazada: más piezas para un solo objetivo);
  meter el backend en compose (rechazada: fuera del alcance de la historia y de LAB-10).

## D5. Cómo llegan los dashboards al clúster (fuente única)

- **Decisión**: una `Application` de Argo CD nueva, `shoplab-observability`, en `shoplab-infra/k8s/argocd/`, con
  `repoURL` de `shoplab-helpdesk`, `path: infra/observability`, `targetRevision: main`, destino `monitoring` y el
  finalizer. El kustomization de esa ruta genera un `ConfigMap` por dashboard con la etiqueta `grafana_dashboard: "1"`
  y `disableNameSuffixHash: true` (nombres estables). El sidecar de Grafana ya busca en **todos** los namespaces y con
  esa etiqueta (verificado en los valores del chart 92.13.1: `sidecar.dashboards.searchNamespace: ALL`).
- **Rationale**: es la única opción que cumple FR-016 («sin duplicarlos») y FR-017 (reaparecen tras `destroy` y
  `apply`) sin copiar archivos entre repositorios. Es coherente con GitOps pull (ADR-0006): Argo CD lee git y las
  credenciales de escritura no salen del clúster. El repositorio es público, así que no hace falta credencial de
  lectura. Cumple las políticas de CI de infra (`targetRevision: main`, finalizer, `repoURL` sin placeholder).
- **Consecuencias**: un cambio de dashboard en `main` de helpdesk se aplica solo, sin PR en infra; hay que fusionar
  helpdesk antes que infra (ver «Orden de entrega»). La smoke de infra solo exige que la `Application` exista
  (`ARGOCD_PRESENT_APPS`), no que quede `Healthy`.
- **Alternativas**: (a) copiar los JSON a `shoplab-infra` con un script de sincronización (rechazada: duplica y
  deriva); (b) fuente en infra y compose la lee por ruta relativa de otro repositorio (rechazada: compose dejaría
  de funcionar en un clon aislado); (c) multi-source dentro de `shoplab-dev` (rechazada: mezcla plataforma con la
  aplicación y obliga a tocar la smoke y el contrato con LAB-81); (d) dashboards dentro del chart de Terraform
  (rechazada: Terraform no es la fuente de verdad de contenido que cambia a menudo).

## D6. Descubrimiento en el clúster: `ServiceMonitor` y regla

- **Decisión**: `ServiceMonitor` y `PrometheusRule` en `k8s/base/backend/`, y en Terraform
  `serviceMonitorSelectorNilUsesHelmValues: false` y `ruleSelectorNilUsesHelmValues: false` en
  `prometheus.prometheusSpec`.
- **Rationale**: (verificado) con los valores por defecto del chart, `serviceMonitorSelector`/`ruleSelector` quedan
  fijados a la etiqueta `release: kube-prometheus-stack`, así que un `ServiceMonitor` sin esa etiqueta se ignora en
  silencio. Poner los selectores a «todo» desacopla los manifiestos del nombre del release de Helm (el
  `serviceMonitorNamespaceSelector` ya es `{}`, es decir, todos los namespaces). El `ServiceMonitor` apunta al puerto
  con nombre `http` del `Service` `backend` (verificado) y a la ruta `/actuator/prometheus`; el `job` resultante es
  `backend`. La etiqueta `application` del backend (D2) es lo que une los dashboards con `compose` (`job` =
  `helpdesk-backend`) y el clúster (`job` = `backend`).
- **Alternativas**: añadir `release: kube-prometheus-stack` a cada manifiesto (rechazada: acopla nombres); `PodMonitor`
  (rechazada: el `Service` ya existe y da un objetivo por réplica igual).

## D7. Alertmanager en el clúster

- **Decisión**: activar Alertmanager en `monitoring.tf` (hoy `alertmanager = { enabled = false }`) con requests mínimos
  y sin persistencia, y dejarlo sin receptores externos.
- **Rationale**: (verificado) LAB-98 lo desactivó para ahorrar recursos; el criterio de aceptación exige la alerta «en
  Alertmanager», así que es un cambio imprescindible; el spec lo daba por instalado y se corrigió en Assumptions. Sin `StorageClass` en el k3s de Floci, un PVC dejaría el pod `Pending`; las alertas
  silenciadas se pierden al reiniciar, aceptable en un laboratorio.
- **Alternativas**: evaluar la regla solo en Prometheus sin Alertmanager (rechazada: no cumple el criterio); receptor
  de correo o chat (rechazada: fuera del alcance, exige secretos).

## D8. Diseño de la regla «backend caído»

- **Decisión**: `BackendDown` con `expr: up{job="backend",namespace="shoplab-dev"} == 0 or
  absent(up{job="backend",namespace="shoplab-dev"})`, `for: 2m`, `severity: critical`, y `summary`/`description`/`runbook`
  en español.
- **Rationale**: solo `up == 0` no cubre el caso de escalar a 0 réplicas o borrar el `Deployment`: la serie `up`
  desaparece y la alerta nunca se dispararía. `absent()` cubre ese hueco. Los 2 minutos más el intervalo de
  recogida y de evaluación quedan por debajo del límite de 5 minutos (SC-004) y un reinicio por despliegue
  progresivo no la dispara porque el pod antiguo sigue siendo objetivo hasta que el nuevo está listo. El umbral se
  expresa en la propia regla y se ajusta sin tocar el spec.
- **Alternativas**: alertar por `kube_deployment_status_replicas_available == 0` (rechazada: no detecta un pod vivo
  que no sirve métricas); `for: 5m` (rechazada: roza el límite de SC-004).

## D9. Validación automática

- **Decisión**: (helpdesk) job `observability` en `ci.yml`, filtrado por `infra/observability/**` e incluido en
  `ci-gate`: `jq empty` sobre cada dashboard, comprobación de `uid` y `title` únicos y no vacíos, `kustomize build
  infra/observability` y `docker compose --profile observability config`. (infra) `promtool check rules` y `promtool
  test rules` sobre la regla extraída del `PrometheusRule` (`rules-tests/`), con `PROMTOOL_VERSION` fijada en
  `versions.env`, más el `kustomize build` + `kubeconform` + políticas que ya existen para el `ServiceMonitor` y la
  regla (el catálogo de CRDs que usa `kubeconform` incluye los de `monitoring.coreos.com`, a confirmar).
- **Rationale**: SC-007 pide validación automática de JSON y manifiestos; la regla es lógica y merece una prueba
  unitaria. El job nuevo conserva el patrón de `ci-gate` para no cambiar la protección de `main`.
- **Alternativas**: validar dashboards con `grafana-dashboard-linter` (rechazada por ahora: otra herramienta que
  fijar; `jq` y la comprobación de `uid` cubren el criterio); no probar la regla (rechazada: la regla es la parte con
  más riesgo lógico, D8).

## Decisiones menores

- **Dashboards con variable de datasource**: cada JSON declara una variable `datasource` de tipo `datasource`
  (`prometheus`), de modo que no dependen del `uid` del origen de datos y sirven igual en compose y en el clúster.
- **Dashboard de pods**: usa métricas de `kube-state-metrics` y cAdvisor, que `kube-prometheus-stack` ya recoge
  (`kubeStateMetrics` está habilitado por defecto). En compose no hay pods: el dashboard muestra «sin datos» y se
  documenta como esperado.
- **Carpeta en Grafana**: los tres dashboards se agrupan en la carpeta «ShopLab»; el `uid` es estable
  (`shoplab-jvm`, `shoplab-http`, `shoplab-pods`).
- **Seguridad del punto de métricas**: no se publica por el ingress (verificado: solo `/api` y `/`); el acceso es de
  red interna. Si se añade seguridad al backend más adelante, se excluye o protege entonces (Assumptions del spec).
