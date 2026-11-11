# Validación: Trazas de LLM con Langfuse (LAB-64)

Evidencia de las verificaciones de [tasks.md](tasks.md). Fechas en 2026-11-10 salvo indicación.

## Línea base (T001)

Rama `feature/LAB-64-langfuse-llm-traces` sin cambios de código, `./mvnw -B -ntp -Dskip.srcclr=true clean verify` en
`backend/`: **660 pruebas, 0 fallos, BUILD SUCCESS**, puerta de cobertura de JaCoCo cumplida; cobertura de líneas
**99,1 %**.

## Recursos locales (T017)

Perfil `llmops` en reposo (sin tráfico), `docker stats --no-stream`:

| Servicio | Memoria |
|---|---|
| langfuse-web | 1,60 GiB |
| langfuse-worker | 0,69 GiB |
| langfuse-clickhouse | 0,38 GiB |
| langfuse-minio | 0,07 GiB |
| langfuse-postgres | 0,05 GiB |
| langfuse-redis | 0,01 GiB |
| **Total** | **≈ 2,8 GiB** |

Máquina de medición: 15,2 GiB visibles para Docker. Los seis servicios quedan `healthy`.

Hallazgos al levantar el perfil (corregidos):
- El puerto de MinIO por defecto (9090) chocaba con Prometheus del perfil `observability`: ahora **9092**.
- Web y worker de Langfuse escuchan en la dirección del contenedor y no en `127.0.0.1`: los healthchecks usan
  `$HOSTNAME` (`/api/public/health` en 3000, `/api/health` en 3030) y `wget` (no hay `curl`).

## US1 — escenarios (T018)

| Escenario | Resultado |
|---|---|
| Solo `core`: ningún servicio `langfuse-*` (`config --services`) | OK, 0 servicios |
| Con `--profile llmops`: seis servicios `langfuse-*`, `config -q` válido | OK |
| Proyecto y claves creados sin alta manual: `GET /api/public/projects` con `pk-lf-shoplab-local`/`sk-lf-shoplab-local` devuelve el proyecto `shoplab-helpdesk` (organización `shoplab`) | OK |
| Tras `restart` de todo el perfil, el proyecto y las claves siguen ahí | OK |
| `--profile llmops --profile observability`: puertos publicados 3000 (Grafana), 3001 (Langfuse), 9090 (Prometheus), 9092 (MinIO) sin choque; `config -q` válido | OK (configuración); Prometheus no se levantó porque el 9090 de esta máquina estaba ocupado por otro proceso |
| `grep ':latest' infra/docker-compose.yml` | Sin coincidencias |

Pendiente de comprobar a mano en el navegador: el inicio de sesión con `admin@shoplab.local` (la creación del proyecto
con `LANGFUSE_INIT_*` prueba que la inicialización se ejecutó).

## US4 — resiliencia del envío (T040–T045)

Pruebas verdes en la suite completa (764, 0 fallos) y `checkstyle:check` con 0 violaciones:

| Caso | Prueba |
|---|---|
| Cabeceras `Authorization: Basic` y `x-langfuse-ingestion-version: 4` | `TraceResilienceIntegrationTests` |
| Langfuse más lento que `export-timeout`: 3 respuestas seguidas en < 4 s | `TraceResilienceIntegrationTests` |
| 401: respuesta completa, contador `shoplab.chat.trace.export.failures`, sin clave ni contenido en el log | `TraceResilienceIntegrationTests` |
| Langfuse falla (503) y vuelve: las trazas nuevas llegan sin reiniciar | `TraceResilienceIntegrationTests` |
| Langfuse inalcanzable: turnos íntegros y un aviso fijo («could not be delivered to Langfuse») | `TraceDeliveryTests` |
| Cola llena: turnos íntegros y `shoplab.chat.trace.dropped` > 0 | `TraceDeliveryTests` |
| Al cerrar el contexto salen los spans pendientes (`schedule-delay=60s`) | `TraceDeliveryTests` |
| Apagado: ni proveedor, ni exportador, ni una sola petición | `TracingConfigLangfuseTests` |
| Claves vacías: arranca con `NoOpChatTrace` y el aviso no incluye valores de claves | `TracingConfigLangfuseTests` |
| Un turno trazado completo bajo BlockHound | `TracingBlockHoundTests` |

Desviaciones respecto a lo previsto:
- `BatchSpanProcessorBuilder.setMeterProvider` existe en la versión 1.62.0 del SDK: **no** hizo falta el plan alternativo de
  T043 (la métrica `processedSpans{dropped=true}` alimenta `shoplab.chat.trace.dropped`).
- El exportador OTLP solo informa éxito o fallo, no el código HTTP: el aviso por lote fallido tiene texto fijo y no
  incluye el código ni el tipo de excepción (salvo si el exportador lanza). El recuento está en
  `shoplab.chat.trace.export.failures`.
- Los casos (a), (e) y (f) de T040 se prueban en `TraceDeliveryTests` llamando a `ChatTracePort` (un contexto de Spring por
  caso sería muy lento); los casos (b), (c), (d) y (g) recorren el chat completo.

## US5 — turnos especiales (T047–T051)

`TraceSpecialTurnsIntegrationTests` (6 casos) y `TraceMidStreamFailureIntegrationTests` (1) verdes: sin contexto
(`no_context`, sin `generation`), cancelado (`cancelled`, `WARNING`, texto parcial), modelo caído (`SLHD_CHAT_00002`,
etapa `generation`), búsqueda fallida (`SLHD_CHAT_00004`, etapa `retrieval`), herramienta con error (etapa `tool`, span
`ERROR`; el adaptador usa `alwaysThrow(true)`, así que el fallo de la herramienta termina el turno), fallo a mitad de
respuesta (`SLHD_CHAT_00003`) y una sola raíz por `requestId`.

## US3 — clúster (T053–T063), repositorio `shoplab-infra`

Rama `feature/LAB-64-langfuse-llm-traces` desde `origin/main` (cambios sin commitear, en el índice).

Hecho: secreto `langfuse` requerido en Terraform (`make fmt-check validate tf-test` verde: 18 + 4 + 2 pruebas),
`k8s/base/langfuse/` (6 cargas + `ExternalSecret`), `ExternalSecret` `backend-langfuse` y su `envFrom`, parches del overlay
`dev` (el `target` del parche de `postgres-password` ahora se limita a `backend-credentials`), recursos iniciales y
`docs/observability.md`. `kustomize build k8s/overlays/dev` renderiza sin errores, sin `Secret` en claro y sin `latest`;
`trivy config` sobre lo renderizado: 0 hallazgos HIGH/CRITICAL; `check-pins.sh` y `check-layout.sh` verdes.

Las seis imágenes arrancan con `readOnlyRootFilesystem`, `capabilities: drop [ALL]` y sin root (probado con `docker run`):
ClickHouse necesita un `emptyDir` en `/etc/clickhouse-server/users.d` y MinIO `HOME=/tmp`. Web y worker solo necesitan `/tmp`;
su arranque completo (Next.js) solo se confirma en el clúster (T064).

**No ejecutado (falta `yq` y `kubeconform` en esta máquina y la descarga de binarios no estuvo permitida):** `make kustomize`
(que valida con `kubeconform`), `policies`, `test-policies`, `prom-rules` y `make check`. Por eso T053 (línea base) y T062
siguen sin marcar. Reglas que afectan a los workloads nuevos: `IMAGE_LATEST` e `IMAGE_NO_TAG` (todas las imágenes llevan
versión o digest) y `PLAIN_SECRET` (no hay recursos `Secret`; solo `ExternalSecret`). Hay que correr
`make kustomize policies test-policies prom-rules trivy` con las herramientas instaladas antes de abrir el PR.

Aviso: `terraform/envs/dev/secrets.auto.tfvars` (local, no versionado) no tiene la entrada `langfuse`, así que el próximo
`make up` fallará la validación hasta que se añada (ver `docs/observability.md` del repositorio de infraestructura).
No lo he tocado.

## Estado de la implementación

Hecho y verde en `shoplab-helpdesk`: T001–T037 (Foundational, US1, US2), T038, T040–T045 (US4) y T047–T051 (US5).
Hecho en `shoplab-infra`: T054–T061 y T063. Pendiente de máquina con Ollama y Langfuse en marcha: T039, T046 y T052
(verificaciones manuales). Pendiente del clúster y de una release publicada: T064–T065. Pendiente: T053/T062 (herramientas),
Polish T066–T072.
