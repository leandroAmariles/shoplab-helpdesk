# ShopLab Helpdesk

[![CI](https://github.com/leandroAmariles/shoplab-helpdesk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/leandroAmariles/shoplab-helpdesk/actions/workflows/ci.yml)

Laboratorio paso a paso para aprender LLMs, RAG y chatbots construyendo una mesa de ayuda para una tienda online ficticia.

- **Frontend**: Angular (chat con streaming, consola de agentes, admin).
- **Backend**: Java 21 + Spring Boot 4 + Spring AI 2.0 + Spring Modulith (monolito modular que evoluciona a microservicios con Kafka y CQRS).
- **Python**: ingesta, chunking, embeddings y evaluación (RAGAS / DeepEval).
- **Datos**: PostgreSQL 16 + pgvector; dataset público de Olist.

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `frontend/` | App Angular |
| `backend/` | Spring Boot 4 + Spring Modulith + Spring AI 2.0 |
| `ingest/` | Servicio FastAPI de ingesta |
| `eval/` | Dataset golden y evaluación |
| `notebooks/` | Experimentos: [comparativo de estrategias de chunking](notebooks/README.md) |
| `ml/` | ML y MLOps (F8): [análisis exploratorio de Olist](ml/README.md), [dataset de intenciones](ml/README.md#dataset-de-intenciones-del-cliente-lab-117) y [clasificador de intención con scikit-learn](ml/README.md#clasificador-de-intención-con-scikit-learn-lab-118) |
| `data/` | Políticas, FAQs y scripts de carga |
| `infra/` | docker compose, Keycloak, observabilidad |
| `docs/` | Guías por fase y ADRs |

## Fases

| Fase | Objetivo |
| --- | --- |
| F0 | Fundaciones: chat con streaming de punta a punta |
| F1 | RAG básico con citas |
| F2 | Evaluación automática en cada PR |
| F3 | Retrieval avanzado (híbrido, reranking) |
| F4 | Agente con tool calling + Kafka en la ingesta |
| F5 | Microservicios con CQRS y event sourcing |
| F6 | Plataforma: gateway, resiliencia, observabilidad, seguridad |
| F7 | Mejora continua y despliegue en Kubernetes |

El backlog vive en Jira (proyecto `LAB`). Las decisiones de arquitectura se registran como ADR en `docs/adr/` (índice y guía en [`docs/adr/README.md`](docs/adr/README.md); se parte de [`docs/adr/0000-plantilla.md`](docs/adr/0000-plantilla.md)). El mapa de servicios de destino tras la extracción temprana se detalla en [`docs/mapa-servicios.md`](docs/mapa-servicios.md) ([ADR-0031](docs/adr/0031-extraccion-temprana-de-servicios.md)).

## Convenciones

- Ramas: `<type>/LAB-<n>-<slug>`, donde `type` es `feature`, `fix`, `hotfix`, `chore`, `refactor` o `docs` (por ejemplo `feature/LAB-123-descripcion`).
- Commits de rama: `LAB-<n> type(scope): description` (Conventional Commits con la clave de la historia al inicio). No se validan: se aplastan al fusionar.
- Título del PR: `type(scope): [LAB-<n>] descripción`, por ejemplo `feat(chat): [LAB-12] respuesta en streaming`. Es el mensaje del commit de squash en `main` y lo valida el check `pr-title`. Tipos permitidos: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert` (un hotfix usa `fix`).
- Archivos con UTF-8, fin de línea LF y sangría con espacios (ver `.editorconfig`).
- Definition of Done de servicios: desde F4, cada servicio nuevo incluye su base y su entrada en el overlay `dev` (el único ambiente) en `shoplab-infra/k8s/`, siguiendo `docs/kustomize.md` de ese repositorio.

### Versionado y releases

Las versiones y el `CHANGELOG.md` salen solos de los títulos de PR (el commit de squash en `main`) con [release-please](https://github.com/googleapis/release-please). Solo el **título del PR** cuenta; los commits de rama no se leen.

| Tipo del título | Efecto en la versión |
| --- | --- |
| `feat` | sube la minor |
| `fix`, `perf` | sube la patch |
| `!` tras el tipo (`feat(api)!: [LAB-9] ...`) o `BREAKING CHANGE:` | sube la major (la minor mientras la versión sea `0.x`) |
| `docs`, `chore`, `test`, `ci`, `build`, `refactor` | no generan release |

- **Flujo:** cada push a `main` abre o actualiza un único PR de release, `chore(main): [LAB-100] release <versión>`, con la versión nueva (`backend/pom.xml`) y el `CHANGELOG.md`. Pasa por los mismos checks (`ci-gate`, `pr-title`) y se fusiona con squash.
- **Al fusionarlo** se crean el tag `vX.Y.Z` y el GitHub Release con las notas del changelog; el tag dispara la imagen versionada (LAB-78).
- **No edites a mano** `CHANGELOG.md`, `.release-please-manifest.json` ni la versión del proyecto.
- **Configuración:** `release-please-config.json`, `.release-please-manifest.json` y `.github/workflows/release-please.yml`. Se usa una sola versión del repositorio; `frontend` e `ingest` se añaden a `extra-files` cuando tengan `package.json` / `pyproject.toml`.
- **Token:** el workflow usa el secreto `RELEASE_PLEASE_TOKEN` (token de grano fino, solo este repositorio, `Contents` y `Pull requests` en lectura/escritura) porque los PRs y tags creados con `GITHUB_TOKEN` no disparan otros workflows. Anota aquí su caducidad y rótalo antes: **caduca el AAAA-MM-DD** (pendiente de completar al crearlo).

## Arrancar

Prerrequisitos: git, Docker con Compose v2+, JDK 21 o superior (Maven no hace falta: el backend incluye el wrapper `mvnw`), Node.js y Python 3.

1. Clona el repositorio y entra en la carpeta raíz.
2. (Opcional) Copia `infra/.env.example` a `infra/.env` para cambiar puertos, credenciales o modelos. `infra/.env` no se versiona.
3. Levanta el entorno local: `docker compose -f infra/docker-compose.yml up -d --wait`.

La primera vez descarga las imágenes y los modelos (unos 13 GB en total), así que puede tardar varios minutos. El comando termina cuando PostgreSQL y Ollama están saludables; los siguientes arranques tardan segundos.

### Backend

El backend **necesita PostgreSQL para arrancar**: al iniciar aplica las migraciones de cada módulo y, si no hay base de datos, falla indicando el módulo afectado. Levanta solo la base con `docker compose -f infra/docker-compose.yml up -d postgres` (el backend usa por defecto `localhost:5432/shoplab`; se cambia con `POSTGRES_URL`, `POSTGRES_USER` y `POSTGRES_PASSWORD`). Las pruebas (`./mvnw verify`) arrancan su propia PostgreSQL desechable con Testcontainers, así que solo necesitan Docker en marcha.

```bash
cd backend
./mvnw verify            # compila, ejecuta tests, Checkstyle, PMD y cobertura (en Windows: mvnw.cmd verify)
./mvnw spring-boot:run   # arranca en http://localhost:8080
curl http://localhost:8080/actuator/health
```

Con un JDK inferior a 21 la construcción falla con un mensaje que indica la versión requerida. Los módulos por contexto de negocio (`chat`, `retrieval`, `orders`, `catalog`, `tickets`, cada uno dueño de su esquema de datos, y el módulo abierto `shared` con los errores comunes, ver `docs/adr/0010-modulo-shared-errores.md`) y sus fronteras se verifican en `ModularityTests` y `ModuleArchitectureTests`; la guía está en [docs/modulos.md](docs/modulos.md) y la decisión en `docs/adr/0015-limites-de-contexto-y-repositorios.md`; la decisión de plataforma está en `docs/adr/0004-spring-boot-4-spring-ai-2.md`. El chat en streaming (`POST /api/chat`) se prueba como se explica en [docs/chat-streaming.md](docs/chat-streaming.md).

### Frontend (Angular)

Requiere Node `22.22.3` o superior de la línea 22 (`frontend/.nvmrc`; con `nvm use` se selecciona). `npm ci` falla con un mensaje claro si la versión no cumple (`engine-strict`).

```bash
cd frontend
npm ci
npm start                  # http://localhost:4200, redirige /api a http://localhost:8080
npm test -- --watch=false  # Vitest + jsdom, sin navegador instalado
npm run lint
npm run build              # genera frontend/dist/browser
```

La app usa componentes standalone, signals y Angular Material (ver `docs/adr/0013-biblioteca-ui-angular-material.md`). El proxy de desarrollo (`frontend/proxy.conf.json`) evita configurar CORS: levanta el backend (`./mvnw spring-boot:run`) y llama a rutas relativas `/api/...`. Para apuntar a otro backend basta con cambiar el `target` de ese archivo.

**Chat (`/chat`)**: con el backend en `localhost:8080` y el modelo cargado, `npm start` en `frontend/` y abre `http://localhost:4200/chat`. La pantalla envía `POST /api/chat` y lee la respuesta como SSE con `fetch` (el navegador no permite `EventSource` con `POST`; ver `docs/adr/0014-markdown-seguro-en-el-chat.md`). Las respuestas del asistente se muestran como Markdown saneado: **solo** `core/markdown/markdown-renderer.ts` puede confiar en HTML (`bypassSecurityTrustHtml`), y ESLint falla si otro archivo lo intenta. La conversación visible vive en memoria de la pestaña y se pierde al recargar; el servidor recuerda cada conversación por un UUID que genera la pantalla (`docs/adr/0020-memoria-de-conversacion-en-tabla-propia.md`), y «Nueva conversación» empieza otra. Límite conocido: la imagen de contenedor aún no enruta `/api` al backend (lo resuelve el ingreso del clúster), así que el chat se prueba en desarrollo con el proxy.

Los pasos de arranque de `ingest/` se documentan cuando tenga código.

### Datos de ejemplo (Olist)

Con la infraestructura local levantada y el backend arrancado (crea las tablas de `orders` y `catalog`), carga el dataset
público de Olist con `cd data/olist && uv sync && uv run olist-loader load`. Reparto de tablas, stock sintético y
licencia en [`docs/olist-dataset.md`](docs/olist-dataset.md).

### Base de conocimiento (políticas y FAQs)

El corpus de políticas y preguntas frecuentes de la tienda está en `data/kb/` (24 documentos en Markdown, 10 también en
PDF). Cómo se organiza, cómo añadir una política y cómo regenerar los PDF en
[`docs/base-conocimiento.md`](docs/base-conocimiento.md).

## Infraestructura local

### Requisitos de hardware

| Recurso | Mínimo | Recomendado |
| --- | --- | --- |
| RAM | 8 GB | 16 GB o más |
| Disco libre | 15 GB | 25 GB (varios modelos) |
| GPU | No necesaria | NVIDIA con 8 GB de VRAM o más |

Sin GPU todo funciona solo con CPU, con respuestas del chat más lentas. Docker debe tener asignada al menos la RAM del modelo elegido (en Docker Desktop: Settings → Resources).

### Modelos recomendados

| Equipo | Chat (`OLLAMA_CHAT_MODEL`) | Embeddings (`OLLAMA_EMBED_MODEL`) |
| --- | --- | --- |
| 8 GB de RAM, sin GPU | `llama3.2:1b` (1,3 GB) | `nomic-embed-text` (274 MB, 768 dim) |
| 16 GB de RAM, sin GPU o GPU con menos de 6 GB | `llama3.2:3b` (2,0 GB, por defecto) | `bge-m3` (~1,2 GB, 1024 dim, por defecto) |
| 32 GB de RAM o GPU de 8-12 GB | `qwen2.5:7b` o `llama3.1:8b` | `bge-m3` |
| GPU de 16 GB o más | `qwen2.5:14b` | `bge-m3` |

La dimensión del embedding fija el tamaño de la columna `vector(n)` en las historias siguientes. Cambiar de modelo de embeddings obliga a reindexar los datos.

### Perfiles

| Perfil | Servicios | Estado |
| --- | --- | --- |
| `core` | `postgres` (PostgreSQL 16 + pgvector), `ollama` (sin puerto publicado), `litellm` (gateway de modelos, puerto 4000), `litellm-postgres` y `litellm-bootstrap` (crea las claves virtuales y termina), `ingest` | Siempre activo |
| `llmops` | `langfuse-web`, `langfuse-worker`, `langfuse-postgres`, `langfuse-clickhouse`, `langfuse-redis`, `langfuse-minio` (trazas de LLM, LAB-64) | Opcional: `--profile llmops` |
| `observability` | `prometheus`, `grafana` (métricas del backend y dashboards, LAB-102) | Opcional: `--profile observability` |
| `kafka` | — | Reservado (F4) |

Activar un perfil reservado (`--profile <nombre>`) no produce errores; sus servicios llegan en historias posteriores. Los perfiles `observability` y `llmops` ya tienen servicios (ver más abajo).

### Operar el entorno

Todos los comandos se ejecutan desde la raíz del repositorio.

| Acción | Comando |
| --- | --- |
| Levantar `core` | `docker compose -f infra/docker-compose.yml up -d --wait` |
| Con un perfil extra | `docker compose -f infra/docker-compose.yml --profile kafka up -d --wait` |
| Con GPU NVIDIA | `docker compose -f infra/docker-compose.yml -f infra/docker-compose.gpu.yml up -d --wait` |
| Ver estado de salud | `docker compose -f infra/docker-compose.yml ps` |
| Detener (conserva datos y modelos) | `docker compose -f infra/docker-compose.yml down` |
| Reiniciar desde cero | `docker compose -f infra/docker-compose.yml down -v` |

- **Salud:** `ollama` solo pasa a `healthy` cuando el modelo de chat y el de embeddings están descargados; `postgres`, cuando acepta conexiones y la extensión `vector` existe.
- **Cambiar de modelo:** define `LITELLM_CHAT_PRIMARY_MODEL` o `LITELLM_EMBED_MODEL` (o `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBED_MODEL` si hay que descargarlos) en `infra/.env` y ejecuta `up -d litellm`; el backend no se reinicia. El modelo anterior sigue en el disco; se elimina con `down -v`. Ver [`docs/gateway-de-modelos.md`](docs/gateway-de-modelos.md).
- **Puerto ocupado:** cambia `POSTGRES_PORT`, `LITELLM_PORT`, `PROMETHEUS_PORT` o `GRAFANA_PORT` en `infra/.env`.
- **Conexión:** PostgreSQL en `localhost:5432` (usuario y base `shoplab`) y el gateway de modelos en `http://localhost:4000/v1` (el backend habla solo con él, con su clave virtual; **Ollama ya no publica puerto**: solo lo alcanza el gateway, y para depurarlo se añade `-f infra/docker-compose.ollama-host.yml`); las credenciales de ejemplo están en `infra/.env.example`.
- **Gateway de modelos (LAB-84):** fallback entre dos modelos locales (`OLLAMA_FALLBACK_MODEL`), presupuesto por clave virtual, coste y tokens por conversación en los logs del gateway y un proveedor cloud opcional (desactivado sin clave). Guía: [`docs/gateway-de-modelos.md`](docs/gateway-de-modelos.md) y [ADR-0023](docs/adr/0023-gateway-de-modelos-litellm.md).

### Langfuse (perfil `llmops`)

`docker compose -f infra/docker-compose.yml --profile llmops up -d --wait` levanta Langfuse v4 autoalojado con sus almacenes (PostgreSQL, ClickHouse, Redis y MinIO) para inspeccionar cada turno del chat. La interfaz queda en `http://localhost:3001` (el 3000 es de Grafana; se cambia con `LANGFUSE_PORT`). Arranca ya inicializado: organización, proyecto `shoplab-helpdesk`, usuario `admin@shoplab.local` (contraseña `LANGFUSE_INIT_USER_PASSWORD`, por defecto `change-me-local-only`, solo válida en local) y las claves `pk-lf-shoplab-local` / `sk-lf-shoplab-local`. Es opcional y pesado: en reposo usa unos 3 GB de RAM (la web de Langfuse ~1,6 GB, el worker ~0,7 GB, ClickHouse ~0,4 GB); medición en `specs/LAB-64-langfuse-llm-traces/validation.md`. Sin el perfil no arranca ninguno de sus contenedores. Los datos viven en volúmenes con nombre (`langfuse_*`) y sobreviven a reinicios. Para que el backend envíe trazas, ver «Trazas de LLM».

### Observabilidad local (perfil `observability`)

`docker compose -f infra/docker-compose.yml --profile observability up -d --wait` levanta Prometheus (`http://localhost:9090`) y Grafana (`http://localhost:3000`, usuario `admin`, contraseña `GRAFANA_ADMIN_PASSWORD`, por defecto `change-me-local-only` y solo válida en local). Prometheus recoge `/actuator/prometheus` del backend que corre en el host en el puerto 8080; Grafana arranca con el origen de datos y los dashboards de `infra/observability/`. Sin el perfil no arranca nada de esto. Detalle, dashboards, alerta y cómo borrar solo sus datos: [docs/observabilidad.md](docs/observabilidad.md).

### Trazas de LLM

Cada turno del chat puede dejar una traza en Langfuse (LAB-64, ADR-0022). **Está apagado por defecto**: sin la variable no se crea ningún exportador ni se abre ninguna conexión.

**Activarlo en local** (con el perfil `llmops` levantado):

```bash
SHOPLAB_CHAT_TRACING_ENABLED=true SHOPLAB_CHAT_TRACING_PUBLIC_KEY=pk-lf-shoplab-local SHOPLAB_CHAT_TRACING_SECRET_KEY=sk-lf-shoplab-local ./mvnw spring-boot:run
```

Las demás variables (`SHOPLAB_CHAT_TRACING_ENDPOINT`, `_ENVIRONMENT`, `_MAX_CONTENT_CHARACTERS`, `_QUEUE_SIZE`, `_BATCH_SIZE`, `_SCHEDULE_DELAY`, `_EXPORT_TIMEOUT`, `_SHUTDOWN_TIMEOUT`) tienen valores por defecto en `backend/src/main/resources/application.yml`. Con `enabled=true` y las claves vacías el backend arranca igualmente, avisa en el log (sin valores) y no envía nada.

**Cómo leer una traza.** En `http://localhost:3001`, proyecto `shoplab-helpdesk`, cada turno es una traza `chat-turn` con:

| Paso | Qué muestra |
|---|---|
| `chat-turn` (raíz) | la pregunta, la respuesta, `result` (`completed`, `no_context`, `cancelled` o `error`), el `X-Request-Id`, los turnos de memoria usados y el tiempo hasta el primer token; en un error, `error_code` y `failed_stage`. La conversación se agrupa en una sesión por su `conversationId` |
| `retrieval` | el texto buscado y los fragmentos entregados al modelo con fuente, título, sección y **puntuación**, más cuántos candidatos hubo |
| `generation` | el modelo y sus parámetros, el prompt completo (`system`, historial y pregunta con contexto), la respuesta, los tokens de entrada y salida, el coste y el tiempo hasta el primer token |
| `tool:<nombre>` | por cada herramienta que el modelo llamó: argumentos, resultado y duración |

Un uso de tokens que el modelo no informó aparece como desconocido, nunca como cero. El nombre exacto de cada atributo está en `specs/LAB-64-langfuse-llm-traces/contracts/trace-schema.md`.

**Precios.** El coste sale de la tabla `shoplab.chat.tracing.pricing` (USD por millón de tokens, por nombre exacto de modelo y, si no, por proveedor). Los modelos locales de Ollama valen 0. Para un modelo de pago añade una entrada, por ejemplo:

```yaml
shoplab.chat.tracing.pricing.claude-sonnet-5-5:
  input-per-million: 3.0
  output-per-million: 15.0
```

Un modelo sin precio no tiene coste en su traza (no se inventa).

**Encontrar la traza de una petición.** El identificador de la traza es `SHA-256(X-Request-Id)` en hexadecimal, sus primeros 32 caracteres:

```bash
printf '%s' "<X-Request-Id>" | sha256sum | cut -c1-32
```

Con ese id, la interfaz abre `http://localhost:3001/project/shoplab-helpdesk/traces/<traceId>`.

> **Datos.** Las trazas contienen la conversación completa (preguntas, respuestas, fragmentos y prompt). Úsalas solo con datos públicos o de prueba: la base de conocimiento actual lo es. Si activas el envío con datos reales, Langfuse los guarda.

**Ambiente DEV.** El backend de dev envía las trazas al Langfuse del clúster y este no tiene Ingress; se abre con `kubectl -n shoplab-dev port-forward svc/langfuse-web 3001:3000` y luego `http://localhost:3001` (detalles y secretos en `docs/observability.md` del repositorio `shoplab-infra`).

### Prompts del chat (LAB-85)

Los cuatro textos que el chat envía al modelo (prompt de sistema, de cortesía y las dos plantillas del mensaje del cliente) se
editan en **Langfuse**, sin tocar código ni redesplegar (ADR-0027). **Está apagado por defecto**: sin la variable el chat usa
el texto por defecto de `backend/src/main/resources/prompts/*.txt`. En local, con el perfil `llmops` levantado:

```bash
SHOPLAB_CHAT_PROMPTS_ENABLED=true SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true SHOPLAB_CHAT_PROMPTS_REFRESH_INTERVAL=10s \
SHOPLAB_CHAT_PROMPTS_PUBLIC_KEY=pk-lf-shoplab-local SHOPLAB_CHAT_PROMPTS_SECRET_KEY=sk-lf-shoplab-local ./mvnw spring-boot:run
```

- **Primera vez:** con `PUBLISH_MISSING=true` el backend crea los prompts que no existen (versión 1, etiqueta `production`, el texto
  por defecto) al arrancar; repetirlo no crea nada. Aparecen en `http://localhost:3001` → Prompts: `chat-system`,
  `chat-small-talk`, `chat-context` y `chat-empty-context`.
- **Cambiar un prompt:** crea una versión nueva en Langfuse y mueve la etiqueta `production` a ella; en ≤ `REFRESH_INTERVAL`
  (60 s por omisión) el chat la usa, sin reiniciar. Para revertir, mueve la etiqueta de vuelta.
- **Si Langfuse falla** el chat sigue con la última versión válida y, si no hay, con el texto por defecto: nunca devuelve error por esto.
- **Medir antes de promover:** `quality --prompt` y `compare` de `eval/` (ver `eval/README.md`); necesitan
  `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` en el backend y `SHOPLAB_EVAL_PROMPT_TOKEN` en `eval/`.
- Las variables `SHOPLAB_CHAT_SYSTEM_PROMPT` y `SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT` ya no se leen. Todas las variables, la guía de
  editar, probar, promover y revertir y lo que dice cada traza están en [`docs/chat-streaming.md`](docs/chat-streaming.md).

## Integración continua

El workflow `.github/workflows/ci.yml` corre en cada pull request y en cada push a `main`, y solo construye los componentes cuyos archivos cambiaron (`backend/`, `frontend/`, `ingest/`, `eval/`, `notebooks/`, `ml/`).

- **Backend:** Temurin 21, `./mvnw clean verify` (Checkstyle, PMD, prueba de Modulith, pruebas de integración con PostgreSQL desechable vía Testcontainers y gate de cobertura de líneas ≥ 80 %). Después comprueba que `docs/modulos/` (generada por `ModularityTests`) coincide con lo versionado. El reporte de JaCoCo se sube como artefacto `jacoco-report`, incluso si el job falla.
- **Qodana (backend):** el job `qodana` analiza `backend/` con el linter JVM Community (`backend/qodana.yaml`) y falla si aparecen problemas **nuevos** de severidad alta o crítica respecto al baseline `backend/qodana.sarif.json`. Los hallazgos se ven en *Security > Code scanning*, en el comentario del PR y en el artefacto `qodana-report`; en PRs desde forks solo queda el artefacto. No requiere `QODANA_TOKEN`.
  - *Reproducir en local:* `docker run --rm -v "$PWD/backend:/data/project" -v "$TMPDIR/qodana:/data/results" jetbrains/qodana-jvm-community:2026.2 --baseline qodana.sarif.json --save-report=false`.
  - *Regenerar el baseline:* ejecuta el análisis en `main` sin `--baseline`, reduce `qodana.sarif.json` a los resultados y confírmalo en un PR propio.
  - *Falso positivo:* suprímelo con `exclude` en `backend/qodana.yaml` o con la anotación en el código, revisado en el PR; nunca desactives el gate.
- **Seguridad (Trivy):** el job `trivy` escanea todo el repo (dependencias Maven/npm/Python, secretos y configuración) en cada PR y en cada push a `main`, y falla ante hallazgos HIGH o CRITICAL con corrección disponible. Los hallazgos se ven en *Security > Code scanning* (categoría `trivy`) y en el artefacto `trivy-report`; en PRs de forks o de Dependabot solo queda el artefacto. Dependabot abre PRs semanales de actualización (`.github/dependabot.yml`).
  - *Reproducir en local:* `trivy fs . --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --ignore-unfixed --skip-dirs node_modules,target,.venv --ignorefile .trivyignore --exit-code 1`.
  - *Excepción:* añade el id al `.trivyignore` precedido de `Motivo:` y `Responsable: ... | Revisar antes de: AAAA-MM-DD`; nunca sin justificación.
- **Frontend y Python:** lint, pruebas y build (Angular) y ruff + pytest (`ingest`, `eval`, `notebooks`, `ml`). Si el componente aún no tiene proyecto, el job termina en verde con un aviso.
- **Notebook de `ml/`:** el job `ml-notebook` levanta un Postgres, aplica las migraciones de `orders` y `catalog`, carga las fixtures del cargador de Olist y ejecuta el notebook con `nbconvert --execute`; falla si una celda da error. Se ejecuta con una muestra de 3 pedidos (el dataset completo no se descarga en CI). Detalles en [`ml/README.md`](ml/README.md).
- **Reproducir en local:** `cd backend && ./mvnw -B -ntp clean verify`.

### Imágenes Docker

El workflow `.github/workflows/images.yml` construye una imagen por servicio (`backend`, `frontend`, `ingest`; Dockerfile multi-stage y usuario no root en cada carpeta) y las publica en `ghcr.io/leandroamariles/<servicio>`. En pull requests que tocan esos directorios solo construye y escanea; no publica. `ingest` usa una página de relevo hasta que exista su código; `frontend` ya empaqueta la app Angular compilada sobre nginx (con fallback a `index.html` para las rutas internas).

- **Tags:** `<sha completo>` y `latest` en cada merge a `main`; `vX.Y.Z` cuando release-please crea el tag (nunca mueve `latest`). Un tag de versión ya publicado no se sobrescribe.
- **Escaneo previo (Trivy):** cada imagen se escanea antes de publicar (HIGH y CRITICAL con corrección disponible, mismo `.trivyignore` que el escaneo del repo). Si falla alguna de las tres, no se publica ninguna. El reporte SARIF queda en el artefacto `trivy-image-<servicio>`.
- **SBOM y provenance:** adjuntos a cada imagen como atestaciones de BuildKit.
- **Firma:** cosign *keyless* sobre el digest, sin claves en el repo. Verificar:

```bash
cosign verify ghcr.io/leandroamariles/backend:latest \
  --certificate-identity-regexp '^https://github.com/leandroAmariles/shoplab-helpdesk/\.github/workflows/images\.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

- **Un CVE de la imagen base bloquea el pipeline:** actualiza la base (Dependabot abre PRs semanales con el digest nuevo) o, si es un falso positivo, añade el id al `.trivyignore` con motivo y responsable.
- *Reproducir el escaneo en local:* `docker build -t shoplab/backend:local backend && trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --ignorefile .trivyignore --exit-code 1 shoplab/backend:local`.

### Protección de main

Nada llega a `main` sin pasar por un pull request y por todos los checks. El ruleset `main-protect` (definición versionada en [`.github/rulesets/main-protection.json`](.github/rulesets/main-protection.json)) aplica a todos, propietario incluido (sin bypass):

- **Flujo:** rama `<type>/LAB-<n>-<slug>` → pull request → checks en verde → *Squash and merge*.
- **Sin push directo, force push ni borrado** de `main`; historial lineal; único método de fusión: squash, con el título del PR como mensaje del commit.
- **Rama al día:** si `main` avanzó, actualiza la rama (*Update branch*) y espera de nuevo los checks.
- **Checks requeridos:**
  - `ci-gate`: agrega `backend` (build, tests y gate de cobertura), `qodana`, `trivy`, `frontend` y `python`; un componente omitido por el filtro de rutas no bloquea.
  - `pr-title`: valida el título del PR (`type(scope): [LAB-<n>] descripción`). Si falla, edita el título: se reevalúa en segundos, sin nuevo commit.
- **Dependabot:** sus PRs (`chore(deps): ...`) solo validan el tipo, porque no pueden llevar clave LAB.
- **Renombrar un check:** cambiar `ci-gate` o `pr-title` exige actualizar el ruleset en el mismo PR; si no, los PRs quedan esperando un check que nunca llega.

*Aplicar o restaurar* (requiere ser administrador; el ruleset se activa después de fusionar la historia que introduce `pr-title`):

```bash
REPO=leandroAmariles/shoplab-helpdesk
ID=$(gh api repos/$REPO/rulesets --jq '.[] | select(.name=="main-protect") | .id')
gh api -X PUT repos/$REPO/rulesets/$ID --input .github/rulesets/main-protection.json   # si no existe: -X POST repos/$REPO/rulesets
gh api -X PATCH repos/$REPO -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true -f squash_merge_commit_title=PR_TITLE -f squash_merge_commit_message=BLANK
```

*Auditar* (sin salida si coincide):

```bash
F='{name,target,enforcement,bypass_actors,conditions,rules}'
gh api repos/$REPO/rulesets/$ID | jq -S "$F" | diff - <(jq -S "$F" .github/rulesets/main-protection.json)
```

## Licencia

MIT, ver [LICENSE](LICENSE).
