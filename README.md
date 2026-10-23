# ShopLab Helpdesk

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

El backlog vive en Jira (proyecto `LAB`). Las decisiones de arquitectura se registran como ADR en `docs/adr/`.

## Convenciones

- Ramas: `<type>/LAB-<n>-<slug>`, donde `type` es `feature`, `fix`, `hotfix`, `chore`, `refactor` o `docs` (por ejemplo `feature/LAB-123-descripcion`).
- Commits: `LAB-<n> type(scope): description` (Conventional Commits con la clave de la historia al inicio).
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

## Licencia

MIT, ver [LICENSE](LICENSE).
