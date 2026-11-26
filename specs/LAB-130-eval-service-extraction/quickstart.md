# Quickstart: LAB-130

Escenarios de validación de extremo a extremo, en orden. Cada uno nombra los requisitos que comprueba.

## 0. Requisitos

- Compose local arriba (`infra/docker-compose.yml`), con `chat-service` y `retrieval-service`.
- Repos hermanos clonados (`scripts/clone-all.ps1`), incluido `shoplab-eval-service`.
- `EVAL_DB_PASSWORD` y `SHOPLAB_EVAL_PERSISTENCE_TOKEN` definidos en `infra/.env`.

## 1. Servicio en paralelo (US1, FR-001…FR-012)

1. `docker compose -f infra/docker-compose.yml up -d eval-db-init eval-service`
2. `bash scripts/migrate-eval.sh` y `bash scripts/verify-eval-migration.sh` → recuentos, MD5 y secuencias iguales.
3. Misma petición al monolito (`:8080`) y al servicio (`:8084`):
   `GET /api/eval/history/runs`, `GET /api/eval/history/runs/{id}`, `GET /api/eval/history/compare?base=…&target=…`
   → cuerpos idénticos.
4. `POST /api/eval/runs` dos veces con el mismo `runId` y token → `201` y luego `409 SLHD_EVAL_00003`.

## 2. Configuración desde chat (US2, FR-013…FR-016)

1. `GET /api/eval/settings` con `X-Eval-Token` → configuración servida por `chat-service`.
2. `docker compose stop chat-service` → `GET /api/eval/settings` devuelve `503 SLHD_EVAL_00005`;
   `GET /api/eval/history/runs` y `POST /api/eval/runs` siguen respondiendo.
3. `mvn verify` en `shoplab-eval-service` → pasan `EvalChatContractConsumerTest` y el productor de contratos.

## 3. Corte (US3, FR-017…FR-022)

1. Vaciar el token del monolito; ejecutar la migración; verificar.
2. En el gateway local/dev: `SHOPLAB_GATEWAY_ROUTES_EVAL_URI=http://eval-service:8080`.
3. Ejecutar la herramienta de `shoplab-eval` con la URL nueva y el token → corrida guardada.
4. Abrir `admin-eval`: se ven las migradas y la nueva; comparar una migrada con la nueva.
5. Revertir la variable → el monolito vuelve a atender (solo antes de la fase D).

## 4. Retiro (US4, FR-024, FR-025)

1. Quitar `backend/` y sus referencias; `docker compose up` desde cero sin él.
2. `git grep -n "backend/"` sin resultados relevantes; CI en verde.
3. `psql` sobre `shoplab`: los esquemas Olist y `eval` siguen presentes.

## 5. Documentación (US5, FR-027)

ADR-0035, `docs/mapa-servicios.md`, `docs/modulos.md`, `docs/contratos.md`, `docs/modulos/admin-eval.md` y la
constitución (PATCH) actualizados; `docs/adr/README.md` enlaza el ADR.
