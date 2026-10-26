# ADR-0009: Observabilidad base con una sola fuente de dashboards entre dos repositorios

- Estado: aceptado
- Fecha: 2026-10-26
- Complementa a: ADR-0006 (GitOps pull con repositorio de infraestructura separado); no lo sustituye

## Contexto

LAB-102 pide ver desde el principio cómo se comportan el backend y el clúster, antes de añadir el LLM: métricas del
backend, dashboards de JVM, HTTP y pods versionados como código, y una alerta de ejemplo. Esos dashboards deben verse en
dos sitios: docker compose (donde el desarrollador trabaja a diario) y el clúster local (donde despliega Argo CD).

Tres hechos del estado actual condicionan la decisión:

- Los manifiestos del clúster viven en `shoplab-infra` y Argo CD los sincroniza por pull (ADR-0006), pero el compose y
  el código del backend viven en `shoplab-helpdesk`. Un mismo JSON de dashboard tiene que llegar a ambos lados sin
  copiarse a mano.
- `kube-prometheus-stack` (LAB-98) se instaló con Alertmanager desactivado para ahorrar recursos y, con los valores por
  defecto del chart, Prometheus solo selecciona los `ServiceMonitor` y las `PrometheusRule` con la etiqueta
  `release: kube-prometheus-stack`: un manifiesto sin esa etiqueta se ignora en silencio.
- El sidecar de Grafana del chart ya carga en cualquier namespace todo `ConfigMap` con la etiqueta
  `grafana_dashboard: "1"`.

## Decisión

- **Una sola copia de los dashboards**, en `shoplab-helpdesk/infra/observability/dashboards/*.json`. Docker compose los
  monta tal cual. En el clúster, una `Application` de Argo CD nueva, `shoplab-observability`, declarada en
  `shoplab-infra/k8s/argocd/`, sincroniza la ruta `infra/observability` de `shoplab-helpdesk` (rama `main`), cuyo
  `kustomization.yaml` genera un `ConfigMap` por dashboard en el namespace `monitoring`, con nombres estables y la etiqueta
  `grafana_dashboard`. Sigue siendo GitOps pull: el clúster lee git y las credenciales de escritura no salen de él.
- **Descubrimiento abierto**: Terraform pone `serviceMonitorSelectorNilUsesHelmValues` y `ruleSelectorNilUsesHelmValues`
  en `false`, de modo que los manifiestos de `k8s/` no dependen del nombre del release de Helm. El `ServiceMonitor` y la
  `PrometheusRule` del backend viven con su despliegue, en `shoplab-infra/k8s/base/backend/`.
- **Alertmanager activo** y sin receptores externos, con requests mínimos y sin almacenamiento (el k3s de Floci no trae
  `StorageClass`). La alerta de ejemplo `BackendDown` se prueba con `promtool` en el CI de `shoplab-infra`.
- **Sin dependencias nuevas de plataforma**: no se añaden OpenTelemetry, Loki ni Tempo; los paneles de coste de LLM y de
  lag de Kafka llegan con LAB-65.

Se descartan:

- **Copiar los JSON a `shoplab-infra` con un script o un job**: dos copias que derivan, y el compose o el clúster acabarían
  mostrando dashboards distintos.
- **Dejar la fuente en `shoplab-infra` y que compose la lea por ruta relativa**: compose dejaría de funcionar en un clon
  aislado de `shoplab-helpdesk`.
- **Multi-source dentro de `shoplab-dev`**: mezcla la plataforma de observabilidad con el despliegue de la aplicación y
  obligaría a tocar la prueba de humo y el contrato con LAB-81.
- **Empaquetar los dashboards en el chart de Terraform**: Terraform no es la fuente de verdad de un contenido que cambia
  a menudo, y cada cambio exigiría un `apply`.
- **Añadir `release: kube-prometheus-stack` a cada manifiesto**: acopla los manifiestos al nombre del release y falla en
  silencio si cambia.

## Consecuencias

- Un cambio de dashboard fusionado en `main` de `shoplab-helpdesk` llega solo al clúster, sin una PR en `shoplab-infra`.
  La revisión ocurre en la PR de `shoplab-helpdesk`, donde corre el job `observability` (JSON válido, `uid` y `title`
  únicos, sin `uid` de datasource fijo, `kustomize build` correcto).
- La `Application` `shoplab-observability` apunta a una ruta que debe existir en `main` de `shoplab-helpdesk`: la PR de
  `shoplab-helpdesk` se fusiona antes que la de `shoplab-infra`. Si la ruta desaparece, `prune` retira los dashboards.
- ADR-0006 sigue vigente: Terraform gestiona la plataforma y Argo CD lo que hay en git; solo se amplía a un segundo
  repositorio de origen para un contenido concreto (los dashboards). `shoplab-helpdesk` es público, así que no hace falta
  una credencial de lectura; si pasara a privado, habría que registrar un secreto de repositorio en Argo CD.
- Alertmanager añade un pod pequeño al nodo local de ~2 CPU; las alertas silenciadas se pierden al reiniciarlo.
- `/actuator/prometheus` no se publica por el ingress (solo `/api` y `/`): Prometheus lo recoge por la red interna. Si el
  backend añade autenticación, este punto se excluye o se protege entonces.
- En compose, Prometheus recoge el backend del host en el puerto fijo 8080 (no expande variables de entorno); cambiar
  `server.port` obliga a editar `infra/observability/prometheus/prometheus.yml`.
