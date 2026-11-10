# Implementation Plan: Pantalla de administración de documentos

**Branch**: `feature/LAB-25-document-admin-screen` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-25-document-admin-screen/spec.md`

## Summary

El administrador sube PDF/Markdown desde una pantalla Angular (`/documentos`) con progreso, cancelación y una lista de
documentos con estado (pendiente, indexado, error) y número de chunks. En el backend, el módulo `retrieval` gana dos
operaciones REST (`POST /api/documents` multipart y `GET /api/documents`) que reutilizan la ingesta (LAB-20) y el disparo
automático de embeddings (LAB-21, `IndexAfterIngestListener`). No hay migraciones: el estado y la causa ya viven en
`retrieval.document`. La pantalla refresca los documentos pendientes por consulta periódica acotada. Detalle de decisiones
en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 6 / Angular 22 (frontend)

**Primary Dependencies**: Spring Boot 4.x WebFlux, Spring Modulith, `JdbcTemplate` vía `BlockingCalls`, Micrometer;
Angular Material 22, `HttpClient` con `reportProgress`, Vitest. Sin dependencias nuevas.

**Storage**: PostgreSQL 16 + pgvector, esquema `retrieval` (solo lectura nueva: listado). Sin migración.

**Testing**: JUnit 5 + Mockito (casos de uso), `WebTestClient` (controlador), Testcontainers/Postgres (adaptador de
listado) siguiendo el estilo de LAB-19..21, BlockHound, Modulith tests; Vitest + `HttpTestingController` (frontend).

**Target Platform**: servidor Linux (contenedor, ADR-0006/0007); navegadores modernos de escritorio y móvil.

**Project Type**: aplicación web (backend modular + frontend Angular).

**Performance Goals**: subida ≤ 5 MB responde en < 10 s p95 (SC-003, sin contar embeddings); lista de 100 documentos en
< 2 s (SC-004); cambio de estado visible < 10 s tras indexar (SC-005).

**Constraints**: documento ≤ 10 MiB (alineado con `INGEST_MAX_DOCUMENT_BYTES`); sin bloqueo del hilo reactivo
(BlockHound); `retrieval` único dueño de su esquema; mensajes de UI en español; accesibilidad por teclado y lector.

**Scale/Scope**: decenas de documentos en F1 (techo de prueba: 100); un administrador a la vez en la práctica; una pantalla
nueva, dos endpoints, un ADR.

Sin `NEEDS CLARIFICATION` pendientes.

## Constitution Check

*GATE: antes de la investigación (Fase 0) y tras el diseño (Fase 1).*

| Principio | Evaluación | Resultado |
|---|---|---|
| I. Monolito modular | Todo dentro de `retrieval`; la pantalla habla con la API REST; ningún módulo nuevo ni tabla compartida | PASA |
| II. Hexagonal | `DocumentController` solo arma el comando y llama al puerto de entrada; listado tras `DocumentCatalogPort`; casos de uso sin infraestructura; mapeo explícito | PASA |
| III. Inglés y errores tipados | Identificadores en inglés, textos de UI en español; errores con `ResponseCodeEnum` existentes y solo `GlobalExceptionHandler` mapea a HTTP; sin códigos nuevos | PASA |
| IV. Test-first y cobertura ≥ 80 % | Tests por capa (ver Estructura); DTO/config/mapper excluidos y sin lógica; `requests.http` tras implementar | PASA |
| V. RAG evaluado | No cambia prompt, chunking, embeddings ni recuperación; solo expone operaciones existentes. Desde F2 la evaluación no se ve afectada | PASA (n/a) |
| VI. Observabilidad y datos | Métrica `retrieval.upload{outcome}` y `retrieval.documents{status}`; logs con `requestId`; sin texto de documentos ni nombres como etiqueta; fixtures del corpus del proyecto | PASA |
| VII. Simplicidad y ADR | Sin paginación, sin tiempo real, sin borrado (YAGNI); ADR-0021 documenta la decisión | PASA |

**Post-diseño**: se reevalúa sin cambios. Único punto a vigilar: la ausencia de autenticación (R10) es una deuda aceptada
por la spec y registrada en el ADR, no una violación de la Constitución (Keycloak llega con la fase de seguridad).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-25-document-admin-screen/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── documents-api.md
├── checklists/requirements.md
└── tasks.md              # /speckit-tasks (no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/
├── shared/exception/ResponseCodeEnum.java          # sin cambios (se reutilizan los códigos SLHD_RETR_*)
└── retrieval/
    ├── application/
    │   ├── port/in/ListDocumentsServicePort.java            # NUEVO
    │   ├── port/out/DocumentCatalogPort.java                # NUEVO
    │   ├── port/out/UploadMetricsPort.java                  # NUEVO
    │   └── usecase/
    │       ├── ListDocumentsUseCase.java                    # NUEVO
    │       └── IngestDocumentUseCase.java                   # MODIFICA: registra métrica de subida
    ├── domain/model/
    │   ├── DocumentSummary.java                             # NUEVO
    │   ├── IngestedDocument.java                            # MODIFICA: + replaced
    │   └── constants/RetrievalConstants.java                # + nombres de métricas
    ├── config/UploadProperties.java                         # NUEVO (shoplab.retrieval.upload.max-bytes)
    └── infrastructure/
        ├── in/rest/
        │   ├── DocumentController.java                      # NUEVO
        │   ├── dto/DocumentUploadResponse.java              # NUEVO
        │   ├── dto/DocumentSummaryResponse.java             # NUEVO
        │   └── util/mapper/DocumentResponseMapper.java      # NUEVO
        └── out/
            ├── persistence/JdbcDocumentCatalogAdapter.java  # NUEVO
            ├── persistence/JdbcKnowledgeBaseAdapter.java    # MODIFICA: RETURNING inserted
            └── metrics/MicrometerUploadMetrics.java         # NUEVO

backend/src/main/resources/application.yml                   # + shoplab.retrieval.upload, multipart limits
backend/src/test/java/com/shoplab/helpdesk/retrieval/        # tests espejo de lo anterior

frontend/src/app/
├── app.routes.ts                                            # MODIFICA: ruta `documentos` + nav «Documentos»
├── core/api/api-error.ts / api-error.interceptor.ts         # MODIFICA: expone `code`/`message` del cuerpo
└── features/documents/                                      # NUEVO
    ├── documents.ts / .html / .scss                         # pantalla
    ├── documents-api.client.ts                              # GET y POST con progreso
    ├── documents-store.ts                                   # lista, cola de subidas, refresco acotado
    ├── upload-validation.ts                                 # formato y tamaño locales
    ├── document-status.ts                                   # etiquetas y traducción de la causa (R7)
    ├── upload-list/ , document-table/                       # subcomponentes
    └── *.spec.ts

docs/
├── adr/0021-subida-y-administracion-de-documentos.md        # NUEVO
├── modulos.md, modulos/module-retrieval.adoc                # actualiza
└── base-conocimiento.md                                     # flujo de subida desde la pantalla

specs/LAB-25-document-admin-screen/requests.http             # al implementar
```

**Structure Decision**: aplicación web existente (backend modular + frontend Angular). El backend extiende la arquitectura
hexagonal de `retrieval`; el frontend añade una *feature* nueva con carga perezosa, siguiendo el patrón de `features/chat`
(cliente HTTP, *store* con señales, componentes pequeños). Infraestructura: revisar el límite de cuerpo del Ingress en
`shoplab-infra` (R3).

## Fases de trabajo (orden propuesto para `/speckit-tasks`)

1. **Backend base** (US1/US3 en servidor): `UploadProperties`, `replaced` en `IngestedDocument`, `DocumentCatalogPort` +
   adaptador + caso de uso de listado, `DocumentController` con DTO y mapper, límites multipart, métricas. Tests primero.
2. **Frontend base**: ruta y navegación, cliente HTTP, *store* y lista (US3, US4); interceptor ampliado.
3. **Subida con progreso** (US1/US2): validación local, cola secuencial, progreso/procesando/cancelar/reintentar.
4. **Refresco acotado y accesibilidad** (US3/US4): sondeo mientras haya pendientes, estados vacío/error, roles ARIA,
   responsive.
5. **Cierre**: ADR-0021, docs de módulo, límite del Ingress en infra, `requests.http`, cobertura, Checkstyle, BlockHound.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Ingress rechaza cuerpos > 1 MiB | revisar/ajustar `proxy-body-size` en `shoplab-infra` dentro de la historia (R3) |
| Doble indexación si se dispara desde el controlador | no se dispara: solo el evento existente (R8) |
| Subidas concurrentes del mismo nombre | el `UPSERT` + `updated_at` como versión de LAB-21 ya garantiza coherencia; test de caso |
| Interceptor muestra *snackbar* duplicado con errores propios de la pantalla | el interceptor propaga `code` y la pantalla decide (R9) |
| Sin autenticación expone la subida | deuda explícita en ADR-0021; solo entorno dev (ADR-0008) |

## Complexity Tracking

Sin violaciones de la Constitución que justificar.
