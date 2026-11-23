# Implementation Plan: ADR de extracción temprana y mapa de servicios

**Branch**: `feature/LAB-128-early-extraction-service-map` | **Date**: 2026-11-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-128-early-extraction-service-map/spec.md`

## Summary

Establecer la gobernanza documental y arquitectónica para la descomposición temprana del backend de ShopLab Helpdesk tras la fase F2 (Sprints 19 a 24), documentando el mapa de los 8 servicios de destino, formalizando la adopción del protocolo Model Context Protocol (MCP) para la invocación de herramientas de agente sobre la red, prohibiendo librerías comunes de dominio en favor de la plantilla de servicio de LAB-103, y delimitando las responsabilidades de `eval-service` (Java) y `shoplab-eval` (Python).

Enfoque técnico (detalle en [research.md](research.md)):

- **Registro formal de `ADR-0031` («Extracción temprana de servicios»)**:
  - Resuelve la colisión con la denominación en Jira (donde se citaba ADR-0029, número previamente asignado a LAB-32 en el repositorio) adoptando la numeración secuencial libre `ADR-0031` en `docs/adr/0031-extraccion-temprana-de-servicios.md`.
  - Actualiza el estado de `docs/adr/0001-monolito-modular-primero.md` y `docs/adr/0015-limites-de-contexto-y-repositorios.md` a `aceptado (parcialmente sustituido por ADR-0031)`, y actualiza el índice `docs/adr/README.md`.
- **Publicación del mapa de servicios en `docs/mapa-servicios.md`**:
  - Especifica los 8 servicios: `chat-service`, `retrieval-service`, `eval-service`, `catalog-service`, `order-service`, `ticket-service`, `gateway` y `notification-service`.
  - Para cada servicio se define: esquema propio en PostgreSQL (sin queries cruzadas ni FKs entre esquemas), catálogo de APIs expuestas (REST, SSE, MCP), eventos de Kafka publicados/consumidos (enlazados con `shoplab-contracts`, LAB-108) y dependencias técnicas.
- **Herramientas de agente distribuidas mediante Model Context Protocol (MCP)**:
  - Traslada la regla de `@Tool` de ADR-0015 al ámbito de red: los servicios dueños (`catalog`, `order`, `ticket`, `retrieval`) levantan servidores MCP con JSON Schema declarativo, y `chat-service` actúa como cliente MCP remoto sobre HTTP/SSE.
- **Política de código compartido (ADR-0010) y plantilla de servicio**:
  - Prohibición estricta de librerías comunes con lógica o entidades de dominio. La infraestructura base técnica (errores, correlación, logging) se obtendrá como copia inicial desde la plantilla de `LAB-103`.
- **Separación entre `eval-service` (Java) y `shoplab-eval` (Python)**:
  - `eval-service` (Java, repo `shoplab-eval-service`) custodia el esquema `eval` y las APIs de `admin-eval`.
  - `shoplab-eval` (Python, repo `shoplab-eval`) administra el dataset golden (`golden.jsonl`) y corre la suite RAGAS/DeepEval en CI.

---

## Technical Context

**Language/Version**: Documentación en Markdown (UTF-8, LF) en español; esquemas OpenAPI 3.1, JSON Schema y especificaciones JSON-RPC 2.0 (MCP). Código del ecosistema en Java 21 (Spring Boot 4) y Python ≥ 3.12.

**Primary Dependencies**: Ninguna dependencia nueva en tiempo de ejecución. Herramientas documentales y linters de Markdown; protocolos de integración definidos: Model Context Protocol (MCP 2024-11), CloudEvents / Avro (para Kafka en LAB-108) y OpenAPI.

**Storage**: PostgreSQL 16 con esquemas aislados por servicio (`chat`, `retrieval`, `eval`, `catalog`, `orders`, `tickets`, `notification`). Cero persistencia compartida.

**Testing**: Validación estática de enlaces y Markdown, verificación de tests de arquitectura de Modulith (`ModularityTests`, `ModuleArchitectureTests`) para confirmar que el monolito actual sigue íntegro antes de ejecutar las extracciones físicas.

**Target Platform**: Repositorio `shoplab-helpdesk` (documentación en `docs/` y `docs/adr/`).

**Project Type**: Documentación de arquitectura, contratos y registros de decisiones (ADR).

**Performance Goals**: N/A para los artefactos documentales; para los contratos MCP definidos: llamadas con timeout estricto ≤ 3 s y degradación elegante sin bloqueo del streaming.

**Constraints**: Markdown en español; enlaces relativos válidos; inmutabilidad de ADRs aceptados; numeración secuencial de 4 dígitos; estricta adhesión a los 7 principios de la Constitución de ShopLab Helpdesk.

**Scale/Scope**: 1 ADR nuevo (`0031`), 2 ADRs actualizados (`0001`, `0015`), 1 índice actualizado (`docs/adr/README.md`), 1 mapa de servicios completo (`docs/mapa-servicios.md`) con 8 servicios.

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Evaluación | Estado |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | El principio establece que F0-F3 corren como monolito modular y que la extracción a servicios con Kafka ocurre solo desde F4/F5 **y requiere un ADR**. Esta historia cumple exactamente este mandato creando el **ADR-0031** que formaliza el adelantamiento motivado por el desbalance de código. | ✅ PASS |
| **II. Hexagonal Architecture and Domain Purity** | Cada uno de los 8 servicios mantiene su arquitectura hexagonal. El protocolo MCP asegura que `chat-service` use un puerto de salida hacia las herramientas sin importar dependencias de dominio de otros servicios. | ✅ PASS |
| **III. English Identifiers, Typed Errors** | Documentación en español; nombres de servicios, APIs, tópicos de Kafka y herramientas MCP en inglés. Cada servicio gestiona sus propios errores tipados sin librerías compartidas de dominio. | ✅ PASS |
| **IV. Test-First with Coverage Gate** | No introduce código de aplicación en `backend/` en esta historia. Las pruebas del backend y de Modulith se mantienen al 100 % en verde. | ✅ PASS |
| **V. Evaluation-Driven RAG** | Clarifica el rol de `shoplab-eval` (Python, dataset golden y RAGAS en CI) frente a `eval-service` (Java, persistencia histórica), protegiendo la rigurosidad de las evaluaciones de RAG. | ✅ PASS |
| **VI. Observabilidad y Manejo Seguro** | Define la propagación obligatoria de `traceparent` y correlación en todas las llamadas MCP y eventos de Kafka, manteniendo la privacidad en logs y métricas. | ✅ PASS |
| **VII. Simplicidad y Decisiones Documentadas** | Todas las decisiones se registran como ADRs en `docs/adr/` siguiendo la plantilla estándar, actualizando estados históricos y documentando el mapa de destino en `docs/`. | ✅ PASS |

**Gate de Constitución**: **PASA** sin violaciones ni desviaciones injustificadas.

---

## Project Structure

### Documentation (this feature)

```text
specs/LAB-128-early-extraction-service-map/
├── spec.md                          # Especificación funcional de requisitos
├── plan.md                          # Este plan de implementación
├── research.md                      # Fase 0: Decisiones R1-R6, numeración y análisis
├── data-model.md                    # Fase 1: Soberanía de esquemas, entidades y herramientas MCP
├── quickstart.md                    # Fase 1: Guía de revisión y validación
├── checklists/
│   └── requirements.md              # Checklist de calidad de requisitos
└── contracts/
    ├── service-map-schema.md        # Fase 1: Esquema de los 8 servicios de destino
    └── mcp-tool-protocol.md         # Fase 1: Protocolo de integración MCP en red
```

### Source Code / Repositorio (`docs/`)

```text
docs/
├── mapa-servicios.md                # [NUEVO] Mapa de los 8 servicios de destino
└── adr/
    ├── 0000-plantilla.md
    ├── 0001-monolito-modular-primero.md                 # [MODIFICADO] Estado: parcialmente sustituido por 0031
    ├── 0015-limites-de-contexto-y-repositorios.md       # [MODIFICADO] Estado: parcialmente sustituido por 0031
    ├── 0031-extraccion-temprana-de-servicios.md         # [NUEVO] ADR de extracción temprana
    └── README.md                                        # [MODIFICADO] Índice con 0031 y estados actualizados
```

---

## Plan de Ejecución por Fases

### Fase 0: Investigación y resolución de alternativas (Completada en `research.md`)
- [x] **R1**: Resolución de la numeración del ADR (adopción de `ADR-0031` para evitar colisión con LAB-32 y LAB-33).
- [x] **R2**: Definición del calendario de extracción (S19–S24) y nacimiento directo de `catalog`, `orders` y `tickets`.
- [x] **R3**: Delimitación de los 8 servicios y soberanía de esquemas de datos.
- [x] **R4**: Adopción de Model Context Protocol (MCP) para herramientas de agente en red.
- [x] **R5**: Regla de código compartido: prohibición de JAR común con dominio y uso de plantilla LAB-103.
- [x] **R6**: Delimitación de `eval-service` (Java) y `shoplab-eval` (Python).

### Fase 1: Diseño de artefactos y contratos (Completada en `contracts/` y `data-model.md`)
- [x] **`data-model.md`**: Diagramas ER de soberanía de esquemas, modelo conceptual MCP y trazabilidad de ADRs.
- [x] **`contracts/service-map-schema.md`**: Ficha técnica detallada de los 8 servicios (esquema, APIs, eventos, dependencias).
- [x] **`contracts/mcp-tool-protocol.md`**: Protocolo JSON-RPC 2.0 para descubrimiento y ejecución de herramientas MCP.
- [x] **`quickstart.md`**: Guía paso a paso para revisión y verificación de los artefactos.

### Fase 2: Implementación de la documentación en el repositorio (A definir en `tasks.md`)
1. **Creación de `docs/adr/0031-extraccion-temprana-de-servicios.md`**.
2. **Actualización de `docs/adr/0001-monolito-modular-primero.md`** y **`docs/adr/0015-limites-de-contexto-y-repositorios.md`**.
3. **Actualización del índice `docs/adr/README.md`**.
4. **Creación de `docs/mapa-servicios.md`**.
5. **Verificación integral de enlaces y pruebas del repositorio**.

---

## Complexity Tracking

| Complejidad detectada | Justificación | Alternativa más simple descartada |
|---|---|---|
| Uso de MCP en red en lugar de llamadas REST directas | MCP es el estándar nativo para herramientas de LLM; evita diseñar un protocolo propietario de RPC que se volvería obsoleto al incorporar agentes en F4. | Endpoints REST ad-hoc con DTOs propios: requeriría versionar cientos de DTOs en `shoplab-contracts` y acoplar el orquestador a firmas estáticas. |
| Creación de 8 esquemas de base de datos aislados | Cumple el Principio de Soberanía de Datos y habilita la extracción de bases de datos independientes en Kubernetes sin migraciones traumáticas. | Base de datos compartida con prefijos: acopla los esquemas y facilita violaciones accidentales de integridad referencial cruzada. |
| Replicación de infraestructura técnica desde plantilla | Garantiza autonomía completa en el ciclo de vida y despliegue de cada microservicio. | Librería común en Maven: tiende a convertirse en un monolito distribuido y fuerza despliegues en cascada. |
