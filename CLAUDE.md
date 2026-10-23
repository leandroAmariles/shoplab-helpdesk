# CLAUDE.md

Guía para Claude Code en este repo. Lee también `README.md` y los ADRs en `docs/adr/`.

## Proyecto

ShopLab Helpdesk: mesa de ayuda con RAG para un e-commerce ficticio. Es un laboratorio de aprendizaje, así que el código debe ser claro y explicar el porqué en los puntos clave (sin comentarios obvios).

- Backlog en Jira, proyecto `LAB` (sitio `amarilespl.atlassian.net`). Las épicas LAB-1 a LAB-8 son las fases F0 a F7.
- Arquitectura: monolito modular (Spring Modulith) en F0 a F3, microservicios con Kafka y CQRS desde F4 (ver ADR 0001).

## Flujo de trabajo

1. Toma la historia del sprint activo en Jira y lee sus criterios de aceptación antes de escribir código.
2. Pasa la historia a "En curso".
3. Crea la rama `feature/LAB-<n>-<descripcion-corta>` desde `main`.
4. Commits pequeños que empiecen por la clave: `LAB-<n> Descripción en imperativo`.
5. Abre un PR usando `.github/pull_request_template.md` y enlaza la historia.
6. Cuando el PR esté abierto, deja un comentario en la historia con el enlace y pásala a "En revisión" si existe ese estado.

No hagas push directo a `main`.

## Definition of Done

- Cumple todos los criterios de aceptación de la historia.
- Compila y pasa los tests en local y en CI.
- Tiene tests para la lógica nueva.
- Documentación actualizada (README, `docs/fases/` o un ADR si hubo una decisión de arquitectura).
- Sin secretos en el repo: usa `.env` (ignorado) y deja un `.env.example`.

## Stack y convenciones

| Área | Stack | Notas |
| --- | --- | --- |
| `backend/` | Java 21+, Spring Boot 4, Spring AI 2.0, Maven | Paquetes por módulo de negocio, no por capa |
| `frontend/` | Angular (standalone components, signals) | Streaming del chat por SSE |
| `ingest/`, `eval/` | Python 3.12, FastAPI, `uv` | RAGAS o DeepEval para evaluación |
| `infra/` | docker compose | PostgreSQL 16 + pgvector |

- Indentación según `.editorconfig` (4 espacios en Java y Python, 2 en el resto).
- Si hay que elegir entre varias librerías o enfoques con impacto, propón un ADR en `docs/adr/` con la plantilla `0000-plantilla.md`.
