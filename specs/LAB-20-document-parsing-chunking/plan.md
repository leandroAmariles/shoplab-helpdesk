# Implementation Plan: Parseo y chunking de documentos

**Branch**: `feature/LAB-20-document-parsing-chunking` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-20-document-parsing-chunking/spec.md`

## Summary

Crear el servicio `ingest` (FastAPI, sin estado) con `POST /ingest`, que recibe un PDF o Markdown, lo parsea y devuelve
sus chunks con metadatos (documento, sección, posición) troceados por encabezados con solape configurable. En el backend,
el módulo `retrieval` gana el caso de uso de ingesta: llama a `ingest` por un puerto (`WebClient`), y guarda documento y
chunks de forma atómica en su esquema, sin vector todavía. Para ello una migración `V3` hace opcionales `embedding` y
`embedding_model` (con un `CHECK` «ambos o ninguno»), relajando el FR-003 de LAB-19. El contrato de `POST /ingest` se
versiona en `ingest/openapi.json` y la CI lo verifica en ambos lados. Se completa el despliegue: servicio `ingest` en
`docker compose`, sonda `/health` y URL de `ingest` para el backend en `shoplab-infra`.

Decisiones y alternativas en [research.md](research.md).

## Technical Context

**Language/Version**: Python 3.12 (`ingest`); Java 21 y SQL PostgreSQL 16 (`retrieval`)

**Primary Dependencies**: `ingest`: FastAPI, Uvicorn, `python-multipart`, `markdown-it-py`, `pdfplumber`,
`pydantic-settings`, `prometheus-client`. `retrieval`: Spring Boot 4.1 (WebFlux `WebClient`, JDBC), Micrometer; sin dependencias nuevas en el
backend.

**Storage**: `ingest`: ninguno. `retrieval`: PostgreSQL 16 + pgvector, esquema `retrieval` (ADR-0002, ADR-0015).

**Testing**: `ingest`: pytest + `TestClient` (httpx) + pytest-cov (≥ 80 %), PDF generados con `fpdf2` y corpus `data/kb`.
Backend: JUnit 5, Mockito para el caso de uso, WireMock para el adaptador, Testcontainers (`@PostgresTest`) para
persistencia, ArchUnit/Modulith existentes, BlockHound activo.

**Target Platform**: contenedores Linux; local con `docker compose`; dev por GitOps (`shoplab-infra`)

**Project Type**: web-service (servicio Python nuevo + módulo `retrieval` del monolito modular)

**Performance Goals**: política típica (≤ 20 páginas) de extremo a extremo en < 5 s (SC-004)

**Constraints**: `ingest` sin estado ni credenciales de base de datos; documento ≤ 10 MiB; troceado determinista; líneas
≤ 121 caracteres en Java y Python; sin `RestTemplate`/`RestClient` (ADR-0011); JDBC solo vía `BlockingCalls`

**Scale/Scope**: corpus de ~25 documentos (LAB-18); diseño válido hasta miles de chunks por documento

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: `retrieval` sigue siendo el único dueño de su esquema; `ingest` entrega su salida por HTTP y no toca la base de datos. Sin claves foráneas nuevas. |
| II. Hexagonal | Cumple: casos de uso en `application` con puertos (`DocumentParserPort`, `KnowledgeBasePort`); `WebClient` y JDBC solo en `infrastructure`; el dominio no importa Spring, JDBC ni Reactor. En `ingest`, el núcleo (parseo y troceado) es Python puro sin FastAPI. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; errores nuevos en `ResponseCodeEnum` (`SLHD_RETR_*`), construidos con `ExceptionUtil` y traducidos solo por `GlobalExceptionHandler`. `ingest` devuelve códigos estables. |
| IV. Pruebas con cobertura | Cumple: pruebas con la implementación; ≥ 80 % en backend (JaCoCo) y en `ingest` (pytest-cov); integración contra PostgreSQL real. |
| V. RAG con evaluación | No aplica todavía (desde F2). El tamaño de chunk y el solape quedan configurables para que la evaluación los compare. |
| VI. Observabilidad y datos seguros | Cumple en logs y métricas: logs JSON con `X-Request-Id` y `/metrics` Prometheus en `ingest` (contador por resultado e histograma de duración, recogidos en local y en dev) y `Timer` en `retrieval`. Las trazas distribuidas no existen aún en el proyecto (ver Complexity Tracking). Sin secretos nuevos; datos de prueba del corpus sintético. |
| VII. Simplicidad y ADR | Cumple: reglas simples (sin OCR ni modelos), sin colas ni reintentos. Se registra ADR-0016 por la estrategia de troceado y el contrato. |

**Gate**: PASA, con una desviación menor justificada (trazas distribuidas).

**Re-evaluación tras el diseño**: PASA. El diseño añade un servicio y un módulo con puertos, sin dependencias de backend ni
tablas nuevas; la única migración (`V3`) es aditiva en comportamiento y está justificada por el orden LAB-20 → LAB-21.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-20-document-parsing-chunking/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── ingest.openapi.yaml        # diseño del contrato de POST /ingest
│   └── retrieval-ingestion.md     # puertos, errores y configuración del caso de uso
├── checklists/requirements.md
├── requests.http                  # se genera al implementar (regla http-test-requests)
└── tasks.md                       # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
ingest/                                         # servicio FastAPI (antes: solo Dockerfile)
├── pyproject.toml                              # uv, package = false; ruff 121; pytest con cobertura
├── uv.lock
├── .dockerignore
├── openapi.json                                # contrato versionado, verificado en CI
├── app/
│   ├── main.py                                 # FastAPI: rutas, manejadores de error, /health
│   ├── openapi_export.py                       # regenera openapi.json
│   ├── api/ (routes.py, schemas.py, errors.py)
│   ├── core/ (config.py, metrics.py)           # INGEST_CHUNK_SIZE, INGEST_CHUNK_OVERLAP, INGEST_MAX_DOCUMENT_BYTES; /metrics
│   ├── domain/ (models.py, errors.py)          # ParsedSection, Chunk, IngestResult; errores tipados
│   ├── parsing/ (detect.py, markdown.py, pdf.py)
│   ├── chunking/ (splitter.py)                 # troceo por tamaño + solape, sin dependencias web
│   └── service.py                              # bytes -> IngestResult
└── tests/ (unit por módulo, test_api.py, test_corpus.py, test_openapi_contract.py, fixtures/)

backend/
├── src/main/java/com/shoplab/helpdesk/retrieval/
│   ├── domain/model/                           # ParsedDocument, ParsedChunk, IngestedDocument (records)
│   ├── application/
│   │   ├── port/in/IngestDocumentServicePort.java  # + IngestDocumentCommand (en domain/model)
│   │   ├── port/out/DocumentParserPort.java
│   │   ├── port/out/KnowledgeBasePort.java
│   │   └── usecase/IngestDocumentService.java
│   ├── infrastructure/out/
│   │   ├── ingest/ (IngestWebClientAdapter, IngestResponse DTO, IngestErrorMapper)
│   │   └── persistence/JdbcKnowledgeBaseAdapter.java
│   └── config/ (IngestClientProperties, RetrievalIngestConfig)
├── src/main/java/com/shoplab/helpdesk/shared/exception/ResponseCodeEnum.java   # + 5 códigos SLHD_RETR_*
├── src/main/resources/db/migration/retrieval/V3__chunk_embedding_optional.sql  # NUEVO
├── src/main/resources/application.yml          # + shoplab.retrieval.ingest.*
└── src/test/java/com/shoplab/helpdesk/
    ├── retrieval/application/usecase/IngestDocumentServiceTests
    ├── retrieval/infrastructure/out/ingest/IngestWebClientAdapterTests
    ├── retrieval/infrastructure/out/persistence/JdbcKnowledgeBaseAdapterTests
    ├── retrieval/ (IngestContractTests, IngestDocumentFlowIntegrationTests)
    └── schema/ (RetrievalSchemaIntegrationTests y ModuleSchemasIntegrationTests: actualizadas a V3)

infra/docker-compose.yml                        # + servicio ingest (sin variables de base de datos)
infra/observability/prometheus/prometheus.yml   # + job ingest (ingest:8000, /metrics)
.github/workflows/ci.yml                        # el filtro del job backend incluye ingest/openapi.json
docs/ (adr/0016-troceado-por-encabezados-e-ingesta-sincrona.md, modulos/ regenerado, modulos.md y README de ingest)
../shoplab-infra (repo hermano, rama propia desde main)
  └── k8s/base/ingest/deployment.yaml (sonda httpGet /health), k8s/base/ingest/servicemonitor.yaml (+ kustomization),
      k8s/base/backend/kustomization.yaml (URL de ingest)
```

**Structure Decision**: se mantiene el monorepo con el servicio Python en `ingest/` (ya existe su `Dockerfile`, su job de CI
y su imagen) y el código Java dentro del módulo `retrieval` existente, en las capas `domain`, `application`,
`infrastructure` y `config` que fija ADR-0015. Los nombres de paquete de puertos siguen las guías de desarrollo. El
contrato vive junto al servicio que lo implementa; pasará a `shoplab-contracts` cuando `retrieval` se extraiga (F5).

## Orden de implementación recomendado

1. `V3` y actualización de las pruebas de LAB-19 (desbloquea el guardado sin vector).
2. Núcleo de `ingest`: parseo Markdown → troceado → PDF → errores, cada uno con sus pruebas.
3. API de `ingest`, `/health`, `openapi.json` y su prueba de contrato; imagen construible con `uv.lock`.
4. `retrieval`: dominio y puertos → caso de uso → adaptador `WebClient` → adaptador JDBC → prueba de flujo.
5. Prueba de contrato del lado backend, ADR-0016, documentación y `docs/modulos` regenerado.
6. Compose local y, en `shoplab-infra`, sonda y URL de `ingest` (rama y PR propias).
7. `requests.http` de la carpeta del spec.

## Complexity Tracking

| Desviación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Sin trazas distribuidas (Constitución VI pide logs, métricas y trazas) | El proyecto no tiene aún pila de trazado (ni el backend); `ingest` cumple logs JSON con `X-Request-Id` y métricas `/metrics`, y `retrieval` mide la llamada con un `Timer`. Las trazas deben añadirse a ambos servicios a la vez para tener continuidad. | Instrumentar solo `ingest` con OpenTelemetry daría trazas sin continuación en el backend; se registra como historia de observabilidad (trazas en backend e `ingest`) tras fusionar. |
