---

description: "Task list for LAB-10 Infraestructura local con docker compose"
---

# Tasks: Infraestructura local con docker compose

**Input**: Design documents from `/specs/LAB-10-local-infra-compose/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/compose-interface.md,
quickstart.md

**Tests**: No se piden tests automatizados (no hay código de aplicación); la verificación son los
comandos de [quickstart.md](quickstart.md), que se ejecutan al cierre de cada historia.

**Organization**: Tareas agrupadas por historia de usuario. Commits: `LAB-10 type(scope): description`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: Historia a la que pertenece (US1, US2, US3, US4)
- Todas las rutas son relativas a la raíz del repositorio. `infra/docker-compose.yml` lo editan
  varias historias, por eso esas tareas no llevan `[P]`.

## Phase 1: Setup (línea base)

**Purpose**: Confirmar rama, herramientas y estado heredado.

- [X] T001 Confirmar con `git branch --show-current` que la rama es `chore/LAB-10-local-infra-compose` y con `docker compose version` que Compose es v2 o superior; listar `ls -la infra` para ver que solo existe `infra/.gitkeep`

---

## Phase 2: Foundational (bloquea todas las historias)

**Purpose**: Archivos compartidos de configuración y fin de línea.

**⚠️ CRITICAL**: Ninguna historia puede empezar hasta completar esta fase.

- [X] T002 [P] Crear `.gitattributes` en la raíz con `* text=auto` y `*.sh text eol=lf` (research D8: un `entrypoint.sh` con CRLF falla dentro del contenedor)
- [X] T003 [P] Crear `infra/.env.example` con las variables de data-model.md: `POSTGRES_IMAGE=pgvector/pgvector:0.8.7-pg16`, `POSTGRES_DB=shoplab`, `POSTGRES_USER=shoplab`, `POSTGRES_PASSWORD=change-me-local-only`, `POSTGRES_PORT=5432`, `OLLAMA_IMAGE=ollama/ollama:<tag vigente>` (consultar el tag estable más reciente en Docker Hub y escribirlo fijo, nunca `latest`), `OLLAMA_PORT=11434`, `OLLAMA_CHAT_MODEL=llama3.2:3b`, `OLLAMA_EMBED_MODEL=bge-m3`; comentario de cabecera: copiar a `infra/.env` para personalizar (no versionar secretos reales)
- [X] T004 Crear el esqueleto `infra/docker-compose.yml` con `name: shoplab`, comentario de cabecera que liste los perfiles `core` (siempre activo), `llmops`, `observability` y `kafka` (reservados, ver research D5) y un bloque `volumes:` vacío; retirar `infra/.gitkeep` con `git rm infra/.gitkeep`
- [X] T005 Verificar con `docker compose -f infra/docker-compose.yml config --quiet` que el esqueleto es válido y con `git check-ignore infra/.env` que `.env` sigue excluido (FR-010)

**Checkpoint**: Base lista para añadir servicios.

---

## Phase 3: User Story 1 - Levantar BD y modelos con un comando (Priority: P1) 🎯 MVP

**Goal**: `docker compose -f infra/docker-compose.yml up` deja Postgres 16 con pgvector y Ollama con un modelo de chat y uno de embeddings descargados, con datos y modelos persistentes (FR-001, FR-002, FR-003, FR-004, FR-009, FR-010).

**Independent Test**: Quickstart pasos 2 (arranque), 3 (Postgres), 4 (modelos) y 5 (persistencia).

- [X] T006 [P] [US1] Crear `infra/postgres/init/01-extensions.sql` con `CREATE EXTENSION IF NOT EXISTS vector;` (solo corre con el volumen vacío, research D9)
- [X] T007 [P] [US1] Crear `infra/ollama/entrypoint.sh` (POSIX `sh`, `set -eu`, ejecutable): iniciar `ollama serve` en segundo plano, esperar con `ollama list` hasta que responda (reintento cada 2 s), ejecutar `ollama pull "$OLLAMA_CHAT_MODEL"` y `ollama pull "$OLLAMA_EMBED_MODEL"` solo si el modelo no aparece en `ollama list`, y terminar con `wait` sobre el proceso del servidor para mantenerlo en primer plano; salir con error claro si falta alguna de las dos variables
- [X] T008 [US1] En `infra/docker-compose.yml`, añadir el servicio `postgres`: imagen `${POSTGRES_IMAGE:-pgvector/pgvector:0.8.7-pg16}`, variables `POSTGRES_DB/USER/PASSWORD` con `${VAR:-default}` (mismos defectos de `.env.example`), puerto `${POSTGRES_PORT:-5432}:5432`, volumen `pgdata:/var/lib/postgresql/data`, bind de solo lectura `./postgres/init:/docker-entrypoint-initdb.d:ro`, `restart: unless-stopped`; sin clave `profiles:` (research D5)
- [X] T009 [US1] En `infra/docker-compose.yml`, añadir el servicio `ollama`: imagen `${OLLAMA_IMAGE}` con el mismo tag fijo de T003 como defecto, puerto `${OLLAMA_PORT:-11434}:11434`, volumen `ollama_models:/root/.ollama`, bind `./ollama/entrypoint.sh:/entrypoint.sh:ro`, `entrypoint: ["sh", "/entrypoint.sh"]`, variables `OLLAMA_CHAT_MODEL` y `OLLAMA_EMBED_MODEL` con defectos `llama3.2:3b` y `bge-m3`, `restart: unless-stopped`; declarar los volúmenes con nombre `pgdata` y `ollama_models`
- [X] T010 [US1] Ejecutar `docker compose -f infra/docker-compose.yml up -d` y completar los pasos 3, 4 y 5 de `specs/LAB-10-local-infra-compose/quickstart.md` (versión 16.x, `vector` presente, ambos modelos en `/api/tags`, datos y modelos intactos tras `down`/`up`); corregir los archivos hasta que pasen

**Checkpoint**: MVP: `core` funcional y persistente.

---

## Phase 4: User Story 2 - Salud de cada servicio (Priority: P1)

**Goal**: Cada servicio informa su salud; "saludable" significa listo para usar y los dependientes pueden esperar (FR-005, FR-006).

**Independent Test**: Quickstart pasos 2 (`up --wait` con código 0 y ambos `healthy`) y 6 (servicio detenido no saludable).

- [X] T011 [US2] En `infra/docker-compose.yml`, añadir `healthcheck` a `postgres`: `CMD-SHELL` con `pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB} && psql -U $${POSTGRES_USER} -d $${POSTGRES_DB} -tAc "select 1 from pg_extension where extname='vector'" | grep -q 1`, `interval: 10s`, `timeout: 5s`, `retries: 5`, `start_period: 60s` (research D10)
- [X] T012 [US2] En `infra/docker-compose.yml`, añadir `healthcheck` a `ollama`: `CMD-SHELL` que comprueba que `ollama list` contiene `$${OLLAMA_CHAT_MODEL}` y `$${OLLAMA_EMBED_MODEL}` (la imagen no trae `curl`), `interval: 10s`, `timeout: 10s`, `retries: 5`, `start_period: 600s` para cubrir la descarga inicial
- [X] T013 [US2] Ejecutar los pasos 2 y 6 de `specs/LAB-10-local-infra-compose/quickstart.md`: `up -d --wait` termina con código 0, `docker compose -f infra/docker-compose.yml ps` muestra `healthy` en ambos y, tras `stop ollama`, `ollama` deja de estar saludable; medir que con modelos ya descargados `core` queda saludable en menos de 2 minutos (SC-002) y ajustar tiempos si no

**Checkpoint**: US1 y US2 funcionando; `service_healthy` utilizable por consumidores.

---

## Phase 5: User Story 3 - Perfiles y GPU opcional (Priority: P2)

**Goal**: Perfiles `core`, `llmops`, `observability`, `kafka` sin errores al activarlos, y soporte GPU sin romper equipos sin ella (FR-007, FR-008).

**Independent Test**: Quickstart pasos 1 (perfiles) y 7 (solo `core`).

- [X] T014 [US3] En el comentario de cabecera de `infra/docker-compose.yml`, documentar para cada perfil reservado la historia o fase que lo completará (`llmops`, `observability`, F6 / `kafka`, F4) y que `--profile core` es no-op; no añadir servicios placeholder (YAGNI)
- [X] T015 [P] [US3] Crear `infra/docker-compose.gpu.yml` con el override del servicio `ollama` que reserva dispositivos NVIDIA (`deploy.resources.reservations.devices` con `driver: nvidia`, `count: all`, `capabilities: [gpu]`) y comentario con su uso: `docker compose -f infra/docker-compose.yml -f infra/docker-compose.gpu.yml up -d --wait`
- [X] T016 [US3] Ejecutar los pasos 1 y 7 de `specs/LAB-10-local-infra-compose/quickstart.md`: `config --services` con los tres perfiles reservados lista solo `postgres` y `ollama` sin errores, y `docker compose -f infra/docker-compose.yml -f infra/docker-compose.gpu.yml config --quiet` valida el override

**Checkpoint**: Perfiles y GPU verificados.

---

## Phase 6: User Story 4 - README de hardware y modelos (Priority: P2)

**Goal**: README con arranque real, requisitos de hardware, tabla de modelos y operación (FR-011, FR-012, FR-013, FR-014).

**Independent Test**: Una persona con 16 GB de RAM sin GPU determina modelos y perfiles solo con el README; quickstart paso 10 (sin aviso "Pendiente").

- [X] T017 [US4] En `README.md`, sección `## Arrancar`: sustituir el aviso **Pendiente** por el comando real `docker compose -f infra/docker-compose.yml up -d --wait`, indicar copiar `infra/.env.example` a `infra/.env` para personalizar, y mencionar que la primera vez descarga los modelos (FR-014)
- [X] T018 [US4] En `README.md`, añadir sección `## Requisitos de hardware` con prerrequisitos de software (Docker con Compose v2+), espacio en disco (~13 GB con modelos por defecto), RAM y GPU opcional por perfil, y la aclaración de que solo CPU funciona con menor rendimiento (FR-011)
- [X] T019 [US4] En `README.md`, añadir la tabla de modelos recomendados por RAM/GPU de research.md D4 (8 GB sin GPU; 16 GB; 32 GB o GPU 8-12 GB; GPU ≥ 16 GB) con chat y embeddings, y la advertencia de que la dimensión del embedding fija `vector(n)` (1024 con `bge-m3`, 768 con `nomic-embed-text`) y que cambiarlo obliga a reindexar (FR-012)
- [X] T020 [US4] En `README.md`, añadir sección `## Operar el entorno local` con los comandos de contracts/compose-interface.md (levantar, perfil extra, GPU, estado de salud, detener, reiniciar desde cero con `down -v`), cómo cambiar de modelo con `OLLAMA_CHAT_MODEL`/`OLLAMA_EMBED_MODEL` en `infra/.env`, cómo cambiar puertos ocupados y la tabla de perfiles con su estado (FR-013)
- [X] T021 [US4] Ejecutar el paso 8 (cambio de modelo con `llama3.2:1b`) y el paso 10 de `specs/LAB-10-local-infra-compose/quickstart.md`; devolver `infra/.env` a su estado original y confirmar que `infra/.env` no aparece en `git status --short`

**Checkpoint**: Las cuatro historias completas.

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: ADR, verificación final y limpieza.

- [X] T022 [P] Crear `docs/adr/0003-ollama-modelos-locales.md` siguiendo `docs/adr/0000-plantilla.md` y el formato de `0002-postgres-pgvector.md` (Estado: aceptado, Fecha: 2026-10-23, Contexto, Decisión, Consecuencias): Ollama como servidor local de modelos, reemplazable porque el acceso a LLM/embeddings irá detrás de puertos (Principio II)
- [X] T023 Ejecutar el paso 11 de `specs/LAB-10-local-infra-compose/quickstart.md` (`git ls-files --eol infra/ollama/entrypoint.sh` debe mostrar `w/lf`) y el paso 9 (`down -v` y arranque limpio completo con `up -d --wait`)
- [X] T024 Revisar con `git status --short` y `git diff --staged` que solo hay archivos de la historia (sin `infra/.env`, `*.log` ni temporales); no hay endpoints HTTP, por lo que no se genera `requests.http`
- [X] T025 Commits atómicos con el formato `LAB-10 type(scope): description` (por ejemplo `LAB-10 chore(infra): add postgres and ollama compose services`, `LAB-10 docs(readme): document hardware and models`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; bloquea las historias. T004 depende de T003 solo por coherencia de variables; T005 depende de T003 y T004.
- **US1 (Phase 3)**: depende de Foundational. T008 y T009 dependen de T006 y T007.
- **US2 (Phase 4)**: depende de US1 (healthchecks sobre servicios existentes).
- **US3 (Phase 5)**: depende de US1; T015 es independiente de US2.
- **US4 (Phase 6)**: documentación; puede redactarse tras US1 pero su verificación (T021) requiere US1-US3.
- **Polish (Phase 7)**: depende de todas las historias; T022 puede hacerse en cualquier momento.

### Parallel Opportunities

- Fase 2: T002 y T003.
- US1: T006 y T007 (archivos distintos); luego T008 → T009 → T010 en `docker-compose.yml`.
- US3: T015 en paralelo con T014.
- T022 en paralelo con cualquier tarea.

### Parallel Example: User Story 1

```text
Task: "Crear infra/postgres/init/01-extensions.sql"
Task: "Crear infra/ollama/entrypoint.sh"
```

---

## Implementation Strategy

### MVP primero (US1)

1. Setup y Foundational (T001-T005).
2. US1 (T006-T010).
3. **Detenerse y validar** con el quickstart (pasos 2-5).

### Entrega incremental

1. US1 → entorno funcional.
2. US2 → salud verificable (cierra los dos P1).
3. US3 → perfiles y GPU.
4. US4 → README completo.
5. Polish → ADR y verificación final.

---

## Notes

- Cada tarea de verificación referencia pasos del quickstart; un paso fallido se corrige antes de continuar.
- La primera descarga de modelos requiere red y ~3,5 GB; conviene ejecutar T010 con buena conexión.
- No usar `latest` en imágenes; el tag de Ollama se fija en T003 y se repite como defecto en T009.
