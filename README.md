# ShopLab Helpdesk

Laboratorio paso a paso para aprender LLMs, RAG y chatbots construyendo una mesa de ayuda para una tienda online ficticia.

- **Frontend**: Angular (chat con streaming, consola de agentes, admin).
- **Backend**: Java 21 + Spring Boot 3 + Spring AI (monolito modular que evoluciona a microservicios con Kafka y CQRS).
- **Python**: ingesta, chunking, embeddings y evaluación (RAGAS / DeepEval).
- **Datos**: PostgreSQL 16 + pgvector; dataset público de Olist.

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `frontend/` | App Angular |
| `backend/` | Spring Boot + Spring AI |
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

Prerrequisitos: git, Docker, JDK 21, Node.js y Python 3.

1. Clona el repositorio y entra en la carpeta raíz.
2. Levanta el entorno local con `docker compose -f infra/docker-compose.yml up`.
   **Pendiente:** este archivo se entrega en F0-02 (`LAB`); hasta entonces el comando no funciona.

Los pasos de arranque de cada componente (`backend/`, `frontend/`, `ingest/`) se documentan cuando cada uno tenga código.

## Licencia

MIT, ver [LICENSE](LICENSE).
