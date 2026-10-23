# ADR-0003: Ollama como servidor local de modelos

- Estado: aceptado
- Fecha: 2026-10-23

## Contexto

El laboratorio necesita un modelo de chat y un modelo de embeddings que se ejecuten en la máquina del desarrollador, sin claves de API ni costes, y que se levanten con `docker compose`.

## Decisión

Usar Ollama como servidor local de modelos dentro del perfil `core` de `infra/docker-compose.yml`. Los modelos se descargan en el primer arranque y se configuran por variables (`OLLAMA_CHAT_MODEL`, `OLLAMA_EMBED_MODEL`). Por defecto: `llama3.2:3b` para chat y `bge-m3` (multilingüe, 1024 dimensiones) para embeddings.

## Consecuencias

- El acceso a LLM y embeddings en el backend irá detrás de puertos, de modo que Ollama se pueda sustituir por otro proveedor sin tocar el dominio.
- La dimensión del embedding fija el tamaño de la columna `vector(n)`; cambiar de modelo de embeddings obliga a reindexar.
- Sin GPU el rendimiento es menor; el README recomienda modelos según RAM y GPU.
