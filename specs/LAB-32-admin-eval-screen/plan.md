# Implementation Plan: Pantalla de evolución de métricas (admin-eval)

**Branch**: `feature/LAB-32-admin-eval-screen` | **Date**: 2026-11-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-32-admin-eval-screen/spec.md`

## Summary

Una pantalla `admin-eval` de solo lectura que convierte el historial de LAB-31 en una gráfica por ejecución, el detalle de los
casos que fallan y la comparación entre dos ejecuciones. LAB-31 dejó fuera la lectura para el frontend (su ADR-0028, decisión
8); esta historia la define.

Enfoque (detalle y alternativas en [research.md](research.md)):

- **El módulo `eval` del backend gana una lectura de solo lectura** (`/api/eval/history/…`, tres endpoints, **sin token**:
  el token de guardado no viaja al navegador). Es el mismo módulo, el mismo esquema y la misma hexagonal; **no hay migración
  ni tabla nueva**. Las medias se calculan **en SQL** sobre `eval_result.scores` (una sola implementación, la misma que las
  consultas documentadas de `eval/queries/`), y la **comparación se calcula en el backend** con `BigDecimal`, con la misma
  regla que `07-regressions.sql`, para que la pantalla y las consultas coincidan (FR-019).
- **El criterio de «caso que falla» vive en el cliente** (umbral que elige quien mira, FR-010): la ejecución llega entera
  (≈ 100 resultados) y filtrar es una función pura, así que mover el umbral no hace peticiones.
- **Frontend**: pantalla nueva `features/eval/` de Angular + Material (ADR-0013), carga perezosa en la ruta `admin-eval` y
  entrada en la navegación. **La gráfica es un componente SVG propio, sin biblioteca nueva**: huecos reales para los valores sin
  valor, puntos enfocables con teclado y una tabla equivalente. Estado con `signal`s en un store provisto por la pantalla (el
  patrón de `documents`).
- **Enlace a Langfuse**: el backend construye la dirección de la traza a partir de una plantilla configurable
  (`shoplab.eval.history.trace-url-template`); sin plantilla, la pantalla muestra el identificador para copiarlo.
- Una decisión arquitectónica nueva (lectura del historial sin token + gráfica sin biblioteca): **ADR-0029**.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 4.x, WebFlux, Spring Modulith) para la lectura; TypeScript 6 / Angular 22 con
Angular Material 22 para la pantalla.

**Primary Dependencies**: backend, `JdbcTemplate` aislado con `BlockingCalls` (ADR-0011) y Jackson 3 (`tools.jackson`), ya
usados por el módulo; frontend, `@angular/material`, `@angular/cdk` (`BreakpointObserver`), Router y `HttpClient`, ya
instalados. **Ninguna dependencia nueva** en ninguno de los dos lados.

**Storage**: PostgreSQL 16, esquema `eval` de LAB-31, **sin cambios de esquema** (`V1` y `V2` siguen siendo las migraciones del
módulo). Solo `SELECT`.

**Testing**: backend, JUnit 5 + Mockito (casos de uso y comparación), `WebTestClient` (controlador) y Testcontainers
PostgreSQL (`@PostgresTest`) para el adaptador y para la equivalencia con `eval/queries/*.sql`; ArchUnit y Modulith ya
existentes. Frontend, Vitest + jsdom (`ng test`): funciones puras (series, fallos, escala), store, cliente HTTP y
componentes. Sin red ni modelo de lenguaje (Constitución IV).

**Target Platform**: el navegador del administrador (escritorio y teléfono) y el backend ya desplegado. En desarrollo la
pantalla se sirve como el resto del frontend y llama a `/api` por el mismo origen (proxy de `ng serve`, nginx en la imagen).

**Project Type**: monolito modular (backend) + aplicación web (frontend).

**Performance Goals**: con 100 ejecuciones guardadas (≈ 100 resultados cada una, ≈ 10 000 filas), la lista de ejecuciones con
sus medias responde en < 1 s y la gráfica aparece en < 3 s (SC-006); el detalle de una ejecución (≈ 100 KB) en < 2 s.

**Constraints**: solo lectura; sin token en el navegador; sin texto de respuestas ni de fragmentos en ninguna respuesta
(FR-016); sin cambiar el asistente, las métricas ni el guardado (FR-024); vacío ≠ cero en todo el recorrido (FR-005, FR-013);
cobertura ≥ 80 % del backend; Checkstyle/PMD limpios; líneas ≤ 121; ESLint y Prettier del frontend limpios.

**Scale/Scope**: una o pocas ejecuciones por día; cientos como mucho a medio plazo. La lista limita a las 100 más recientes
por defecto (máximo 500) y dice cuántas hay.

## Constitution Check

*GATE: pasa antes de la investigación y se revalida tras el diseño.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | ✅ La lectura vive en el módulo `eval`, dueño del esquema `eval`; no toca otros esquemas ni módulos (no usa `chat.api` para leer). Los límites los vigilan los tests de Modulith existentes. |
| II. Hexagonal | ✅ Puertos de entrada nuevos (`ListEvalRunsServicePort`, `GetEvalRunServicePort`, `CompareEvalRunsServicePort`), un puerto de salida de lectura (`EvalHistoryPort`) y su adaptador JDBC; el controlador solo arma el comando y llama al puerto; la comparación es lógica de dominio sin Spring. Constantes en `model/constants`, enums en `model/enums`. |
| III. Identificadores en inglés, errores tipados | ✅ Un código nuevo `SLHD_EVAL_00006` (ejecuciones no comparables) en `ResponseCodeEnum`; el resto reutiliza los de LAB-31; todo se mapea solo en `GlobalExceptionHandler`. Texto de la pantalla y documentación en español. |
| IV. Test-first, cobertura ≥ 80 % | ✅ Plan de pruebas abajo: casos de uso y comparación aislados, controlador, adaptador contra PostgreSQL real con equivalencia a las consultas SQL, y funciones puras y componentes del frontend. |
| V. Evaluation-driven RAG | ✅ No cambia el asistente ni las métricas; hace **legible** el historial que el principio exige. No define umbrales de bloqueo (son de LAB-30). |
| VI. Observabilidad y manejo seguro | ✅ Ninguna respuesta lleva texto de respuestas ni de fragmentos, claves ni tokens (prueba de SC-008). El enlace a la traza se construye con una plantilla del entorno. Registro estructurado con tipo, filtros y duración, sin contenido. |
| VII. Simplicidad y decisiones documentadas | ✅ Sin migración, sin dependencia nueva, tres endpoints. Una justificación en *Complexity Tracking* (gráfica propia). Decisión registrada como **ADR-0029**. |

**Resultado del gate**: sin violaciones injustificadas. **Revalidado tras el diseño** (Fase 1): sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-32-admin-eval-screen/
├── spec.md
├── plan.md              # este archivo
├── research.md          # Fase 0: decisiones y alternativas (R1-R15)
├── data-model.md        # Fase 1: modelos de lectura y estado de la pantalla (sin tablas nuevas)
├── quickstart.md        # Fase 1: validación de punta a punta (V1-V24)
├── contracts/
│   ├── eval-history-api.md      # los tres endpoints de lectura, errores y propiedades
│   └── admin-eval-screen.md     # contrato de la pantalla: ruta, controles, estados, accesibilidad
├── checklists/requirements.md
└── tasks.md             # Fase 2 (/speckit-tasks; no lo crea este comando)
```

### Source Code (repository root)

```text
backend/src/main/
├── java/com/shoplab/helpdesk/
│   ├── eval/                                         # MÓDULO EXISTENTE (LAB-31): solo se añade la lectura
│   │   ├── domain/
│   │   │   ├── model/
│   │   │   │   ├── MetricStat.java                   # NUEVO: media y n (la media puede ser nula)
│   │   │   │   ├── RunListItem.java, RunListing.java # NUEVO: lista con total y orígenes
│   │   │   │   ├── StoredRun.java, StoredResult.java # NUEVO: ejecución completa y resultado con su caso
│   │   │   │   ├── ScopeSummary.java                 # NUEVO: resumen del conjunto completo y por categoría
│   │   │   │   ├── RunComparison.java, CaseComparison.java, ParameterDifference.java   # NUEVO
│   │   │   │   └── enums/CaseComparisonStatus.java   # NUEVO: WORSE, BETTER, SAME, NO_VALUE, NOT_COMPARABLE, ...
│   │   │   ├── RunComparator.java                    # NUEVO: lógica pura de comparación (BigDecimal), sin Spring
│   │   │   └── exception/EvalRunsNotComparableException.java   # NUEVO
│   │   ├── application/
│   │   │   ├── port/in/ListEvalRunsServicePort.java, GetEvalRunServicePort.java, CompareEvalRunsServicePort.java
│   │   │   ├── port/out/EvalHistoryPort.java         # NUEVO: lectura (lista, ejecución, resumen, resultados)
│   │   │   └── usecase/ListEvalRunsUseCase.java, GetEvalRunUseCase.java, CompareEvalRunsUseCase.java,
│   │   │                                              # EvalHistoryQueryValidator.java
│   │   ├── infrastructure/
│   │   │   ├── in/rest/EvalHistoryController.java    # NUEVO: GET /api/eval/history/{runs, runs/{id}, compare}
│   │   │   ├── in/rest/dto/…                         # NUEVO: respuestas en snake_case (como LAB-31)
│   │   │   ├── in/rest/util/mapper/EvalHistoryMapper.java
│   │   │   └── out/persistence/JdbcEvalHistoryAdapter.java   # NUEVO: SELECT con agregados sobre el jsonb
│   │   └── config/EvalHistoryProperties.java         # NUEVO: shoplab.eval.history.trace-url-template
│   └── shared/exception/ResponseCodeEnum.java        # + EVAL_RUNS_NOT_COMPARABLE (SLHD_EVAL_00006)
└── resources/application.yml                          # + shoplab.eval.history.trace-url-template

backend/src/test/java/com/shoplab/helpdesk/eval/       # tests nuevos junto a los de LAB-31 (ver plan de pruebas)

frontend/src/app/
├── app.routes.ts                                      # + ruta `admin-eval` (carga perezosa, entrada de navegación)
└── features/eval/                                     # NUEVO
    ├── admin-eval.ts / .html / .scss                  # la pantalla; provee EvalStore
    ├── eval-history.ts                                # tipos de la API (snake_case → camelCase en el cliente)
    ├── eval-api.client.ts                             # lista, ejecución, comparación (`HANDLES_OWN_ERRORS`)
    ├── eval-store.ts                                  # estado: tipo, métricas, origen, ejecución, comparación, errores
    ├── metric-series.ts                               # función pura: series con huecos, nombres y etiquetas de métrica
    ├── case-failures.ts                               # función pura: criterio de fallo (umbral / k), orden y filtros
    ├── chart-scale.ts                                 # función pura: escalas y posiciones del SVG
    ├── metric-chart/                                  # gráfica SVG + tabla equivalente
    ├── run-list/                                      # lista de ejecuciones (elegir, referencia, comparar)
    ├── run-detail/                                    # parámetros y resumen por categoría
    ├── failing-cases/                                 # umbral, filtros, orden y caso abierto
    └── run-comparison/                                # parámetros que difieren, métricas y casos
    (cada componente con su `.spec.ts`; los tests de las funciones puras junto a ellas)

eval/README.md                                         # + la pantalla como forma de leer el historial (FR-026)

docs/
├── adr/0029-lectura-del-historial-de-evaluacion.md    # NUEVO + fila en docs/adr/README.md
├── admin-eval.md                                      # NUEVO: cómo leer la pantalla (FR-026)
├── modulos/                                           # regenerado por ModularityTests
└── modulos.md                                         # + referencia a la lectura del módulo `eval`

specs/LAB-32-admin-eval-screen/requests.http           # al terminar la implementación (regla del proyecto)
```

**Structure Decision**: la lectura vive en el módulo `eval` (no en uno nuevo) porque es el dueño del esquema y de los
modelos; se separa en un puerto de salida **propio de lectura** (`EvalHistoryPort`) en vez de ensanchar `EvalRunStorePort`,
que es el de escritura protegido por token, para que ninguna prueba ni ningún adaptador mezcle los dos caminos. En el frontend
se sigue la estructura por pantalla de `features/chat` y `features/documents`.

## Plan de pruebas (resumen)

| Capa | Qué se prueba | Spec |
|---|---|---|
| `RunComparator` (unit, sin Spring) | `WORSE`/`BETTER`/`SAME` con `drop` estricto en `BigDecimal` (el caso límite 0,05 exacto no es empeoramiento); `NO_VALUE`; `NOT_COMPARABLE` por revisión distinta; `ONLY_BASE`/`ONLY_TARGET`; parámetros que difieren y cuáles avisan | FR-017, FR-018 |
| Casos de uso (unit) | Validación (tipo desconocido, límite fuera de rango, `drop` fuera de 0–1, ids con formato inválido); ejecución inexistente → 404; tipos distintos → 422 `SLHD_EVAL_00006`; sin token en ningún camino | FR-020 |
| Adaptador JDBC (PostgreSQL real) | Medias y `n` por ejecución y por categoría = las de `03-run-summary.sql`; el hueco (`n = 0`) no es cero; orden y límite de la lista; facetas de origen; ejecución con métrica ausente | FR-007, FR-009 |
| Equivalencia con las consultas | La comparación del backend da los mismos casos que `05-compare-runs.sql` y `07-regressions.sql` con los mismos parámetros (reutiliza las fixtures de `EvalHistoryQueriesTests`) | FR-019, SC-005 |
| Controlador (`WebTestClient`) | 200, 404, 422 (con `detail`), 503; **sin** cabecera de token funciona; el cuerpo no contiene el token ni texto de respuestas | contrato, FR-020 |
| Sin texto | Ninguna respuesta de lectura incluye campos fuera de la lista del contrato; una ejecución real no filtra texto de respuesta | FR-016, SC-008 |
| Arquitectura | Reglas de capas, Modulith, aislamiento de esquema (existentes); el módulo sigue sin depender de nada más que `chat.api` | ADR-0015 |
| Funciones puras (Vitest) | Series con huecos y orden estable; fallo por umbral (con y sin valor) y por k; orden y filtros; escala con 1 punto, 0 puntos y valores extremos | FR-005, FR-011–013, FR-023 |
| Store y cliente (Vitest) | Estados cargando / vacío / datos / error con reintento; cambio de tipo no mezcla series; filtro de origen informa de los ocultos; carga perezosa del detalle; sin sondeo | FR-003, FR-006, FR-021 |
| Componentes (Vitest + jsdom) | Gráfica: un punto por ejecución, hueco sin línea, foco y descripción de cada punto, tabla equivalente; detalle: «sin valor» ≠ 0; comparación: no comparable y advertencias; navegación y roles ARIA | FR-002, FR-004, FR-022 |
| Manual | Escenarios V1–V24 de [quickstart.md](quickstart.md) en escritorio y teléfono, con teclado y con lector de pantalla | SC-001, SC-002, SC-006, SC-009, SC-010 |

## Cableado del entorno de desarrollo

- **Sin infraestructura nueva**: la lectura no necesita secretos, tablas ni servicios. El único ajuste de configuración es
  opcional (`SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE`, por defecto `http://localhost:3001/trace/{traceId}`, la dirección de
  Langfuse del perfil `llmops`); sin él la pantalla muestra el identificador de la traza (FR-015).
- **FR-027 (DEV es el entorno local)**: el backend de `make up` debe incluir el módulo con la lectura, y ADR-0012 solo despliega
  versiones de release, así que la comprobación en el clúster necesita el **primer tag que incluya esta historia** (igual que
  LAB-31); la verificación local con `ng serve` + backend desde el IDE no lo necesita. El frontend ya se sirve con el proxy
  a `/api`, sin cambios de nginx.
- No hay cambio en `shoplab-infra`: se confirma al implementar (V24) y se registra si hiciera falta.

## Complexity Tracking

| Desviación | Por qué hace falta | Alternativa más simple descartada |
|---|---|---|
| Un componente de gráfica SVG propio (≈ 200 líneas) en vez de una biblioteca | ADR-0013 fija Angular Material como única biblioteca de componentes y Material no trae gráficas; una biblioteca de gráficos añade una dependencia (y un ADR de sustitución) para dibujar una línea con huecos y ≤ 5 series. El SVG da control exacto de los huecos (FR-005), del foco por teclado (FR-004) y de la alternativa textual. | Chart.js / ngx-charts / ECharts: dependencia nueva de cientos de KB, canvas sin árbol accesible y huecos configurables pero propensos a dibujar un cero. Ver [research.md](research.md) R4. |
