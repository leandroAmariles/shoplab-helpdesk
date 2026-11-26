# Feature Specification: LAB-130 Extraer eval-service del monolito

**Feature Branch**: `feature/LAB-130-eval-service-extraction`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-130 (F5-23) Como desarrollador, quiero el historial de evaluaciones en su propio servicio, para retirar el monolito y que la evaluación online de F7 tenga dónde vivir. Criterios de aceptación: repo `shoplab-eval-service` creado desde la plantilla de LAB-103, con el módulo Java `eval` y su carpeta `db/migration/eval`; base de datos propia con los datos del esquema `eval` migrados; publica `POST/GET /api/eval/runs` y `GET /api/eval/settings` con su OpenAPI en `shoplab-contracts`; lee la configuración servida del asistente de `chat-service` por HTTP (`GET /api/assistant/settings`) en lugar de `AssistantSettingsApi`, con prueba de contrato eval → chat; la herramienta Python de `eval/` (repo `shoplab-eval`, LAB-109) guarda sus ejecuciones contra el servicio sin cambios en su lado salvo la URL; el gateway de LAB-107 mueve `/api/eval/**` al servicio; desplegado en dev por el ApplicationSet de LAB-104. Depende de LAB-59 y LAB-106. Va antes de LAB-69 (evaluación online)."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El historial de evaluaciones vive en su propio servicio con sus datos (Priority: P1)

Como desarrollador, quiero `eval-service` en su repositorio, con su base de datos y la misma API que hoy ofrece el monolito, para evolucionar la evaluación sin tocar el resto de servicios.

**Why this priority**: Es el núcleo de la historia. Con el servicio funcionando en paralelo al monolito ya se puede comparar su comportamiento sin cambiar nada para los usuarios ni para la herramienta de evaluación.

**Independent Test**: Levantar el servicio con una copia migrada de los datos, junto al monolito intacto. Ejecutar contra ambos el guardado, la lectura y el listado de ejecuciones; las respuestas coinciden campo a campo.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-eval-service` creado desde la plantilla de LAB-103, **When** se construye y se prueba, **Then** contiene el módulo `eval` y sus migraciones de `db/migration/eval`, y no depende de código del monolito.
2. **Given** las ejecuciones, casos y resultados del esquema `eval`, **When** se migran a la base de datos propia, **Then** los recuentos y el contenido coinciden, el historial de migraciones se conserva y las secuencias siguen asignando identificadores sin chocar con los existentes.
3. **Given** una misma petición a `POST /api/eval/runs`, `GET /api/eval/runs/{runId}`, `GET /api/eval/settings` o a la lectura del historial, **When** la atiende el servicio, **Then** el estado, el cuerpo y los códigos de error `SLHD_EVAL_*` son los mismos que en el monolito.
4. **Given** una ejecución ya guardada con un identificador, **When** se guarda otra vez con el mismo identificador, **Then** el servicio la rechaza como duplicada, igual que hoy.
5. **Given** una release del servicio, **When** termina su pipeline, **Then** su OpenAPI está en `shoplab-contracts` y el servicio la verifica como productor.

---

### User Story 2 - eval-service lee la configuración del asistente desde chat-service (Priority: P1)

Como desarrollador, quiero que `eval-service` pida a `chat-service` la configuración servida del asistente por HTTP y que el acuerdo esté probado, para que `eval-service` no necesite código del chat y un cambio de contrato se detecte antes de desplegar.

**Why this priority**: Es la única dependencia en tiempo de ejecución de `eval` con otro servicio. Sin ella, `GET /api/eval/settings` no puede funcionar fuera del monolito.

**Independent Test**: Con los stubs publicados por `chat-service`, pedir `GET /api/eval/settings` al servicio y comprobar que devuelve la configuración del stub; detener el stub y comprobar el error tipado; correr la prueba de contrato eval → chat.

**Acceptance Scenarios**:

1. **Given** `chat-service` sano, **When** se pide `GET /api/eval/settings` con el token válido, **Then** el servicio devuelve la configuración que sirve `chat-service` en `GET /api/assistant/settings`, sin recalcularla ni guardarla.
2. **Given** `chat-service` caído o que no responde a tiempo, **When** se pide `GET /api/eval/settings`, **Then** el servicio devuelve el mismo error tipado de almacén no disponible que hoy y el guardado y la lectura del historial siguen funcionando.
3. **Given** las pruebas de contrato eval → chat, **When** corren, **Then** usan los stubs publicados por `chat-service` y fallan si `chat-service` cambia de forma incompatible la respuesta que `eval-service` consume.
4. **Given** el código de `eval-service`, **When** se revisa, **Then** no existe `AssistantSettingsApi` ni ninguna dependencia del código de `chat-service`; solo el contrato publicado.
5. **Given** una petición con cabeceras de trazabilidad, **When** `eval-service` llama a `chat-service`, **Then** las propaga.

---

### User Story 3 - La herramienta de evaluación y la pantalla de administración siguen igual con las rutas en el servicio (Priority: P1)

Como persona que ejecuta evaluaciones o consulta el historial en la pantalla `admin-eval`, quiero que todo siga funcionando cuando las rutas pasan al servicio nuevo, para no notar la extracción.

**Why this priority**: Es el momento en que la extracción entra en uso. Depende de US1 y US2 y es reversible solo con configuración mientras el monolito exista.

**Independent Test**: En dev, con el servicio desplegado y los datos migrados, cambiar el destino de `/api/eval/**` en el gateway; ejecutar la herramienta Python cambiando solo la URL; abrir `admin-eval`, listar, abrir y comparar ejecuciones; revertir y comprobar que el monolito vuelve a atender las rutas (solo antes de retirarlo).

**Acceptance Scenarios**:

1. **Given** el alta de `eval-service` en `shoplab-infra`, **When** el ApplicationSet de dev lo recoge, **Then** queda sincronizado y sano, con su base de datos, sus secretos, el token de escritura y el acceso a `chat-service`.
2. **Given** los datos migrados y el servicio sano, **When** se cambia en el gateway el destino de `/api/eval/**`, **Then** esas peticiones las atiende el servicio y el resto sigue donde estaba, sin publicar una versión nueva del gateway ni del frontend.
3. **Given** las rutas movidas, **When** la herramienta de `shoplab-eval` guarda una ejecución, **Then** queda guardada en el servicio con la misma respuesta que antes y el único cambio en el lado de la herramienta es la URL.
4. **Given** las rutas movidas, **When** se usa `admin-eval`, **Then** se listan las mismas ejecuciones migradas, el detalle y la comparación dan los mismos valores y las ejecuciones nuevas aparecen.
5. **Given** las rutas movidas, **When** se compara una ejecución migrada con una nueva, **Then** la comparación funciona igual que entre dos ejecuciones nuevas.
6. **Given** un problema tras mover las rutas y el monolito aún sin retirar, **When** se revierte la configuración, **Then** el monolito vuelve a atenderlas.

---

### User Story 4 - El monolito se retira (Priority: P2)

Como desarrollador, quiero retirar `backend/` una vez que `eval` vive en su servicio, para no mantener dos copias del historial ni un artefacto sin dueño.

**Why this priority**: Es el objetivo declarado de la historia («retirar el monolito»), pero solo es seguro después de US3. Antes habría dos copias del historial que se separan con la primera ejecución guardada.

**Independent Test**: Con las rutas ya en el servicio, retirar `backend/` y comprobar que la construcción, las pruebas y el despliegue del resto siguen en verde, que ninguna ruta apunta al monolito y que los datos de Olist siguen intactos.

**Acceptance Scenarios**:

1. **Given** las rutas movidas y verificadas, **When** se retira el monolito, **Then** no queda en el repositorio código, migración, ruta del gateway, despliegue ni configuración de `eval` ni de `backend/`.
2. **Given** `backend/` retirado, **When** corren la CI y el compose local, **Then** pasan sin él y el entorno local arranca `eval-service` en su lugar.
3. **Given** el retiro, **When** se inspecciona PostgreSQL, **Then** los datos de Olist y los esquemas ajenos a `eval` siguen intactos; el esquema `eval` antiguo no se borra en esta historia.
4. **Given** el monolito retirado, **When** se necesita volver atrás, **Then** el procedimiento está documentado (restaurar la ruta y los datos desde la copia previa al corte).

---

### User Story 5 - El servicio queda documentado y listo para la evaluación online (Priority: P3)

Como desarrollador, quiero que los documentos de arquitectura reflejen el servicio y que la evaluación online (LAB-69) tenga un lugar donde vivir, para empezar F7 sin otra extracción.

**Why this priority**: Es documentación y preparación; no cambia el comportamiento, pero cierra la deuda de la historia.

**Independent Test**: Revisar que el ADR, el mapa de servicios, la documentación de módulos, contratos y administración de evaluación están actualizados y no mencionan el monolito como destino de `eval`.

**Acceptance Scenarios**:

1. **Given** la historia terminada, **When** se revisa la documentación, **Then** existe un ADR (base propia, corte, retiro del monolito y reversión) y el mapa de servicios, los módulos, los contratos y la pantalla de administración de evaluación están actualizados.
2. **Given** LAB-69, **When** se planifica, **Then** puede añadir su lógica en `eval-service` sin crear un servicio nuevo ni tocar el monolito.

---

### Edge Cases

- **Ejecución guardada durante la ventana de corte**: se acepta suspender el guardado de ejecuciones durante la migración; la herramienta debe fallar de forma visible, no perder la ejecución en silencio.
- **Ejecución sin token o con token inválido**: se rechaza con el mismo código que hoy; la lectura del historial no pide token, como hoy.
- **Mismo identificador de ejecución guardado dos veces**: la segunda se rechaza y no altera la primera.
- **Cuerpo inválido o con propiedades desconocidas**: se rechaza con `SLHD_EVAL_*` igual que hoy.
- **`chat-service` cambia la forma de su configuración**: la prueba de contrato eval → chat falla antes de desplegar.
- **Persistencia desactivada o almacén no disponible**: se mantienen los errores tipados actuales.
- **Datos migrados con identificadores existentes**: nuevas ejecuciones no deben chocar con las migradas.
- **Texto de pregunta o respuesta**: el servicio sigue sin guardar texto de preguntas ni respuestas en el historial (ADR-0028), también tras migrar.
- **Ejecuciones anteriores comparadas con nuevas**: la comparación y el cálculo de regresiones deben dar los mismos valores que con el monolito.
- **Reversión tras el retiro del monolito**: ya no es solo configuración; requiere restaurar desde la copia previa.

## Requirements *(mandatory)*

### Functional Requirements

**Servicio y datos**

- **FR-001**: Se MUST crear el repositorio `shoplab-eval-service` desde la plantilla de LAB-103 sin editar la plataforma a mano, con el módulo Java `eval` y su carpeta `db/migration/eval`.
- **FR-002**: El servicio MUST NOT depender de código del monolito ni de `chat-service`; solo de contratos publicados en `shoplab-contracts`.
- **FR-003**: El servicio MUST tener una base de datos propia con credencial propia (puede estar en el mismo servidor PostgreSQL), donde otros servicios no pueden conectar.
- **FR-004**: Se MUST migrar a esa base los datos del esquema `eval` (ejecuciones, casos, resultados e historial de migraciones) con recuentos y contenido idénticos, conservando los identificadores y sin pérdida de precisión en las métricas.
- **FR-005**: La migración MUST ser verificable (recuentos, sumas de comprobación y secuencias) y repetible, y MUST NOT modificar el esquema de origen.
- **FR-006**: Tras migrar, las secuencias del servicio MUST continuar por encima de los identificadores migrados.

**API y contratos**

- **FR-007**: El servicio MUST publicar `POST /api/eval/runs`, `GET /api/eval/runs/{runId}` y `GET /api/eval/settings` con el mismo estado, cuerpo, validaciones y códigos `SLHD_EVAL_*` que el monolito.
- **FR-008**: El servicio MUST publicar también las rutas de lectura del historial que usa `admin-eval` (`/api/eval/history/**`), porque el gateway mueve `/api/eval/**` completo.
- **FR-009**: Las rutas de escritura y de configuración MUST seguir pidiendo el token de evaluación; la lectura del historial MUST seguir sin pedirlo (ADR-0029).
- **FR-010**: El OpenAPI de `eval-service` MUST publicarse en `shoplab-contracts` y el servicio MUST verificarlo como productor en cada release.
- **FR-011**: Los códigos de error `SLHD_EVAL_*` actuales MUST mantenerse aunque ADR-0031 prevea códigos propios por servicio; cambiarlos rompería a la herramienta de evaluación.
- **FR-012**: El servicio MUST NOT guardar texto de preguntas ni respuestas (ADR-0028).

**Dependencia de chat-service**

- **FR-013**: `GET /api/eval/settings` MUST obtener la configuración del asistente de `GET /api/assistant/settings` de `chat-service` por HTTP, usando el contrato publicado en `shoplab-contracts`, y MUST NOT usar `AssistantSettingsApi` ni código de `chat`.
- **FR-014**: La llamada a `chat-service` MUST tener un tiempo máximo de espera, propagar `X-Request-Id` y `traceparent` y traducir sus fallos al error tipado de almacén no disponible.
- **FR-015**: Se MUST añadir una prueba de contrato eval → chat que use los stubs publicados por `chat-service` y falle ante un cambio incompatible.
- **FR-016**: Un fallo de `chat-service` MUST NOT impedir guardar ni leer ejecuciones.

**Herramienta de evaluación**

- **FR-017**: La herramienta Python de `shoplab-eval` (LAB-109) MUST guardar sus ejecuciones contra el servicio sin cambios en su lado salvo la URL.
- **FR-018**: Se MUST verificar de extremo a extremo que una ejecución completa de la herramienta queda guardada y legible desde `admin-eval`.

**Gateway, despliegue y corte**

- **FR-019**: El gateway de LAB-107 MUST mover `/api/eval/**` al servicio con un solo cambio de configuración, sin publicar una versión nueva del gateway ni del frontend.
- **FR-020**: `eval-service` MUST desplegarse en dev por el ApplicationSet de LAB-104 con su base, sus secretos, su token y su acceso a `chat-service`, quedando sincronizado y sano.
- **FR-021**: El corte MUST hacerse con ventana: se suspende el guardado de ejecuciones, se migra, se mueve la ruta y se reanuda; sin doble escritura ni sincronización continua.
- **FR-022**: Antes de retirar el monolito, devolver las rutas a él MUST ser posible con un solo cambio de configuración y en menos de 10 minutos.
- **FR-023**: El entorno local multi-repo (LAB-110) MUST levantar `eval-service` con su propia base y sin el monolito.

**Retiro del monolito**

- **FR-024**: Una vez verificado el servicio con las rutas movidas, se MUST retirar `backend/` completo: código, migraciones, pruebas, imagen, despliegue, rutas y configuración, y los flujos de CI que solo existían para él.
- **FR-025**: El retiro MUST NOT ejecutar `DROP SCHEMA` ni tocar los datos de Olist ni los esquemas ajenos a `eval` (ADR-0034); el borrado del esquema `eval` antiguo queda como paso manual documentado, con copia previa.

**Calidad y documentación**

- **FR-026**: Se MUST tomar una línea base del historial (recuentos, listados, detalles y comparaciones de ejecuciones representativas) antes de migrar y repetir la comprobación después, y guardar la comparación.
- **FR-027**: Se MUST registrar un ADR (base propia, corte, retiro del monolito y reversión) y actualizar el mapa de servicios, la documentación de módulos, contratos y administración de evaluación.

**Fuera de alcance**

- **FR-028**: Esta historia MUST NOT incluir: la evaluación online de F7 (LAB-69), eventos Kafka como `eval.run.completed` (LAB-47), la promoción de feedback a caso golden (`/api/eval/cases/promote`), autenticación y login (LAB-60, LAB-61), cambios en la herramienta de evaluación más allá de la URL, ni nuevos formatos de métricas.

### Key Entities

- **Ejecución de evaluación**: corrida completa guardada por la herramienta; identidad, tipo, origen, instante de inicio, configuración del asistente y de recuperación, versión de prompt y métricas globales.
- **Caso de evaluación**: pregunta del conjunto de evaluación con su identidad y revisión; no guarda texto de pregunta ni de respuesta.
- **Resultado**: puntajes de un caso dentro de una ejecución.
- **Configuración del asistente**: configuración servida por `chat-service` que se registra con cada ejecución; `eval-service` solo la lee.
- **Contrato de evaluación**: descripción publicada de las operaciones de `eval-service`, de la que sale la verificación como productor.
- **Contrato eval → chat**: acuerdo probado con los stubs de `chat-service` sobre la configuración que lee `eval-service`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las ejecuciones, casos y resultados migrados coinciden con el origen y las comparaciones entre ejecuciones migradas dan los mismos valores que antes.
- **SC-002**: La herramienta de `shoplab-eval` guarda una ejecución completa contra el servicio con 0 cambios en su código, solo la URL.
- **SC-003**: `admin-eval` lista, abre y compara ejecuciones con 0 cambios en el código del frontend.
- **SC-004**: Un cambio en la evaluación se publica y queda activo en dev sin ninguna release del monolito.
- **SC-005**: 0 referencias a `AssistantSettingsApi` y 0 dependencias de código de `chat-service` en `eval-service`.
- **SC-006**: Con `chat-service` caído, el guardado y la lectura del historial responden con normalidad y `GET /api/eval/settings` devuelve el error tipado.
- **SC-007**: Antes del retiro, las rutas se pueden devolver al monolito en menos de 10 minutos y con un solo cambio de configuración.
- **SC-008**: Tras el retiro, 0 referencias a `backend/` en el repositorio y 0 cambios en los datos de Olist.
- **SC-009**: Un cambio de contrato incompatible de `chat-service` hace fallar la prueba de contrato eval → chat antes de desplegar.

## Assumptions

- **Base de datos propia** significa una base separada con credencial propia en el mismo servidor PostgreSQL de dev y local, como en LAB-59 (ADR-0034). No necesita `pgvector`.
- **Corte con ventana**: dev es el único ambiente; se acepta suspender el guardado de ejecuciones durante la migración. No hay doble escritura.
- **Orden de entrega**: servicio en paralelo → migración → rutas del gateway en la misma ventana → retiro de `backend/`. Mover las rutas sin migrar mostraría un historial vacío.
- **Retiro del monolito**: ADR-0034 deja `backend/` reducido a `eval` + `shared` y difiere su retiro completo a esta historia; el título de la historia («retirar el monolito») lo confirma, aunque los criterios de Jira no lo listan explícitamente.
- **Rutas de historial**: `/api/eval/history/**` no aparece en los criterios de Jira, pero lo usa `admin-eval` y el gateway mueve `/api/eval/**` entero, por lo que se incluye.
- **Códigos de error**: se mantienen los `SLHD_EVAL_*` actuales (`00001`–`00006`).
- **Sin autenticación de usuario**, como hoy; el token de evaluación actual es el único control (ADR-0024, LAB-61 pendiente).
- **Una réplica en dev**: no se escala dev.
- **Contratos**: `shoplab-contracts` ya genera el cliente de `chat` (`1.4.0`) y `chat-service` publica los stubs; `eval` ya los usa desde LAB-59.

## Notes for Planning

Hallazgos del repositorio que el plan debe resolver; no cambian el alcance.

- `eval` ya consulta `chat` por HTTP en el monolito (`HttpAssistantSettingsClient` sobre `AssistantSettingsApi` generado, con modo `in-process`/`http`, LAB-59). La historia pide quitar `AssistantSettingsApi` y usar el contrato publicado: decidir si el cliente se genera desde el OpenAPI de `chat` o se escribe a mano sobre el contrato, y conservar el timeout de 5 s.
- `eval` usa `JdbcEvalRunStoreAdapter`/`JdbcEvalHistoryAdapter` con consultas de historial en `src/test/resources/eval/queries`; las pruebas de equivalencia (`EvalHistoryEquivalenceTests`) pueden servir de línea base.
- El esquema `eval` tiene `eval_run`, `eval_case` y `eval_result` (V1 baseline y V2 historial); el mapa de servicios (`docs/mapa-servicios.md`) todavía describe `eval.eval_case` con otro alcance y menciona `/api/eval/cases/promote` y eventos Kafka que **no** existen: alinear sin implementarlos.
- Los scripts de migración de LAB-59 (`scripts/migrate-chat.*`, `verify-chat-migration.*`, `infra/postgres/chat-db-init.sh`) son el modelo para `eval`.
- `shoplab-infra` puede tener ya `apps/eval-service` o no; comprobar y alinear versión, variables y token.
- El retiro de `backend/` afecta a CI, Dockerfile, compose, `shoplab-infra`, Qodana/Trivy y documentación: inventariarlo antes de borrar.
- El token de evaluación llega hoy por cabecera; comprobar que el gateway la reenvía en `/api/eval/**`.

## Dependencies

- **LAB-59**: `chat-service` extraído, con `GET /api/assistant/settings` y sus stubs.
- **LAB-103**: plantilla y flujos reutilizables.
- **LAB-104**: ApplicationSet y `apps/<servicio>` en `shoplab-infra`.
- **LAB-106 / LAB-108**: contratos y paquetes versionados.
- **LAB-107**: gateway que mueve las rutas.
- **LAB-109**: herramienta `shoplab-eval` y frontend separados.
- **LAB-110**: entorno local multi-repo.
- **Posteriores**: LAB-69 (evaluación online) va después de esta historia.
