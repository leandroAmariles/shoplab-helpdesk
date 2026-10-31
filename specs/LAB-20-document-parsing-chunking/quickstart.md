# Quickstart: validar LAB-20

Guía de validación de extremo a extremo; el detalle de los contratos está en [contracts/](contracts/) y el de las tablas en
[data-model.md](data-model.md).

## Requisitos

- Docker (Testcontainers y `docker compose`), JDK 21, `uv` y Python 3.12.
- Rama `feature/LAB-20-document-parsing-chunking` con la implementación.

## 1. Pruebas del servicio `ingest`

```bash
cd ingest
uv sync --frozen
uv run ruff check .
uv run pytest            # incluye cobertura >= 80 % y la comprobación de ingest/openapi.json
```

Esperado: todo en verde; la prueba de corpus ingiere los Markdown y PDF de `data/kb` (SC-001) y cumple tamaño máximo,
solape, secciones y posiciones (SC-002, SC-003).

## 2. Probar `POST /ingest` a mano

```bash
cd ingest && uv run uvicorn app.main:app --port 8000
curl -s -F "file=@../data/kb/returns-01-return-policy.md" localhost:8000/ingest | head -c 600
curl -s -F "file=@../data/kb/returns-01-return-policy.pdf" localhost:8000/ingest | head -c 600
curl -s -F "file=@../README.md;filename=notas.txt" -w '\n%{http_code}\n' localhost:8000/ingest
```

Esperado: JSON con `document` y `chunks` (cada uno con `document`, `section`, `position`); el tercer caso responde `415
UNSUPPORTED_FORMAT`. `requests.http` de esta carpeta (se genera al implementar) cubre los escenarios del spec.

## 3. Pruebas del backend

```bash
cd backend
./mvnw -B -ntp clean verify
```

Esperado: Checkstyle, PMD, Modulith, JaCoCo ≥ 80 %, pruebas de `retrieval` (caso de uso con puertos simulados, adaptador
contra WireMock, persistencia contra PostgreSQL con pgvector, contrato contra `ingest/openapi.json`) y las de LAB-19
actualizadas a `V3`.

## 4. Flujo completo local

```bash
docker compose -f infra/docker-compose.yml up -d --wait   # postgres, ollama, ingest
cd backend && ./mvnw spring-boot:run
```

La ingesta de punta a punta se ejercita con la prueba de integración de `retrieval` (WireMock de `ingest` + PostgreSQL
real); el endpoint de subida llega con LAB-25. Comprobar en la base de datos:

```sql
select source, title, updated_at from retrieval.document;
select count(*), count(embedding) from retrieval.chunk;   -- chunks guardados / con vector (0 hasta LAB-21)
```

## 5. Despliegue en dev (`shoplab-infra`)

Tras fusionar y publicar la release, comprobar que el pod `ingest` pasa su sonda `/health` y que el backend tiene
`SHOPLAB_RETRIEVAL_INGEST_BASE_URL=http://ingest:8000` (LAB-78, validación del primer tag).
