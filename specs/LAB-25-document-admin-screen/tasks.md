# Tasks: Pantalla de administración de documentos (LAB-25)

**Input**: `specs/LAB-25-document-admin-screen/` (plan.md, spec.md, research.md, data-model.md, contracts/documents-api.md, quickstart.md)

**Prerequisites**: plan.md, spec.md. Sin migraciones de base de datos.

**Tests**: Incluidos (Constitución IV: test-first, cobertura ≥ 80 %). Escribirlos primero y verlos fallar.

## Formato: `[ID] [P?] [Story] Descripción`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: US1..US4 según spec.md

## Rutas

- Backend: `backend/src/main/java/com/shoplab/helpdesk/retrieval/` (tests espejo en `backend/src/test/java/...`)
- Frontend: `frontend/src/app/`
- Docs: `docs/`

---

## Phase 1: Setup

- [X] T001 Verificar rama `feature/LAB-25-document-admin-screen` y que `mvn test -pl backend -am` y `npm test` del frontend están en verde (línea base)
- [X] T002 [P] Añadir `shoplab.retrieval.upload.max-bytes` (10485760) y límites multipart (`spring.webflux.multipart.*`) en `backend/src/main/resources/application.yml`
- [X] T003 [P] Añadir nombres de métricas (`retrieval.upload`, `retrieval.documents`) en `retrieval/domain/model/constants/RetrievalConstants.java`

---

## Phase 2: Foundational (bloquea todas las historias)

**⚠️ CRÍTICO**: ninguna historia empieza antes de terminar esta fase.

- [X] T004 [P] Crear `retrieval/config/UploadProperties.java` (`maxBytes`) + test de binding
- [X] T005 [P] Crear `retrieval/domain/model/DocumentSummary.java` (`id`, `name`, `title`, `status`, `chunkCount`, `error`, `updatedAt`, `indexedAt`)
- [X] T006 Test de `JdbcKnowledgeBaseAdapter` (Testcontainers): `replaceDocument` devuelve `replaced=false` para nombre nuevo y `true` para existente
- [X] T007 Añadir `boolean replaced` a `retrieval/domain/model/IngestedDocument.java` y `RETURNING id, (xmax = 0) AS inserted` en `UPSERT_DOCUMENT` de `JdbcKnowledgeBaseAdapter.java`; ajustar usos y tests existentes (depende de T006)
- [X] T008 [P] Crear puerto `application/port/out/UploadMetricsPort.java` y `infrastructure/out/metrics/MicrometerUploadMetrics.java` (timer `retrieval.upload{outcome=ok|rejected|failed}`, sin nombres ni texto como etiqueta) + test
- [X] T009 [P] Ampliar `apiErrorInterceptor` / `core/api/api-error.ts` para exponer `code` y `message` del cuerpo `ErrorResponse` y evitar el snackbar duplicado con errores de negocio; actualizar specs existentes

**Checkpoint**: base lista; las historias pueden avanzar.

---

## Phase 3: US1 — Subir un documento (P1) 🎯 MVP

**Objetivo**: `POST /api/documents` ingiere el archivo y dispara embeddings por el evento existente; la pantalla permite elegir y subir.

**Test independiente**: subir un `.md` válido; aparece en la lista como pendiente y luego indexado; el chat lo cita.

### Tests (escribir primero)

- [X] T010 [P] [US1] `IngestDocumentUseCaseTest`: registra métrica `ok`/`rejected`/`failed` según resultado (modifica caso de uso)
- [X] T011 [P] [US1] `DocumentControllerTest` (`WebTestClient`, `@Import(GlobalExceptionHandler)`): 201 nuevo, 200 reemplazo, 400 sin parte `file` (`SLHD_GENE_00002`), 422 `SLHD_RETR_00003`, 415 `SLHD_RETR_00001`, 413 `SLHD_RETR_00002` (límite y `DataBufferLimitException`), 503 `SLHD_RETR_00004`, 502 `SLHD_RETR_00005`, 503 `SLHD_DBAS_00001`, cabecera `X-Request-Id`
- [X] T012 [P] [US1] `DocumentResponseMapperTest` (mapeo explícito a `DocumentUploadResponse`)
- [X] T013 [P] [US1] Test BlockHound de la subida (sin llamadas bloqueantes en el hilo reactivo)
- [X] T014 [P] [US1] Frontend: `upload-validation.spec.ts` (extensión `.pdf`/`.md`/`.markdown`, tamaño ≤ 10 MiB, mensajes en español)

### Implementación backend

- [X] T015 [US1] Modificar `IngestDocumentUseCase.java` para registrar la métrica y log INFO (nombre, resultado, chunks, `requestId`; sin texto del documento) (depende de T007, T008)
- [X] T016 [P] [US1] Crear `infrastructure/in/rest/dto/DocumentUploadResponse.java`
- [X] T017 [US1] Crear `infrastructure/in/rest/util/mapper/DocumentResponseMapper.java` (MapStruct) (depende de T016)
- [X] T018 [US1] Crear `infrastructure/in/rest/DocumentController.java` con `POST /api/documents`: `@RequestPart("file") FilePart`, `DataBufferUtils.join(content, maxBytes)` liberando buffers, nombre = último segmento, 201/200 según `replaced`, `@LogExecution` (depende de T004, T015, T017)
- [X] T019 [US1] Verificar en `GlobalExceptionHandler` el mapeo de `DataBufferLimitException`/parte ausente a códigos existentes (añadir handler solo si falta; con su test)

### Implementación frontend

- [X] T020 [P] [US1] Crear `features/documents/upload-validation.ts` (depende de T014)
- [X] T021 [P] [US1] Crear `features/documents/documents-api.client.ts` (`upload(file)` con `FormData`)
- [X] T022 [US1] Selector de archivos múltiple + arrastrar y soltar con atributo `accept` en `features/documents/documents.ts/.html/.scss`
- [X] T023 [US1] Mensajes de rechazo y de servicio no disponible en español con opción «Reintentar» conservando el archivo (escenarios 4 y 5); indicar «actualizado» cuando `replaced=true`

**Checkpoint**: se puede subir y ver el resultado (nombre y chunks) de un documento.

---

## Phase 4: US3 — Lista de documentos (P1)

**Objetivo**: `GET /api/documents` y tabla con estado, chunks y causa.

**Test independiente**: con documentos en los tres estados, cada fila muestra nombre, estado, chunks y causa.

### Tests (escribir primero)

- [X] T024 [P] [US3] `JdbcDocumentCatalogAdapterTest` (Testcontainers): `LEFT JOIN` cuenta chunks reales (incluye 0 y chunks sin vector), orden `updated_at DESC, id`, estados y `index_error`
- [X] T025 [P] [US3] `ListDocumentsUseCaseTest` (Mockito + StepVerifier): éxito, vacío, error de puerto propagado
- [X] T026 [P] [US3] Ampliar `DocumentControllerTest`: `GET` 200 con lista, `[]`, 503 `SLHD_DBAS_00001`
- [X] T027 [P] [US3] Frontend: `document-status.spec.ts` (etiquetas y diccionario de causas R7 + texto por defecto «Falló la indexación del documento»)
- [X] T028 [P] [US3] Frontend: `documents-store.spec.ts` y `document-table` spec (lista, vacío, error de carga con última lista conservada)

### Implementación backend

- [X] T029 [P] [US3] Crear `application/port/out/DocumentCatalogPort.java` y `application/port/in/ListDocumentsServicePort.java`
- [X] T030 [US3] Crear `application/usecase/ListDocumentsUseCase.java` (solo `@RequiredArgsConstructor`, cableado manual en la configuración existente de `retrieval`) (depende de T029)
- [X] T031 [US3] Crear `infrastructure/out/persistence/JdbcDocumentCatalogAdapter.java` con `BlockingCalls.stream` (depende de T029)
- [X] T032 [P] [US3] Crear `dto/DocumentSummaryResponse.java` y ampliar `DocumentResponseMapper` (depende de T017)
- [X] T033 [US3] Añadir `GET /api/documents` a `DocumentController` (depende de T018, T030, T032)
- [X] T034 [US3] Gauge `retrieval.documents{status}` en el adaptador de métricas (sin nombres como etiqueta)

### Implementación frontend

- [X] T035 [P] [US3] Crear `features/documents/document-status.ts`
- [X] T036 [US3] Ampliar `documents-api.client.ts` con `list()` y crear `documents-store.ts` (señales: lista, carga, error)
- [X] T037 [US3] Crear `document-table/` (cabeceras y nombre accesible, estado con texto + icono, causa en español, fecha) y estado vacío con invitación a subir

**Checkpoint**: la lista muestra el estado real y los chunks.

---

## Phase 5: US2 — Progreso de la subida (P1)

**Objetivo**: porcentaje, «procesando», cancelar y reintentar.

**Test independiente**: subir un archivo grande y ver avance, luego «procesando» hasta la respuesta; cancelar no deja fila.

### Tests (escribir primero)

- [X] T038 [P] [US2] `upload-list` spec + store con `HttpTestingController`: `UploadProgress` → `sending` con % monótono; fin de envío → `processing`; `HttpResponse` → `done`; `unsubscribe` → `cancelled`; error de red → `failed` + reintento
- [X] T039 [P] [US2] Spec de cola secuencial (1 archivo a la vez; el fallo de uno no detiene los demás, escenario 6 de US1)
- [X] T040 [P] [US2] Spec de accesibilidad: `role="progressbar"` con `aria-valuenow`, estado anunciado con texto

### Implementación

- [X] T041 [US2] `documents-api.client.ts`: `post` con `reportProgress: true, observe: 'events'` (depende de T021)
- [X] T042 [US2] `documents-store.ts`: cola secuencial y máquina de estados `queued→sending→processing→done|failed|cancelled|rejected`, cancelar, reintentar (depende de T036, T041)
- [X] T043 [US2] Crear `upload-list/` con barra de progreso, estado «Procesando…», botones cancelar/reintentar operables por teclado
- [X] T044 [US2] Tras `done`, refrescar la lista y no dejar fila para subidas canceladas ni fallidas

**Checkpoint**: subida con progreso completa y cancelable.

---

## Phase 6: US3/US4 — Refresco acotado, navegación y responsive (P2)

**Test independiente**: llegar desde el menú y abrir a 360 px; una fila pendiente pasa a indexado sin recargar.

### Tests (escribir primero)

- [X] T045 [P] [US3] Spec de refresco con tiempo virtual: sondeo cada 3 s solo con `PENDING`, parada al salir y a los 10 min, backoff a 10 s tras 5 errores consecutivos
- [X] T046 [P] [US4] Spec de rutas: `/documentos` carga perezosa, entrada «Documentos» en la navegación, ruta inexistente conserva «no encontrado»

### Implementación

- [X] T047 [US3] Sondeo acotado en `documents-store.ts` (R8) y aviso con «Reintentar» al perder conexión conservando la última lista
- [X] T048 [P] [US4] Añadir ruta `documentos` y enlace «Documentos» en `frontend/src/app/app.routes.ts` y la navegación
- [X] T049 [P] [US4] Estilos responsive en `documents.scss` / subcomponentes (sin scroll horizontal de página a 360 px)
- [X] T050 [US4] Revisión de accesibilidad por teclado y lector de pantalla de toda la pantalla

---

## Phase 7: Cierre y transversal

- [X] T051 [P] Crear `docs/adr/0021-subida-y-administracion-de-documentos.md` (multipart sobre WebFlux, límite, reemplazo, estado por consulta, deuda de autenticación)
- [X] T052 [P] Actualizar `docs/modulos.md`, `docs/modulos/module-retrieval.adoc` y `docs/base-conocimiento.md` (flujo de subida desde la pantalla)
- [X] T053 Revisar/ajustar `proxy-body-size` del Ingress del backend en el repo `shoplab-infra` (≥ max-bytes + margen multipart; R3)
- [X] T054 [P] Crear `specs/LAB-25-document-admin-screen/requests.http` con los escenarios de la spec
- [X] T055 Test Spring Modulith (`retrieval` sigue siendo dueño de su esquema, sin dependencias nuevas entre módulos)
- [X] T056 `mvn checkstyle:check`, `mvn verify` (cobertura ≥ 80 %) y BlockHound en verde; `npm run lint && npm test -- --watch=false && npm run build`
- [X] T057 Ejecutar `quickstart.md` de extremo a extremo y confirmar que LAB-12, LAB-20..24 siguen cumpliéndose (FR-016, SC-007)

---

## Dependencias y orden

- Setup → Foundational → historias. Dentro de cada una: tests → modelos/puertos → casos de uso/adaptadores → controlador → frontend.
- **US1** (backend T015–T019) y **US3** (backend T029–T034) comparten `DocumentController` y `DocumentResponseMapper`: T033 y T032 dependen de T018 y T017.
- **US2** depende del cliente y la pantalla de US1 (T021, T022) y del store de US3 (T036).
- La Fase 6 depende de US1, US2 y US3.
- Cierre depende de todo lo anterior; T053 puede avanzar en paralelo con el frontend.

## Oportunidades de paralelismo

- Setup: T002 y T003. Foundational: T004, T005, T008, T009.
- Tests de cada historia (T010–T014, T024–T028, T038–T040) entre sí.
- Backend y frontend de una misma historia corren en paralelo tras fijar el contrato.

## Estrategia

1. **MVP**: Fases 1–3 (subida) + lista básica de Fase 4 → validar con `quickstart.md` §1–§3.
2. Añadir progreso (Fase 5), luego refresco, navegación y accesibilidad (Fase 6).
3. Cerrar con ADR, infra, `requests.http` y cobertura (Fase 7).

**Total**: 57 tareas (Setup 3, Foundational 6, US1 14, US3 14, US2 7, Fase 6 6, Cierre 7).
