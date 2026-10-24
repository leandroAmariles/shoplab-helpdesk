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
| `notebooks/` | Experimentos |
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

El backlog vive en Jira (proyecto `LAB`). Las decisiones de arquitectura se registran como ADR en `docs/adr/` (índice y guía en [`docs/adr/README.md`](docs/adr/README.md); se parte de [`docs/adr/0000-plantilla.md`](docs/adr/0000-plantilla.md)).

## Convenciones

- Ramas: `<type>/LAB-<n>-<slug>`, donde `type` es `feature`, `fix`, `hotfix`, `chore`, `refactor` o `docs` (por ejemplo `feature/LAB-123-descripcion`).
- Commits de rama: `LAB-<n> type(scope): description` (Conventional Commits con la clave de la historia al inicio). No se validan: se aplastan al fusionar.
- Título del PR: `type(scope): [LAB-<n>] descripción`, por ejemplo `feat(chat): [LAB-12] respuesta en streaming`. Es el mensaje del commit de squash en `main` y lo valida el check `pr-title`. Tipos permitidos: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert` (un hotfix usa `fix`).
- Archivos con UTF-8, fin de línea LF y sangría con espacios (ver `.editorconfig`).

## Arrancar

Prerrequisitos: git, Docker con Compose v2+, JDK 21 o superior (Maven no hace falta: el backend incluye el wrapper `mvnw`), Node.js y Python 3.

1. Clona el repositorio y entra en la carpeta raíz.
2. (Opcional) Copia `infra/.env.example` a `infra/.env` para cambiar puertos, credenciales o modelos. `infra/.env` no se versiona.
3. Levanta el entorno local: `docker compose -f infra/docker-compose.yml up -d --wait`.

La primera vez descarga las imágenes y los modelos (unos 13 GB en total), así que puede tardar varios minutos. El comando termina cuando PostgreSQL y Ollama están saludables; los siguientes arranques tardan segundos.

### Backend

El backend no necesita la infraestructura levantada para construirse ni arrancar.

```bash
cd backend
./mvnw verify            # compila, ejecuta tests, Checkstyle, PMD y cobertura (en Windows: mvnw.cmd verify)
./mvnw spring-boot:run   # arranca en http://localhost:8080
curl http://localhost:8080/actuator/health
```

Con un JDK inferior a 21 la construcción falla con un mensaje que indica la versión requerida. Los módulos (`chat`, `retrieval`, `tools`, `tickets`) y sus fronteras se verifican en `ModularityTests`; la decisión de plataforma está en `docs/adr/0004-spring-boot-4-spring-ai-2.md`.

Los pasos de arranque de `frontend/` e `ingest/` se documentan cuando cada uno tenga código.

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
| `core` | `postgres` (PostgreSQL 16 + pgvector), `ollama` | Siempre activo |
| `llmops` | — | Reservado |
| `observability` | — | Reservado (F6) |
| `kafka` | — | Reservado (F4) |

Activar un perfil reservado (`--profile <nombre>`) no produce errores; sus servicios llegan en historias posteriores.

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
- **Cambiar de modelo:** define `OLLAMA_CHAT_MODEL` u `OLLAMA_EMBED_MODEL` en `infra/.env` y vuelve a ejecutar `up -d --wait`. El modelo anterior sigue en el disco; se elimina con `down -v`.
- **Puerto ocupado:** cambia `POSTGRES_PORT` u `OLLAMA_PORT` en `infra/.env`.
- **Conexión:** PostgreSQL en `localhost:5432` (usuario y base `shoplab`) y Ollama en `http://localhost:11434`; las credenciales de ejemplo están en `infra/.env.example`.

## Integración continua

El workflow `.github/workflows/ci.yml` corre en cada pull request y en cada push a `main`, y solo construye los componentes cuyos archivos cambiaron (`backend/`, `frontend/`, `ingest/`, `eval/`).

- **Backend:** Temurin 21, `./mvnw clean verify` (Checkstyle, PMD, prueba de Modulith, pruebas de integración y gate de cobertura de líneas ≥ 80 %). El reporte de JaCoCo se sube como artefacto `jacoco-report`, incluso si el job falla.
- **Qodana (backend):** el job `qodana` analiza `backend/` con el linter JVM Community (`backend/qodana.yaml`) y falla si aparecen problemas **nuevos** de severidad alta o crítica respecto al baseline `backend/qodana.sarif.json`. Los hallazgos se ven en *Security > Code scanning*, en el comentario del PR y en el artefacto `qodana-report`; en PRs desde forks solo queda el artefacto. No requiere `QODANA_TOKEN`.
  - *Reproducir en local:* `docker run --rm -v "$PWD/backend:/data/project" -v "$TMPDIR/qodana:/data/results" jetbrains/qodana-jvm-community:2026.2 --baseline qodana.sarif.json --save-report=false`.
  - *Regenerar el baseline:* ejecuta el análisis en `main` sin `--baseline`, reduce `qodana.sarif.json` a los resultados y confírmalo en un PR propio.
  - *Falso positivo:* suprímelo con `exclude` en `backend/qodana.yaml` o con la anotación en el código, revisado en el PR; nunca desactives el gate.
- **Seguridad (Trivy):** el job `trivy` escanea todo el repo (dependencias Maven/npm/Python, secretos y configuración) en cada PR y en cada push a `main`, y falla ante hallazgos HIGH o CRITICAL con corrección disponible. Los hallazgos se ven en *Security > Code scanning* (categoría `trivy`) y en el artefacto `trivy-report`; en PRs de forks o de Dependabot solo queda el artefacto. Dependabot abre PRs semanales de actualización (`.github/dependabot.yml`).
  - *Reproducir en local:* `trivy fs . --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --ignore-unfixed --skip-dirs node_modules,target,.venv --ignorefile .trivyignore --exit-code 1`.
  - *Excepción:* añade el id al `.trivyignore` precedido de `Motivo:` y `Responsable: ... | Revisar antes de: AAAA-MM-DD`; nunca sin justificación.
- **Frontend y Python:** lint, pruebas y build (Angular) y ruff + pytest (`ingest`, `eval`). Si el componente aún no tiene proyecto, el job termina en verde con un aviso.
- **Reproducir en local:** `cd backend && ./mvnw -B -ntp clean verify`.

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
