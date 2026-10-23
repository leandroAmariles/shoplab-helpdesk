# Project Context

## Project Name

- Name: .

## Stack & Frameworks

- ⚠️ Pending confirmation

## Module Structure

- Top and second-level directories:
  - .ai
  - .ai/context
  - .ai/prompts
  - .ai/rules
  - .ai/skills
  - .ai/templates
  - backend
  - data
  - data/kb
  - docs
  - docs/adr
  - docs/fases
  - eval
  - frontend
  - infra
  - ingest
  - notebooks

## Key Dependencies

- ⚠️ Pending confirmation

## CI/CD

- ⚠️ Pending confirmation

## Conventions

- ⚠️ Pending confirmation: naming conventions
- ⚠️ Pending confirmation: testing strategy
- ⚠️ Pending confirmation: branching and release flow
- ⚠️ Pending confirmation: observability and logging practices

## README Snippet (first 100 lines)

```md
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

El backlog vive en Jira (proyecto `LAB`). Ramas `feature/LAB-123-descripcion` y commits que empiecen por `LAB-123`.

## Arrancar

Se completa en F0 (`LAB` F0-02): `docker compose -f infra/docker-compose.yml up`.
```
