# Quickstart: validar LAB-129

Guía de validación. Qué pasos se ejecutaron y con qué resultado está en [validation.md](./validation.md); los
casos HTTP, en [requests.http](./requests.http). Los pasos 4 y 7 comparan con el módulo del monolito, así que ya
solo se pueden repetir con la imagen `backend:v0.29.0`.

## Requisitos

Docker, Java 21, `uv`, `gh` autenticado y acceso a GitHub Packages (`read:packages`).

## 1. Plataforma (US1, SC-008)

- Generar `shoplab-retrieval-service` desde `shoplab-service-template` y publicar una release antes de mover código.
  **Esperado**: CI verde; imagen `:vX.Y.Z` en GHCR con firma verificada.
- Abrir un PR en `shoplab-infra` que toque `k8s/`. **Esperado**: `ci-gate` verde, con `smoke` o sin él según
  [research D-10](./research.md). Si se quitó, ejecutar `make smoke` en local antes de fusionar cambios de `k8s/`.
- Sin `infra/.env`: `docker compose -f infra/docker-compose.yml --profile llmops up -d --wait`.
  **Esperado**: todos los PostgreSQL sanos.

## 2. Línea base (FR-023)

Con el sistema sin cambios y el corpus indexado, en `shoplab-eval`:

```bash
uv run python -m shoplab_eval retrieval
```

Guardar los CSV de resumen y detalle en `specs/LAB-129-retrieval-service-extraction/baseline/`.

## 3. Migración (SC-002)

Provisionar, migrar y verificar según [data-migration.md](./contracts/data-migration.md).
**Esperado**: verificación sin diferencias y cero llamadas de embeddings en el gasto de la clave
`retrieval-local`.

## 4. Paridad en paralelo (US2)

Con el servicio en `:8082` y el monolito en `:8081`, comparar:

```bash
curl -s localhost:8081/api/documents
curl -s localhost:8082/api/documents
curl -s "localhost:8081/api/knowledge/search?query=devoluciones&limit=5"
curl -s "localhost:8082/api/knowledge/search?query=devoluciones&limit=5"
```

**Esperado**: mismos estados y cuerpos. Repetir con consulta vacía (`422 SLHD_RETR_00009`), un archivo `.txt`
(`415`) y uno de más de 10 MiB (`413`).

## 5. Corte (US3, SC-001, SC-003)

Aplicar [configuration.md](./contracts/configuration.md), y entonces:

- `grep "access requestId"` en el registro del gateway: `routeId=documents destination=http://retrieval-service:8080`.
- Pantalla `/documentos`: lista igual; subir un documento y verlo indexado.
- Preguntar en el chat por ese documento. **Esperado**: respuesta con su cita.
- Repetir el paso 2 y comparar. **Esperado**: archivos idénticos.

## 6. Fallos del chat (US4)

Detener `retrieval-service`. Una pregunta de la base → `SLHD_CHAT_00004`; «hola» → responde.
Preguntar con un mensaje de 4000 caracteres con acentos y `{ } & + # %` (con el servicio arriba): sin error.

## 7. Reversión (SC-007)

Revertir el commit del corte y comprobar en el registro que las rutas vuelven al monolito, en menos de 10 minutos.
Volver a aplicarlo.

## 8. Dos instancias (US5, SC-006)

Prueba automatizada del servicio: dos instancias sobre la misma base con documentos pendientes; cada documento se
indexa una vez. Matar la que tiene el cerrojo: otra continúa pasado el TTL.

## 9. Latencia (SC-005)

Comparar el p95 de la búsqueda del chat antes y después, en local, con las mismas preguntas.

## 10. Despliegue independiente y retirada (SC-004, FR-021)

- Publicar una release del servicio: dev se actualiza sin release del monolito.
- En el monolito: `./mvnw -B clean verify` en verde, `docs/modulos/` regenerado y ninguna referencia a
  `helpdesk.retrieval`.
