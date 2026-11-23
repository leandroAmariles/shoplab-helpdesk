# Feature Specification: ADR de extracción temprana y mapa de servicios

**Feature Branch**: `feature/LAB-128-early-extraction-service-map`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "LAB-128 (F5-21) ADR de extracción temprana y mapa de servicios. Como miembro del equipo, quiero registrar la decisión de extraer los servicios tras F2 y el mapa de servicios de destino, para que el nuevo calendario no contradiga ADR-0001 ni ADR-0015 sin dejar rastro. Contexto: el backend crece (chat ~5.600 líneas, retrieval ~2.400, eval ~1.400) y `orders`, `catalog` y `tickets` siguen vacíos. Se decide extraer retrieval, chat y eval en S19–S24 y que catalog, order y ticket nazcan como servicio en F3/F4 en lugar de construirse en el monolito y extraerse en F5. Criterios de aceptación: ADR-0029/ADR-0031 «Extracción temprana de servicios» que sustituye el calendario de ADR-0001 (extraer desde F4) y ADR-0015 (repos por servicio desde F5); ambos quedan marcados como sustituidos en ese punto; Mapa de servicios en `docs/`: chat, retrieval, eval, catalog, order y ticket, más gateway y notification; para cada uno, esquema propio, API que expone, eventos que publicará y dependencias; Las herramientas del agente se publican por MCP en el servicio dueño y `chat` es cliente MCP (la regla de `@Tool` en la `api` de ADR-0015 llevada a red); Decisión sobre el módulo `shared` (ADR-0010): cada servicio lleva su copia desde la plantilla de LAB-103, sin librería común con dominio; eval-service en Java con repo propio, a partir del módulo `eval`; el repo `shoplab-eval` queda para la herramienta Python. Va antes de: LAB-108, LAB-103 y el resto del bloque de extracción."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Registro del ADR de extracción temprana de servicios y actualización de decisiones previas (Priority: P1)

Como arquitecto y desarrollador del equipo, quiero registrar en un ADR formal la decisión de adelantar la extracción de servicios a la finalización de F2 (Sprints 19–24) y actualizar los ADRs históricos vigentes (ADR-0001 y ADR-0015), para que el equipo cuente con una justificación documentada, trazable e indiscutible que explique por qué no se espera a F4 o F5 para descomponer el sistema.

**Contexto y motivación**:
El backend modular actual ha crecido de forma asimétrica: los módulos con implementación real acumulan miles de líneas de código y lógica compleja (`chat` con ~5.600 líneas, `retrieval` con ~2.400 líneas, `eval` con ~1.400 líneas), mientras que los módulos de negocio transaccional (`orders`, `catalog` y `tickets`) permanecen como esqueletos estructurales vacíos sin tablas ni lógica. Esperar a F4/F5 como dictaban ADR-0001 y ADR-0015 implicaría construir `orders`, `catalog` y `tickets` dentro del monolito para inmediatamente después tener que extraerlos, pagando un doble coste de desarrollo, refactorización y migración. Se decide:
1. Extraer los módulos maduros (`retrieval`, `chat` y `eval`) a repositorios y servicios independientes en la ventana S19–S24.
2. Hacer que `catalog`, `order` y `ticket` nazcan directamente como microservicios independientes en F3 y F4, sin pasar por el monolito.

**Numeración del ADR**:
El enunciado original de Jira proponía `ADR-0029`. Sin embargo, conforme a las reglas del repositorio (`docs/adr/README.md`), la numeración es estrictamente secuencial y única de 4 dígitos. Dado que `ADR-0029` (`0029-lectura-del-historial-de-evaluacion.md`, LAB-32) y `ADR-0030` (`0030-feedback-de-respuestas-y-promocion-a-golden.md`, LAB-33) ya fueron aceptados y fusionados, este ADR toma el siguiente número libre disponible: **`ADR-0031: Extracción temprana de servicios`**, documentando explícitamente su equivalencia con la decisión de extracción acordada en Jira LAB-128.

**Why this priority**: Es la base normativa y contractual del cambio arquitectónico. Sin este ADR, cualquier extracción contradice directamente la Constitución del proyecto (Principio I) y los ADR-0001 y ADR-0015.

**Independent Test**: Inspeccionar el directorio `docs/adr/`, verificar la existencia de `0031-extraccion-temprana-de-servicios.md` con sus secciones de Contexto, Decisión y Consecuencias completas, comprobar la actualización de estado en `0001-monolito-modular-primero.md` y `0015-limites-de-contexto-y-repositorios.md` a `aceptado (parcialmente sustituido por ADR-0031)`, y verificar que el índice `docs/adr/README.md` refleja los tres cambios de manera consistente.

**Acceptance Scenarios**:

1. **Given** el directorio `docs/adr/`, **When** se revisan los archivos de decisiones, **Then** existe el archivo `0031-extraccion-temprana-de-servicios.md` en español, con estado `aceptado`, fecha actual, y secciones explícitas de Contexto, Decisión y Consecuencias detallando la extracción en S19–S24 y el nacimiento directo de servicios en F3/F4.
2. **Given** el archivo `docs/adr/0001-monolito-modular-primero.md`, **When** se revisa su encabezado y contenido, **Then** su estado indica `aceptado (parcialmente sustituido por ADR-0031)` con una nota que referencia la extracción temprana tras F2 en lugar de esperar a F4.
3. **Given** el archivo `docs/adr/0015-limites-de-contexto-y-repositorios.md`, **When** se revisa su encabezado y contenido, **Then** su estado indica `aceptado (parcialmente sustituido por ADR-0031)` con una nota que referencia que los repositorios por servicio se establecen desde S19 en lugar de esperar a F5.
4. **Given** el índice general `docs/adr/README.md`, **When** se inspecciona la tabla de decisiones, **Then** aparecen listados ADR-0001, ADR-0015 y ADR-0031 con sus estados exactos y enlaces relativos válidos.

---

### User Story 2 - Definición exhaustiva del mapa de servicios de destino en `docs/mapa-servicios.md` (Priority: P1)

Como ingeniero de software y DevOps, quiero un documento integral de referencia en `docs/mapa-servicios.md` que detalle la topología completa de los 8 servicios del ecosistema ShopLab Helpdesk (`chat`, `retrieval`, `eval`, `catalog`, `order`, `ticket`, `gateway` y `notification`), especificando para cada uno su esquema de datos, su API expuesta, los eventos que publicará/consumirá y sus dependencias técnicas e inter-servicio, para disponer de un plano unívoco que guíe la extracción y el diseño de contratos.

**Detalle de los servicios a documentar**:
1. **`chat-service`**: Orquestador conversacional con streaming SSE, memoria de sesión en base de datos propia, cliente de modelos LLM y cliente MCP de herramientas.
2. **`retrieval-service`**: Motor de búsqueda semántica e híbrida, gestión de documentos y fragmentos con embeddings vectoriales en pgvector.
3. **`eval-service`**: Servicio Java de registro, persistencia y consulta del historial de evaluaciones RAG y feedback humano de respuestas.
4. **`catalog-service`**: Servicio de productos, categorías e inventario de la tienda online; nace como microservicio independiente en F3; expone herramientas vía MCP.
5. **`order-service`**: Servicio de pedidos, estados de entrega y tracking de clientes; nace como microservicio independiente en F4; expone herramientas vía MCP.
6. **`ticket-service`**: Servicio de gestión y escalado de incidencias y soporte técnico; nace como microservicio independiente en F4; expone herramientas vía MCP.
7. **`gateway` (API Gateway)**: Punto de entrada único para clientes frontend y externos; gestiona enrutamiento perimetral, agregación y terminación SSL.
8. **`notification-service`**: Servicio reactivo asíncrono consumidor de eventos de dominio en Kafka para emisión de correos y alertas transaccionales.

**Why this priority**: Constituye la especificación técnica fundamental de la arquitectura distribuida. Define las fronteras de datos y de red necesarias para que historias como `LAB-108` (contratos OpenAPI/Avro) y `LAB-103` (plantilla de servicio) puedan ejecutarse sin ambigüedades.

**Independent Test**: Leer `docs/mapa-servicios.md` y verificar que cada uno de los 8 servicios cuenta con una ficha estandarizada que contiene: (1) Responsabilidad y contexto delimitado, (2) Esquema de datos PostgreSQL exclusivo, (3) APIs expuestas (REST / SSE / MCP), (4) Eventos asíncronos en Kafka (productores y consumidores), y (5) Dependencias upstream y downstream (bases de datos, brokers, servicios, pasarelas de modelos).

**Acceptance Scenarios**:

1. **Given** el directorio `docs/`, **When** se consulta `docs/mapa-servicios.md`, **Then** contiene una introducción que justifica la topología distribuida y una matriz o ficha detallada para cada uno de los 8 servicios requeridos: `chat`, `retrieval`, `eval`, `catalog`, `order`, `ticket`, `gateway` y `notification`.
2. **Given** la sección de cada servicio en `docs/mapa-servicios.md`, **When** se evalúa su persistencia, **Then** especifica un esquema y base de datos propia sin claves foráneas ni lecturas directas entre servicios, garantizando el principio de soberanía de datos (ADR-0015).
3. **Given** la sección de cada servicio en `docs/mapa-servicios.md`, **When** se evalúan sus interfaces de entrada, **Then** detalla los protocolos expuestos (endpoints REST bajo OpenAPI, streaming SSE, endpoints de servidor MCP y endpoints de salud/métricas Actuator).
4. **Given** la sección de cada servicio en `docs/mapa-servicios.md`, **When** se evalúa la mensajería, **Then** enumera los tópicos Kafka que produce y los que consume, vinculando los eventos con las especificaciones de contratos que regirán en `shoplab-contracts` (LAB-108).
5. **Given** la sección de cada servicio en `docs/mapa-servicios.md`, **When** se evalúan sus dependencias, **Then** mapea de forma exhaustiva las conexiones síncronas (HTTP/MCP), asíncronas (Kafka) e infraestructura asociada (PostgreSQL, LiteLLM, Redis de caché si aplica), confirmando la ausencia de dependencias circulares.

---

### User Story 3 - Publicación de herramientas de agente vía protocolo MCP en el servicio dueño (Priority: P1)

Como desarrollador del agente conversacional, quiero que las herramientas que consultan o mutan datos (`catalog`, `order`, `ticket`, `retrieval`) se expongan a través del protocolo abierto Model Context Protocol (MCP) desde el propio servicio dueño de los datos, y que `chat-service` actúe como cliente MCP en la red, para trasladar la regla de encapsulamiento de `@Tool` de ADR-0015 del ámbito modular en memoria al entorno distribuido en red.

**Contexto y motivación**:
En el monolito modular (ADR-0015), cada módulo empaquetaba sus herramientas `@Tool` dentro de su subpaquete `api`, y el módulo `chat` las descubría y componía localmente. Al extraer los módulos a microservicios independientes con repositorios y ciclos de vida propios, no es aceptable importar código ni librerías de negocio de otros servicios en `chat`. Tampoco es deseable crear endpoints ad-hoc REST RPC con acoplamiento de modelos. MCP resuelve esto de forma nativa para agentes LLM:
- Cada servicio de dominio levanta un servidor MCP (por ejemplo, sobre HTTP/SSE o transporte de streaming seguro) publicando sus herramientas con esquema JSON Schema estricto.
- `chat-service` se conecta a los servidores MCP de los servicios correspondientes como cliente MCP, descubre dinámicamente las herramientas disponibles y delega su ejecución a través de la red hacia el servicio dueño.
- El servicio dueño de los datos valida permisos, aplica sus reglas de negocio y ejecuta la consulta/mutación sobre su propia base de datos, retornando la respuesta tipada al agente.

**Why this priority**: Es un criterio de aceptación crítico e innovador de Jira LAB-128 que redefine cómo colabora el asistente conversacional con los servicios periféricos sin acoplamiento de compilación ni pérdida de soberanía de datos.

**Independent Test**: Verificar en `docs/mapa-servicios.md` y en el nuevo ADR que se define explícitamente: la arquitectura MCP cliente-servidor para `chat-service`, el catálogo de herramientas por servicio dueño, los contratos de transporte en red, el manejo de tiempos de espera/caídas y el aislamiento de lógica de negocio fuera de `chat`.

**Acceptance Scenarios**:

1. **Given** un servicio dueño de datos (`catalog`, `order`, `ticket` o `retrieval`), **When** se describe su exposición de capacidades para el LLM, **Then** se especifica que publica un endpoint de servidor MCP con el esquema de sus herramientas correspondientes.
2. **Given** el servicio `chat-service`, **When** requiere invocar una herramienta externa durante un flujo de conversación (p. ej. consultar estado de un pedido o buscar productos), **Then** actúa como cliente MCP invoking la herramienta sobre la red contra el servicio dueño correspondiente, sin contener código Java de dominio de dicho servicio.
3. **Given** la caída o indisponibilidad de un servidor MCP de un servicio de dominio, **When** `chat-service` intenta ejecutar una herramienta, **Then** captura el error de red o timeout y lo traduce limpiamente en una respuesta degradada o informativa para el usuario final sin abortar la sesión ni filtrar trazas de infraestructura.

---

### User Story 4 - Estrategia para el código compartido y plantilla de servicio (ADR-0010) (Priority: P1)

Como líder técnico del equipo, quiero fijar formalmente la política sobre el código del módulo `shared` (ADR-0010), estableciendo que cada nuevo microservicio extraído incorporará su propia copia desacoplada a partir de la plantilla de servicio de LAB-103 y prohibiendo tajantemente la creación de una librería común compartida con lógica o entidades de dominio, para prevenir el antipatrón de «monolito distribuido».

**Contexto y motivación**:
El módulo `shared` en el monolito modular contiene utilidades técnicas básicas: jerarquía de excepciones (`BusinessException`, `TechnicalException`), catálogo inicial de errores (`ResponseCodeEnum`), `GlobalExceptionHandler` y filtro de correlación `RequestIdFilter`.
Al pasar a microservicios, la tentación tradicional es empaquetar `shared` en un JAR común distribuido en Maven. La experiencia demuestra que estas librerías comunes tienden a absorber entidades, DTOs y lógica compartida, acoplando las versiones de despliegue de todos los servicios. La decisión arquitectónica estipula:
- **No hay librería común con dominio**: los contratos de red se comparten mediante paquetes versionados generados exclusivamente desde especificaciones OpenAPI/Avro (`shoplab-contracts`, LAB-108).
- **Copia desde la plantilla**: las utilidades técnicas transversales (estructura de excepciones, envoltorio de respuestas `ResponseDto`, filtro de correlación, configuración base de observabilidad y logging seguro) se clonan e instancian en cada servicio a partir del arquetipo/plantilla de servicio definido en `LAB-103`. Cada servicio es libre de evolucionar sus códigos de error específicos (`<SERVICIO>_...`) de forma autónoma.

**Why this priority**: Evita la degradación arquitectónica del sistema distribuido y marca las reglas de juego claras para `LAB-103` (plantilla de servicio) y `LAB-108` (contratos).

**Independent Test**: Comprobar que tanto en el ADR de extracción como en `docs/mapa-servicios.md` se prohíbe explícitamente el uso de artefactos Maven compartidos con lógica de dominio y se instruye el uso de la plantilla de `LAB-103` para la infraestructura base de cada servicio.

**Acceptance Scenarios**:

1. **Given** la extracción de un módulo a servicio independiente, **When** se define su infraestructura técnica base (errores, correlación, logging), **Then** se adopta por copia/generación inicial desde la plantilla de `LAB-103`, manteniendo su código en su propio repositorio.
2. **Given** los códigos de error de cada servicio, **When** se definen nuevos códigos de negocio, **Then** cada servicio gestiona su propio catálogo tipado con prefijo unívoco (`CHAT_...`, `CAT_...`, `ORD_...`), sin requerir cambios centralizados en otros repositorios.
3. **Given** el intercambio de información entre servicios, **When** se revisan las dependencias Maven de un servicio, **Then** únicamente incluye dependencias de contratos generados (`shoplab-contracts`) y librerías de infraestructura estándar, sin ninguna dependencia Maven de dominio interno compartida.

---

### User Story 5 - Separación de responsabilidades: `eval-service` (Java) y `shoplab-eval` (Python) (Priority: P2)

Como ingeniero de datos y evaluación RAG, quiero clarificar y documentar la división nítida entre `eval-service` (servicio Java en repositorio propio extraído de `backend/eval`) y `shoplab-eval` (repositorio Python dedicado a la suite de evaluación y al dataset golden), para garantizar que ambas piezas cumplan su función sin solapamiento de código ni duplicidad de responsabilidades.

**Contexto y responsabilidades**:
- **`eval-service` (Java)**:
  - Repositorio propio: `shoplab-eval-service`.
  - Construido a partir del módulo `eval` del backend actual (LAB-31, LAB-32).
  - Gestiona el esquema de datos `eval` en PostgreSQL (`eval.eval_run`, `eval.eval_case`, etc.).
  - Expone APIs REST consumidas por la interfaz administrativa web `admin-eval` para consulta de métricas históricas, evolución temporal y revisión de feedbacks con 👎 para promoción.
- **`shoplab-eval` (Python)**:
  - Repositorio propio: `shoplab-eval`.
  - Proyecto administrado con `uv` y Python 3.12+.
  - Hogar del dataset golden (`eval/golden.jsonl`), las suites de evaluación automática (RAGAS, DeepEval), notebooks de experimentación y CLI de validación de reglas.
  - Se ejecuta en los flujos de CI contra el backend o invoca endpoints del sistema, reportando los resultados finales a `eval-service` mediante API REST para su persistencia histórica.

**Why this priority**: Resuelve la ambigüedad operativa de dónde reside el código Java de persistencia de métricas frente a la herramienta de evaluación en Python, cumpliendo el criterio de aceptación de Jira LAB-128.

**Independent Test**: Revisar la documentación de `docs/mapa-servicios.md` y verificar que ambos repositorios y componentes tienen delimitadas sus entradas, salidas, tecnologías y esquemas de interacción en CI y producción.

**Acceptance Scenarios**:

1. **Given** el módulo `eval` del backend Java actual, **When** se ejecuta su extracción en el bloque de extracción temprana, **Then** se transfiere a su repositorio dedicado `shoplab-eval-service`, asumiendo la titularidad del esquema `eval` y las APIs de administración de evaluación.
2. **Given** la herramienta de evaluación automática de RAG en Python, **When** se referencia en la arquitectura, **Then** permanece en el repositorio `shoplab-eval`, encargándose de ejecutar las métricas RAGAS/DeepEval y validar el dataset golden en los pipelines de CI.
3. **Given** la finalización de una corrida de evaluación en CI por parte de `shoplab-eval`, **When** se deben persistir los resultados para consulta histórica, **Then** el runner Python envía el resumen de la ejecución vía cliente HTTP contra la API REST expuesta por `eval-service`.

---

## Edge Cases

- **Coexistencia durante la extracción gradual (S19–S24)**: durante las fases en las que un módulo ya ha sido extraído a servicio (p. ej. `retrieval`) pero otro aún reside en el monolito (p. ej. `chat`), `chat` invocará temporalmente al servicio extraído mediante HTTP/REST o MCP a través de un adaptador de infraestructura, desacoplando los despliegues sin romper la funcionalidad continua en desarrollo.
- **Manejo de herramientas de servicios no desplegados en dev**: si `orders` o `tickets` están en desarrollo y su servidor MCP no está activo en el entorno local, `chat-service` debe consultar dinámicamente el catálogo de MCP y, si una herramienta no está disponible, informar con cortesía al usuario que el canal de pedidos o tickets se encuentra temporalmente fuera de servicio.
- **Evolución de los esquemas Flyway al extraer**: cuando un módulo como `chat` o `retrieval` se extraiga a su propio servicio, sus migraciones Flyway existentes en `db/migration/<modulo>` se convertirán en la línea base (`V1__baseline.sql`) o conservarán su historial en la base de datos dedicada del nuevo servicio sin requerir recrear los datos desde cero.
- **Ruptura de contratos durante la extracción**: ningún servicio podrá modificar los esquemas o APIs de sus herramientas sin pasar por el flujo de versionado semántico y validación de compatibilidad establecido en `shoplab-contracts` (LAB-108).
- **Ambigüedad con la numeración de ADRs en Jira**: en Jira la historia se tituló originalmente como «ADR-0029». Dado que en el repositorio los números 0029 y 0030 fueron asignados a LAB-32 y LAB-33 respectivamente, la documentación debe explicar claramente en una nota de trazabilidad que el ADR oficial es `ADR-0031`, manteniendo la equivalencia directa con LAB-128.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El proyecto MUST incorporar el nuevo documento de decisión arquitectónica en `docs/adr/0031-extraccion-temprana-de-servicios.md` (o numeración secuencial libre que corresponda), cubriendo exhaustivamente el contexto del crecimiento asimétrico del backend, la decisión de extraer `retrieval`, `chat` y `eval` en S19–S24, y el nacimiento de `catalog`, `order` y `ticket` como microservicios en F3/F4.
- **FR-002**: El documento `docs/adr/0001-monolito-modular-primero.md` MUST actualizarse marcando su estado como `aceptado (parcialmente sustituido por ADR-0031)`, explicitando que la ventana de extracción se adelanta de F4 a S19 tras F2.
- **FR-003**: El documento `docs/adr/0015-limites-de-contexto-y-repositorios.md` MUST actualizarse marcando su estado como `aceptado (parcialmente sustituido por ADR-0031)`, explicitando que la estrategia de repositorios por servicio se anticipa a S19 en lugar de esperar a F5.
- **FR-004**: El índice central `docs/adr/README.md` MUST actualizarse registrando la fila de ADR-0031 con su título y estado `aceptado`, y actualizando los estados correspondientes de ADR-0001 y ADR-0015.
- **FR-005**: El proyecto MUST crear y publicar `docs/mapa-servicios.md` describiendo la topología completa de los 8 servicios de destino: `chat`, `retrieval`, `eval`, `catalog`, `order`, `ticket`, `gateway` y `notification`.
- **FR-006**: Para cada uno de los 8 servicios en `docs/mapa-servicios.md`, la especificación MUST detallar su esquema de base de datos exclusivo, prohibiendo explícitamente consultas directas o claves foráneas cruzadas entre esquemas/bases de datos.
- **FR-007**: Para cada uno de los 8 servicios en `docs/mapa-servicios.md`, la especificación MUST detallar las APIs e interfaces expuestas (endpoints HTTP/REST, streaming SSE, endpoints de servidor MCP y endpoints de salud/métricas).
- **FR-008**: Para cada uno de los 8 servicios en `docs/mapa-servicios.md`, la especificación MUST detallar los eventos asíncronos que publica y los que consume a través de Kafka, referenciando su alineación con el repositorio de contratos `shoplab-contracts` (LAB-108).
- **FR-009**: Para cada uno de los 8 servicios en `docs/mapa-servicios.md`, la especificación MUST diagramar o mapear sus dependencias upstream y downstream (bases de datos, colas de eventos, servicios externos como LLM gateway y otros microservicios).
- **FR-010**: La especificación arquitectónica MUST definir el uso de Model Context Protocol (MCP) para la exposición y consumo de herramientas del agente: los servicios dueños (`catalog`, `order`, `ticket`, `retrieval`) actuarán como servidores MCP, y `chat-service` actuará como cliente MCP en red, garantizando que ninguna lógica de negocio de las herramientas resida en `chat-service`.
- **FR-011**: La especificación arquitectónica MUST prohibir formalmente la creación o distribución de librerías comunes con lógica, entidades o repositorios de dominio (anti-patrón de monolito distribuido).
- **FR-012**: La especificación arquitectónica MUST establecer que la infraestructura técnica base no de dominio (manejo de excepciones, correlación, logging) se replicará en cada servicio a partir de la plantilla común de servicio gobernada en `LAB-103`.
- **FR-013**: La especificación arquitectónica MUST delimitar las responsabilidades y repositorios de evaluación: `eval-service` en Java (repo `shoplab-eval-service`) responsable de la persistencia de ejecuciones y APIs administrativas, y `shoplab-eval` en Python (repo `shoplab-eval`) responsable del dataset golden y de la ejecución de RAGAS/DeepEval en CI.
- **FR-014**: Toda la documentación técnica generada (`docs/adr/0031-extraccion-temprana-de-servicios.md`, `docs/mapa-servicios.md` y actualizaciones de ADRs existentes) MUST redactarse en español y seguir el estándar de Markdown del repositorio.
- **FR-015**: La especificación MUST mantener coherencia con las historias predecesoras y sucesoras (`LAB-108`, `LAB-103`, `LAB-106`, `LAB-50`), confirmando que la extracción temprana desbloquea la publicación de contratos en `shoplab-contracts`.

---

### Key Entities *(include if feature involves data)*

- **Servicio de Dominio (Service Entity)**: Entidad arquitectónica autónoma con repositorio propio, base de datos con esquema propio, ciclo de release independiente y despliegue desacoplado en el clúster de dev.
- **Ficha de Servicio (Service Map Specification)**: Estructura documental estandarizada que agrupa: Nombre, Contexto delimitado, Base de datos y esquema, APIs expuestas (REST, SSE, MCP), Eventos Kafka producidos/consumidos, y Matriz de dependencias.
- **Herramienta MCP (MCP Tool Contract)**: Definición de una capacidad expuesta por un servicio dueño conforme a la especificación de Model Context Protocol, incluyendo nombre, descripción en lenguaje natural para el LLM, parámetros de entrada con JSON Schema y respuesta estructurada.
- **Plantilla de Servicio (Service Template Archetype, LAB-103)**: Estructura base de partida para nuevos microservicios en Java/Spring Boot que incluye la configuración inicial de compilación, observabilidad, correlación y jerarquía de excepciones sin lógica de dominio.
- **Registro ADR (Architecture Decision Record)**: Archivo Markdown inmutable bajo `docs/adr/` que documenta formalmente el contexto, decisión, consecuencias y estado de vigencia o sustitución de una directriz técnica del proyecto.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los criterios de aceptación especificados en Jira LAB-128 (ADR de extracción, mapa de 8 servicios, regla MCP, decisión sobre módulo shared y separación de eval-service) están cubiertos con trazabilidad directa en los artefactos de diseño y documentación.
- **SC-002**: El documento `docs/mapa-servicios.md` describe sin omisiones los 8 servicios requeridos (`chat`, `retrieval`, `eval`, `catalog`, `order`, `ticket`, `gateway` y `notification`), contando cada uno con esquema, APIs, eventos y dependencias explícitas.
- **SC-003**: El ADR formal (`0031-extraccion-temprana-de-servicios.md`) queda redactado y vinculado en `docs/adr/README.md`, y los ADR-0001 y ADR-0015 quedan actualizados reflejando su sustitución parcial en menos de 1 minuto de revisión por parte de cualquier miembro del equipo.
- **SC-004**: Cero acoplamientos prohibidos: la especificación prohíbe de forma explícita cualquier dependencia Maven cruzada de dominio entre servicios, obligando a que la comunicación sea 100 % a través de contratos OpenAPI/Avro (`shoplab-contracts`) o MCP en red.
- **SC-005**: La delimitación entre `eval-service` (Java) y `shoplab-eval` (Python) queda descrita de forma que ningún desarrollador tenga dudas sobre en qué repositorio se crean nuevos endpoints de consulta o dónde se agregan casos de evaluación RAGAS.
- **SC-006**: Todos los enlaces Markdown entre documentos de `docs/`, `docs/adr/` y referencias cruzadas son válidos y resuelven correctamente en el árbol del repositorio.

---

## Assumptions

- **Estado del monolito**: Al finalizar F2, los módulos `chat`, `retrieval` y `eval` cuentan con suites de pruebas maduras y esquemas de datos estables en PostgreSQL (`chat`, `retrieval`, `eval`). Los módulos `orders`, `catalog` y `tickets` carecen de implementación real y por tanto su desarrollo inicial se realizará directamente en sus repositorios destino de microservicios en F3/F4.
- **Numeración ADR**: Siguiendo el estándar inmutable del repositorio (`docs/adr/README.md`), el nuevo ADR adopta la numeración secuencial libre `ADR-0031`. En el texto del ADR y de la especificación se hace constar expresamente que responde a la historia LAB-128 (citada en Jira como ADR-0029).
- **Protocolo MCP**: El protocolo Model Context Protocol (MCP) se adopta como el estándar oficial de comunicación entre el asistente (`chat-service`) y las herramientas de los servicios satélite (`catalog`, `order`, `ticket`, `retrieval`). Los transportes soportados en la arquitectura serán HTTP con Server-Sent Events (SSE) o Stream sobre TLS para despliegues en Kubernetes.
- **Gobernanza de contratos**: Los esquemas de eventos (Avro) y contratos síncronos (OpenAPI) se gobernarán exclusivamente a través del repositorio `shoplab-contracts` (adelantado en LAB-108), que publicará artefactos con versionado semántico gestionados con `release-please`.
- **Ecosistema de infraestructura**: Todos los servicios extraídos seguirán desplegándose en el entorno único `dev` gestionado mediante GitOps en `shoplab-infra` (ADR-0006, ADR-0008, ADR-0012).
- **Fuera de alcance de esta historia**: La codificación real de la extracción de cada microservicio en repositorios separados, la implementación de los servidores MCP en Java/Python, y la creación física de la plantilla de servicio de LAB-103 quedan fuera del alcance de LAB-128; LAB-128 establece el marco regulatorio, documental y arquitectónico obligatorio que rige y habilita dichas tareas.
