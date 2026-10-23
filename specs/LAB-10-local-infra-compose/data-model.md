# Data Model: Infraestructura local con docker compose

No hay datos de negocio ni esquema de base de datos en esta historia (las tablas y migraciones
llegan con el backend). Las entidades de la spec se materializan como elementos de configuración.

## Servicio de infraestructura

| Servicio | Imagen (variable) | Puerto host (variable, defecto) | Volumen | Healthy cuando |
|---|---|---|---|---|
| `postgres` | `POSTGRES_IMAGE` | `POSTGRES_PORT` (5432) | `pgdata` | acepta conexiones y la extensión `vector` existe |
| `ollama` | `OLLAMA_IMAGE` | `OLLAMA_PORT` (11434) | `ollama_models` | responde y lista `OLLAMA_CHAT_MODEL` y `OLLAMA_EMBED_MODEL` |

Estado: `starting` → `healthy` | `unhealthy`. `ollama` permanece `starting` durante la descarga
inicial (hasta 10 min de `start_period`) y pasa a `unhealthy` si no completa.

## Perfil

| Perfil | Servicios en esta historia | Activación |
|---|---|---|
| `core` | `postgres`, `ollama` | siempre activos (sin `profiles:`); `--profile core` es no-op |
| `llmops` | ninguno (reservado) | `--profile llmops` |
| `observability` | ninguno (reservado) | `--profile observability` |
| `kafka` | ninguno (reservado) | `--profile kafka` |

Regla: activar un perfil reservado no debe producir error ni reiniciar `core`.

## Modelo local

| Campo | Descripción |
|---|---|
| nombre | etiqueta Ollama (por ejemplo `llama3.2:3b`) |
| rol | `chat` o `embeddings` |
| tamaño en disco | informativo para el README |
| dimensión | solo embeddings; define `vector(n)` en historias posteriores |

Relación: un entorno tiene exactamente un modelo `chat` y uno `embeddings` activos
(`OLLAMA_CHAT_MODEL`, `OLLAMA_EMBED_MODEL`).

## Perfil de hardware

Combinación (RAM, GPU/VRAM) → (modelo de chat, modelo de embeddings), tabla en
[research.md](research.md) D4 y en el README. Sin estados.

## Variables de configuración (`infra/.env.example`)

| Variable | Defecto | Uso |
|---|---|---|
| `POSTGRES_IMAGE` | `pgvector/pgvector:0.8.7-pg16` | imagen de la base |
| `POSTGRES_DB` / `POSTGRES_USER` | `shoplab` / `shoplab` | credenciales de desarrollo |
| `POSTGRES_PASSWORD` | `change-me-local-only` | marcador de ejemplo, sin valor real |
| `POSTGRES_PORT` | `5432` | puerto en el host |
| `OLLAMA_IMAGE` | tag fijado al implementar | imagen del servidor |
| `OLLAMA_PORT` | `11434` | puerto en el host |
| `OLLAMA_CHAT_MODEL` | `llama3.2:3b` | modelo de chat |
| `OLLAMA_EMBED_MODEL` | `bge-m3` | modelo de embeddings |
