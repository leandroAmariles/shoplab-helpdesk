# Quickstart: validar la infraestructura local

Guion de validación de extremo a extremo; cada paso corresponde a requisitos de la spec. Los
comandos se ejecutan desde la raíz del repositorio. Interfaz completa en
[contracts/compose-interface.md](contracts/compose-interface.md).

## Prerrequisitos

- Docker con Compose v2+ (`docker compose version`).
- Espacio libre: ~13 GB con los modelos por defecto; RAM según la tabla del README.
- Red para la primera descarga de modelos.

## 1. Definición válida (FR-001, FR-007, FR-008)

```bash
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml --profile llmops --profile observability --profile kafka config --services
```

Esperado: sin errores; la segunda lista solo `postgres` y `ollama`.

## 2. Arranque de `core` (FR-002, FR-003, FR-005, FR-006, SC-001)

```bash
docker compose -f infra/docker-compose.yml up -d --wait
docker compose -f infra/docker-compose.yml ps
```

Esperado: `up --wait` termina con código 0 y ambos servicios `healthy`. La primera vez puede tardar
según la red (descarga de modelos).

## 3. Postgres con pgvector (FR-002)

```bash
docker compose -f infra/docker-compose.yml exec postgres \
  psql -U shoplab -d shoplab -tAc "select version(); select extversion from pg_extension where extname='vector';"
```

Esperado: versión 16.x y una versión de `vector` no vacía.

## 4. Modelos disponibles (FR-003)

```bash
curl -s http://localhost:11434/api/tags
```

Esperado: lista con el modelo de chat y el de embeddings configurados.

## 5. Persistencia y rapidez de rearranque (FR-004, SC-002, SC-003)

```bash
docker compose -f infra/docker-compose.yml down
docker compose -f infra/docker-compose.yml up -d --wait
```

Esperado: saludable en < 2 min, sin volver a descargar modelos y con los datos de Postgres
intactos (crear una tabla de prueba antes de `down` y comprobarla después).

## 6. Dependencias y fallo (FR-005, FR-006)

```bash
docker compose -f infra/docker-compose.yml stop ollama
docker compose -f infra/docker-compose.yml ps
```

Esperado: `ollama` aparece detenido/no saludable. Volver a levantarlo con `up -d --wait`.

## 7. Perfiles (FR-007, SC-004)

```bash
docker compose -f infra/docker-compose.yml ps --services
```

Esperado: únicamente `postgres` y `ollama`.

## 8. Cambio de modelo (FR-009)

Poner `OLLAMA_CHAT_MODEL=llama3.2:1b` en `infra/.env`, ejecutar `up -d --wait` y comprobar con
`curl -s http://localhost:11434/api/tags` que el modelo nuevo aparece sin editar el compose.

## 9. Reinicio desde cero (FR-013)

```bash
docker compose -f infra/docker-compose.yml down -v
```

Esperado: volúmenes eliminados; el siguiente `up` repite la descarga.

## 10. Higiene del repositorio (FR-010, FR-014, SC-006)

```bash
git status --short            # no debe aparecer infra/.env
git check-ignore infra/.env   # debe imprimir la ruta
grep -n "Pendiente" README.md # no debe quedar el aviso de arranque pendiente
```

## 11. Fin de línea del entrypoint (D8)

```bash
git ls-files --eol infra/ollama/entrypoint.sh
```

Esperado: `w/lf` (sin CRLF).
