# Research: Manifiestos con Kustomize y overlays

## R1. Estructura de la base y de los overlays

- **Decisión**: `k8s/base/<componente>/` con su propio `kustomization.yaml`, agregados por `k8s/base/kustomization.yaml`;
  `k8s/overlays/<ambiente>/` referencia `../../base` y aporta namespace, parches, HPA y generadores.
- **Justificación**: es el patrón estándar de Kustomize; un componente nuevo en F4 es un directorio más (US5) y los
  overlays no copian manifiestos (SC-004).
- **Alternativas**: Helm chart propio (más indirección y plantillas que no hacen falta; la base declarativa pura es
  suficiente); un directorio plano con todos los YAML (no escala); `components/` de Kustomize (útil luego, pero
  innecesario con seis componentes).

## R2. Namespace y aislamiento por ambiente

- **Decisión**: un namespace por overlay (`shoplab-dev`, `shoplab-staging`, `shoplab-prod`) declarado en el overlay,
  con etiquetas de Pod Security `baseline` (enforce) y `restricted` (warn).
- **Justificación**: permite que los tres ambientes convivan en un mismo clúster de práctica y que cada uno use sus
  propios secretos (FR-010). Postgres y Ollama con root necesitan `baseline`, no `restricted`.
- **Alternativas**: un clúster por ambiente (no viable en Floci local); sufijos de nombre en vez de namespaces (ensucia
  los selectores).

## R3. Fijado de imágenes

- **Decisión**: la base referencia imágenes sin tag efectivo y los overlays las fijan con `images:` (nombre y
  `newTag` o `digest`). Backend/frontend/ingest: `ghcr.io/leandroamariles/<servicio>` por **digest** (los tags `vX.Y.Z`, inmutables según LAB-78, aún no están publicados; al estarlo se promueve con `newTag`). Los tags
  por SHA de commit existen: el digest sale de `…/manifests/<sha-completo>`. Postgres y Ollama: las versiones de
  `infra/docker-compose.yml`.
- **Justificación**: un solo lugar por ambiente para promover una versión; la política de LAB-99 (sin `:latest` ni sin tag)
  se evalúa sobre la salida construida. La base nunca debe construirse sola para desplegar (no es un overlay).
- **Nota**: el tag `latest` existe en GHCR para `main`, pero queda prohibido aquí (FR-008).
- **Alternativas**: tag `sha-<largo>` (válido, pero ilegible para promover); Renovate/Dependabot para actualizar tags
  (valioso, fuera de alcance; se anota como seguimiento).

## R4. Réplicas, recursos y HPA

- **Decisión**: `dev` 1 réplica y recursos mínimos; `staging` intermedio; `prod` mayor (valores en
  [contracts/environment-values.md](contracts/environment-values.md)). HPA `autoscaling/v2` por overlay para backend,
  frontend e ingest, sobre CPU. Con HPA presente, el Deployment no fija `replicas` en `staging`/`prod` para que Argo CD
  no pelee con el autoescalador; en `dev` se usa `minReplicas=1`.
- **Justificación**: k3s incluye metrics-server, así que el HPA funciona en el clúster local. Postgres y Ollama son de una
  réplica con estado y quedan fuera del HPA (supuesto del spec).
- **Alternativas**: HPA en la base con parches de valores (triplica los parches); KEDA (complejidad innecesaria).

## R5. Sondas del backend

- **Decisión**: `startupProbe` y `livenessProbe` sobre `/actuator/health/liveness`, `readinessProbe` sobre
  `/actuator/health/readiness`, puerto 8080. Se fuerza `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true` desde el
  ConfigMap del backend.
- **Justificación**: `application.yml` solo expone `health` (`management.endpoints.web.exposure.include: health`); los
  grupos de sondas se activan solos en Kubernetes y la variable lo hace explícito sin tocar el código. La readiness
  refleja la base de datos y Ollama cuando el backend los declare.
- **Frontend e ingest**: sonda HTTP sobre `/` (frontend, nginx) y TCP al puerto 8000 (ingest, hoy su imagen sirve un
  sitio estático si no hay entorno virtual); se afinan cuando ingest tenga endpoint de salud.
- **Alternativas**: `httpGet /actuator/health` único (mezcla liveness con dependencias: un fallo de Postgres reinicia
  el pod sin necesidad).

## R6. Secretos con ExternalSecret

- **Decisión**: un `ExternalSecret` (`external-secrets.io/v1`) por componente que necesita credenciales (`postgres`,
  `backend`, `ingest`), con `secretStoreRef` al `ClusterSecretStore` de LAB-98 y `remoteRef.key` =
  `/shoplab/<ambiente>/<nombre>`. El `Secret` resultante lo crea ESO en el clúster; los pods lo consumen con
  `secretKeyRef`/`envFrom`. El ambiente se inyecta con un parche del overlay sobre la `remoteRef.key`.
- **Justificación**: cumple FR-009/FR-010 y reutiliza el prefijo `/shoplab/<ambiente>/` del módulo `secrets` de
  LAB-98. El valor jamás pasa por git.
- **Regla de política nueva**: ningún recurso `kind: Secret` en la salida de los overlays (FR-009/SC-003); se añade a
  `policy-check.sh` de LAB-99 con fixtures. Como el `Secret` generado por ESO no está en los manifiestos, no choca.
- **Alternativas**: SealedSecrets/SOPS (descartado: el requisito es Secrets Manager); `secretGenerator` de Kustomize (valores
  en git).

## R7. Postgres con pgvector y Ollama

- **Decisión**: ambos como `StatefulSet` de una réplica con `volumeClaimTemplates`. Postgres reutiliza el script
  `01-extensions.sql` (extensión `vector`) mediante un ConfigMap (`configMapGenerator` con el archivo); Ollama
  reutiliza `entrypoint.sh` de compose y las variables `OLLAMA_CHAT_MODEL`/`OLLAMA_EMBED_MODEL` por overlay.
- **Justificación**: mantiene la paridad con el desarrollo local por docker compose (misma imagen, mismo init, mismo
  arranque); un `Deployment` con PVC no da identidad estable.
- **Alternativas**: operador de Postgres (CloudNativePG: sobredimensionado para F0, se evalúa en F7); Postgres externo
  (el supuesto es dentro del clúster).

## R8. Ingress

- **Decisión**: un `Ingress` con `ingressClassName: nginx`; `/api` → backend, `/` → frontend. Host por overlay
  (`shoplab.localtest.me` en `dev`, `staging.shoplab.example` y `shoplab.example` como marcadores no productivos en los otros
  dos). Sin TLS en esta historia.
- **Justificación**: ingress-nginx lo instala LAB-98 y ya se expone por NodePort en el clúster local; `localtest.me` resuelve
  a 127.0.0.1 sin editar `hosts`. Ingest no se publica (uso interno).
- **Alternativas**: Gateway API (ADR-0005 lo menciona como sustituto futuro; no se adopta ahora).

## R9. Endurecimiento para Trivy config

- **Decisión**: `securityContext` con `runAsNonRoot` (backend, frontend, ingest), `allowPrivilegeEscalation: false`,
  `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault` y `readOnlyRootFilesystem` donde la imagen lo permite
  (con `emptyDir` para `/tmp`); `resources.requests/limits` siempre definidos; `automountServiceAccountToken: false`.
- **Justificación**: evita hallazgos HIGH/CRITICAL bloqueantes en el pipeline de LAB-99 y es la línea base razonable.
- **Excepción esperada**: Postgres y Ollama sin `readOnlyRootFilesystem` / con usuario de imagen; se documentan en
  `.trivyignore` si Trivy los marca HIGH.

## R10. Integración con CI y con Argo CD

- **Decisión**: el pipeline de LAB-99 ya construye cada overlay desde `KUSTOMIZE_DIR`; esta historia fija
  `KUSTOMIZE_DIR=k8s/overlays` y añade la regla `no-plain-secret`. La `Application` inicial de Argo CD (LAB-98) apunta a
  `k8s/overlays/dev` con `targetRevision: main` y `CreateNamespace=false` (el namespace viene del overlay).
- **Justificación**: no se duplica validación; la prueba de humo de LAB-99 pasa a probar un despliegue real de `dev`.
- **A validar**: que el CRD de ExternalSecret esté en el catálogo de esquemas de kubeconform (si no, se agrega a la
  configuración de LAB-99).

## R11. Convención para servicios nuevos (F4+)

- **Decisión**: la Definition of Done del proyecto añade "base + overlays del servicio" para cada servicio nuevo desde
  F4; `docs/kustomize.md` incluye una receta de pasos (directorio en `base/`, registro en `base/kustomization.yaml`, imagen y
  réplicas/recursos/HPA en cada overlay, ExternalSecret si lleva credenciales).
- **Justificación**: cumple FR-013/FR-014 y evita que el despliegue se quede atrás del código.
