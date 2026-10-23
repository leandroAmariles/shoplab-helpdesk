# Research: Infraestructura local con docker compose

No quedaban `NEEDS CLARIFICATION` en el Technical Context. Las decisiones cierran los supuestos
que la spec difirió a la planificación. Datos de catálogo verificados el 2026-10-23 (Docker Hub y
ollama.com); los demás tamaños son aproximados y deben reconfirmarse al implementar.

## D1. Imagen de PostgreSQL + pgvector

- **Decision**: `pgvector/pgvector` con tag `pg16`, expuesto como variable `POSTGRES_IMAGE`
  (por defecto `pgvector/pgvector:0.8.7-pg16`). La extensión se crea en un script de
  `/docker-entrypoint-initdb.d`.
- **Rationale**: imagen oficial del proyecto pgvector sobre Postgres 16 (el tag `0.8.7-pg16` existe
  en Docker Hub); fijar la versión menor hace el entorno reproducible. HNSW está soportado desde
  pgvector 0.5.
- **Alternatives considered**: construir imagen propia desde `postgres:16` (más mantenimiento);
  tag flotante `pg16` (se mueve sin aviso).

## D2. Servidor de modelos y versión de Ollama

- **Decision**: imagen `ollama/ollama` con tag fijado por variable `OLLAMA_IMAGE` (versión concreta
  se escribe al implementar tras consultar el tag vigente). Puerto 11434.
- **Rationale**: es lo que pide el criterio de aceptación; funciona en CPU y con GPU.
- **Alternatives considered**: `latest` (no reproducible); llama.cpp server / vLLM (fuera de lo
  pedido y más complejos de operar localmente).

## D3. Descarga automática de modelos y significado de "saludable"

- **Decision**: un único servicio `ollama` con `entrypoint.sh`: arranca `ollama serve` en segundo
  plano, espera a que responda, ejecuta `ollama pull` de `OLLAMA_CHAT_MODEL` y
  `OLLAMA_EMBED_MODEL` si faltan y mantiene el servidor en primer plano. El healthcheck solo pasa
  cuando ambos modelos aparecen en `ollama list`; `start_period` largo para cubrir la primera
  descarga.
- **Rationale**: "saludable" equivale a "listo para usar", y el backend futuro puede usar
  `depends_on: condition: service_healthy` (FR-005, FR-006). Evita un segundo contenedor.
- **Alternatives considered**: servicio one-shot `ollama-pull` (queda en estado "exited", no es
  "saludable", y el healthcheck de Ollama no garantiza modelos); descarga manual (incumple FR-003).

## D4. Modelos por defecto y tabla por RAM/GPU

- **Decision**: por defecto chat `llama3.2:3b` (2,0 GB en disco, verificado) y embeddings `bge-m3`
  (~1,2 GB, multilingüe, 1024 dimensiones). Todo configurable por variables (FR-009).

  | Perfil de hardware | Chat | Embeddings |
  |---|---|---|
  | 8 GB RAM, sin GPU | `llama3.2:1b` (1,3 GB) | `nomic-embed-text` (274 MB, 768 dim) |
  | 16 GB RAM, sin GPU o GPU < 6 GB VRAM | `llama3.2:3b` | `bge-m3` |
  | 32 GB RAM o GPU 8-12 GB VRAM | `qwen2.5:7b` o `llama3.1:8b` | `bge-m3` |
  | GPU ≥ 16 GB VRAM | `qwen2.5:14b` | `bge-m3` |

- **Rationale**: el contenido (políticas y FAQs) estará en español y el dataset Olist trae texto
  en portugués; `bge-m3` es multilingüe, `nomic-embed-text` es sobre todo inglés y queda como
  opción ligera. 3B corre en CPU con 16 GB de forma usable.
- **Consecuencia importante**: la dimensión del embedding fija el tamaño de la columna `vector(n)`
  en historias posteriores (1024 con el valor por defecto; 768 con `nomic-embed-text`). Cambiar de
  modelo de embeddings obliga a reindexar. Se documenta en el README.
- **Alternatives considered**: `nomic-embed-text` por defecto (más liviano pero peor en español);
  modelo de chat de 7B por defecto (excluye equipos de 8-16 GB).

## D5. Perfiles y arranque por defecto

- **Decision**: los servicios de `core` **no** llevan `profiles:`, de modo que
  `docker compose -f infra/docker-compose.yml up` los levanta siempre; los perfiles `llmops`,
  `observability` y `kafka` se declaran en el README y en la cabecera del compose como reservados
  y se activan con `--profile <nombre>` o `COMPOSE_PROFILES`. Verificado: activar un perfil sin
  servicios no produce error.
- **Rationale**: la constitución exige un único comando sin flags; un servicio con
  `profiles: [core]` no arrancaría sin `--profile core` o sin un `.env` local. `core` acepta
  también `--profile core` (no-op) para uniformidad.
- **Alternatives considered**: `profiles: [core]` + `COMPOSE_PROFILES=core` en `.env` (exige un
  paso manual en un clon limpio, incumple SC-001); servicios placeholder en los demás perfiles
  (YAGNI, imágenes sin uso).

## D6. Configuración y secretos

- **Decision**: `infra/.env.example` versionado con valores de ejemplo (usuario/base
  `shoplab`, contraseña de ejemplo evidente, puertos, modelos, versiones de imagen); el compose
  usa `${VAR:-default}` para que funcione sin copiar el archivo. `.env` ya está en `.gitignore`.
- **Rationale**: FR-010, Principio VI. Los defaults permiten el arranque con un solo comando.
- **Alternatives considered**: Docker secrets (excesivo para desarrollo local); contraseñas fijas
  en el compose (peor para cambiar y para no reutilizar valores).

## D7. GPU

- **Decision**: `infra/docker-compose.gpu.yml` como override opcional
  (`-f docker-compose.yml -f docker-compose.gpu.yml`) que reserva dispositivos NVIDIA para `ollama`.
  Base solo CPU.
- **Rationale**: la reserva de GPU hace fallar el arranque en máquinas sin driver; un override
  mantiene el comando base universal (edge case "sin GPU").
- **Alternatives considered**: reserva siempre activa (rompe equipos sin GPU); detección
  automática por script (más piezas, fuera de alcance).

## D8. Fin de línea del script de entrypoint

- **Decision**: añadir `.gitattributes` con `*.sh text eol=lf`.
- **Rationale**: en Windows Git puede convertir a CRLF y el shebang falla dentro del contenedor
  ("no such file or directory"); es un riesgo real de este equipo de desarrollo.
- **Alternatives considered**: confiar solo en `.editorconfig` (no controla la conversión de Git).

## D9. Persistencia y reinicio desde cero

- **Decision**: volúmenes con nombre `pgdata` y `ollama_models`; el README documenta
  `docker compose -f infra/docker-compose.yml down -v` para reiniciar. El script SQL de init solo
  corre con el volumen vacío; el healthcheck de Postgres además verifica que la extensión exista.
- **Rationale**: FR-004 y edge case de datos antiguos. Los volúmenes con nombre sobreviven a
  `down` y se borran solo con `-v`.
- **Alternatives considered**: bind mounts (permisos y rendimiento irregulares en Windows).

## D10. Healthchecks

- **Decision**: Postgres: `pg_isready` más consulta a `pg_extension` por `vector`. Ollama:
  `ollama list` contiene ambos modelos configurados (la imagen no incluye `curl`). Intervalo
  10 s, `start_period` 60 s (Postgres) y 600 s (Ollama, cubre la descarga inicial).
- **Rationale**: FR-005; sin dependencias extra en la imagen.
- **Alternatives considered**: `curl` a `/api/tags` (requiere instalar curl en la imagen).
