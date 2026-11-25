# Feature Specification: LAB-107 Gateway estrangulador antes de extraer servicios

**Feature Branch**: `feature/LAB-107-strangler-gateway`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-107 (F5-15) Como desarrollador, quiero un gateway delante del monolito antes de extraer el primer servicio, para mover cada ruta al servicio nuevo sin que el frontend se entere (patrón strangler fig). Criterios de aceptación: repo `shoplab-gateway` creado desde la plantilla de LAB-103, con Spring Cloud Gateway delante del monolito y Angular hablando solo con el gateway; rutas por prefijo que hoy apuntan al monolito (`/api/chat/**`, `/api/documents/**`, `/api/knowledge/**`, `/api/eval/**`; luego `/api/catalog/**`, `/api/orders/**`, `/api/tickets/**` cuando nazcan esos servicios); streaming SSE de `/api/chat` sin buffering; prueba que mueve una ruta del monolito a un servicio cambiando solo la configuración del gateway; despliegue en dev por el ApplicationSet de LAB-104; JWT y rate limiting quedan para LAB-60. Depende de LAB-103 y LAB-104. Va antes de LAB-129."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Toda la API pasa por el gateway sin cambios visibles (Priority: P1)

Como usuario de la aplicación (cliente en el chat, administrador de documentos o de evaluaciones), quiero que todo siga funcionando igual cuando se pone un gateway delante del monolito, para que la preparación de la extracción no me afecte. Como desarrollador, quiero que ese gateway sea la única puerta de entrada a la API.

**Why this priority**: Es el mínimo entregable. Sin paridad de comportamiento no se puede poner nada delante del monolito, y sin una puerta única no hay patrón estrangulador.

**Independent Test**: Con el gateway delante del monolito, recorrer las pantallas de chat (incluido el feedback), documentos y evaluaciones, y ejecutar las pruebas de extremo a extremo existentes apuntando al gateway. Los resultados son los mismos que contra el monolito.

**Acceptance Scenarios**:

1. **Given** el gateway delante del monolito, **When** la aplicación web llama a cualquiera de las rutas publicadas (`/api/chat`, `/api/chat/feedback/{turnId}`, `/api/documents`, `/api/knowledge/search`, `/api/eval/**`, `/api/assistant/settings`), **Then** la petición llega al monolito y la respuesta vuelve con el mismo estado, cabeceras relevantes y cuerpo que sin el gateway.
2. **Given** una ruta cuyo prefijo coincide exactamente, sin segmentos adicionales (`POST /api/chat`, `GET /api/documents`), **When** se envía por el gateway, **Then** se enruta igual que sus subrutas.
3. **Given** una petición que el monolito rechaza (validación, recurso inexistente, conflicto, límite de presupuesto), **When** pasa por el gateway, **Then** el cliente recibe el mismo código de estado y el mismo cuerpo de error tipado.
4. **Given** la subida de un documento del tamaño máximo admitido hoy, **When** se envía por el gateway, **Then** se acepta y se indexa igual que antes.
5. **Given** la aplicación web sin ningún cambio en su código, **When** se publica detrás del gateway, **Then** sus llamadas a la API las atiende el gateway y ninguna llega directa al monolito.

---

### User Story 2 - El streaming del chat atraviesa el gateway sin retenerse (Priority: P1)

Como cliente en el chat, quiero ver la respuesta aparecer a medida que se genera, igual que hoy, para no esperar a que termine para empezar a leer.

**Why this priority**: El chat es la función principal del producto y la más frágil ante un intermediario: si el gateway acumula la respuesta, la experiencia se rompe aunque el contenido llegue completo.

**Independent Test**: Contra un destino simulado que emite fragmentos con pausas conocidas, comprobar que cada fragmento se recibe a través del gateway antes de que el destino emita el siguiente. Repetir manualmente con el chat real en dev.

**Acceptance Scenarios**:

1. **Given** una conversación en el chat, **When** el asistente genera la respuesta en varios fragmentos, **Then** el cliente recibe cada fragmento conforme se produce y no todos juntos al final.
2. **Given** una respuesta que tarda en emitir su primer fragmento o hace pausas largas dentro de lo que hoy se tolera, **When** pasa por el gateway, **Then** la conexión no se corta.
3. **Given** una respuesta en curso, **When** el usuario cancela o cierra la pantalla, **Then** el gateway cierra también la conexión con el destino y este deja de generar.
4. **Given** una respuesta en curso, **When** el destino falla a mitad del flujo, **Then** el cliente ve el flujo interrumpido o el evento de error que el destino haya emitido, y nunca una respuesta truncada presentada como completa.

---

### User Story 3 - Mover una ruta a un servicio nuevo cambiando solo configuración (Priority: P1)

Como desarrollador que extrae un servicio, quiero redirigir su prefijo desde el monolito al servicio nuevo cambiando únicamente la configuración del gateway, para estrangular el monolito ruta a ruta sin tocar el frontend ni coordinar despliegues.

**Why this priority**: Es la razón de existir del gateway y la condición para empezar LAB-129, la primera extracción.

**Independent Test**: Levantar el gateway con dos destinos distinguibles (monolito y servicio sustituto). Cambiar solo la configuración del destino de un prefijo y comprobar que las peticiones de ese prefijo las atiende el sustituto, el resto sigue en el monolito y el artefacto de la aplicación web es el mismo.

**Acceptance Scenarios**:

1. **Given** todos los prefijos apuntando al monolito, **When** se cambia en la configuración el destino de un prefijo, **Then** sus peticiones llegan al nuevo destino con la misma ruta, método, parámetros, cabeceras y cuerpo.
2. **Given** un prefijo movido, **When** se llama a cualquier otro prefijo, **Then** sigue atendido por el monolito.
3. **Given** el destino de un prefijo es configurable, **When** se cambia, **Then** no hace falta modificar código ni publicar una versión nueva del gateway ni de la aplicación web.
4. **Given** un prefijo movido que da problemas, **When** se revierte el cambio de configuración, **Then** el monolito vuelve a atenderlo.
5. **Given** que `retrieval` ocupa dos prefijos (`/api/documents` y `/api/knowledge`), **When** se mueven ambos, **Then** cada uno se configura de forma independiente y pueden apuntar al mismo destino.

---

### User Story 4 - El gateway corre en dev y en local (Priority: P2)

Como desarrollador, quiero que el gateway se despliegue en dev como un servicio más y forme parte del entorno local, para probar cada extracción en las mismas condiciones en que va a funcionar.

**Why this priority**: Las historias anteriores dan el valor y se pueden verificar de forma aislada; esta lo pone en uso real y es la que habilita las extracciones siguientes en el clúster.

**Independent Test**: Tras una publicación, comprobar en dev que la aplicación del gateway está sincronizada y sana, que la web funciona y que las peticiones de API quedan registradas en el gateway. En local, levantar el compose y repetir el recorrido.

**Acceptance Scenarios**:

1. **Given** el alta del gateway en el repositorio de infraestructura, **When** el ApplicationSet de dev lo recoge, **Then** la aplicación del gateway queda sincronizada y sana sin modificar la configuración de Argo CD.
2. **Given** una versión nueva del gateway publicada, **When** termina su pipeline, **Then** solo se actualiza la imagen del gateway en el repositorio de infraestructura.
3. **Given** el gateway sano en dev, **When** un usuario abre la aplicación y usa la API, **Then** la entrada del clúster entrega todo `/api` al gateway y el gateway lo reparte.
4. **Given** el entorno local de LAB-110, **When** se levanta el sistema completo, **Then** incluye el gateway, la aplicación web y la herramienta de evaluación llegan a la API a través de él, y `shoplab-gateway` se puede clonar y construir localmente igual que los demás repositorios.

---

### Edge Cases

- **Destino caído o inalcanzable**: el gateway responde con un error de indisponibilidad en el formato de error estándar de ShopLab, sin trazas internas, direcciones ni nombres de host. Un destino caído no afecta a los prefijos que apuntan a otros destinos.
- **Destino que no responde a tiempo**: el gateway corta con un error de tiempo de espera en el mismo formato. El límite no puede ser menor que lo que hoy tarda una operación válida (subida de documento con indexado síncrono, primer fragmento del chat).
- **Ruta sin configuración** (`/api/catalog/**`, `/api/orders/**`, `/api/tickets/**` antes de que nazcan sus servicios, o cualquier otra desconocida): respuesta 404 en el formato de error estándar.
- **Prefijo parecido** (`/api/chatbot`, `/api/documents-old`): no coincide con `/api/chat` ni `/api/documents`; los prefijos se comparan por segmentos completos.
- **Archivo por encima del máximo**: el usuario sigue recibiendo el error tipado del destino, no un rechazo genérico del intermediario.
- **Rutas internas** (`/actuator/**` del monolito o de un servicio): no son accesibles desde fuera a través del gateway.
- **Petición sin identificador de correlación**: el gateway genera uno; si ya trae uno, lo conserva. En ambos casos llega al destino.
- **Configuración de rutas inválida** (destino vacío o mal formado): el gateway no arranca e indica qué ruta está mal, en lugar de arrancar y fallar en las peticiones.
- **Reinicio o actualización del gateway**: puede interrumpir respuestas en curso; es aceptable en dev con una sola réplica.

## Requirements *(mandatory)*

### Functional Requirements

**Repositorio y base**

- **FR-001**: El gateway MUST vivir en su propio repositorio `shoplab-gateway`, creado a partir de la plantilla de LAB-103 y construido sobre Spring Cloud Gateway (decisión fijada por la historia), sin dependencias de código del monolito ni de otros servicios.
- **FR-002**: El repositorio MUST usar el pipeline reutilizable de LAB-103 (compilación, pruebas, cobertura, análisis, imagen versionada y firmada) y el versionado automático del resto de repositorios.
- **FR-003**: El gateway MUST ser sin estado: sin base de datos, migraciones ni secretos de base de datos, aunque la plantilla los traiga.

**Enrutamiento**

- **FR-004**: El gateway MUST enrutar al monolito, por prefijo y por segmentos completos (incluida la ruta exacta del prefijo): `/api/chat`, `/api/documents`, `/api/knowledge`, `/api/eval` y `/api/assistant`.
- **FR-005**: El destino de cada prefijo MUST ser configurable por entorno y de forma independiente, sin cambiar código ni reconstruir la imagen.
- **FR-006**: Añadir un prefijo nuevo (`/api/catalog`, `/api/orders`, `/api/tickets`) MUST requerir solo configuración. Estos tres no se configuran en esta historia.
- **FR-007**: El gateway MUST NOT reescribir la ruta, el método, los parámetros, el cuerpo ni el estado de la respuesta; las cabeceras de extremo a extremo, incluidas las de credenciales, pasan sin modificar.
- **FR-008**: Las peticiones sin ruta configurada MUST responder 404 en el formato de error estándar.
- **FR-009**: El gateway MUST NOT exponer hacia fuera los puntos de gestión de los destinos.
- **FR-010**: Una configuración de rutas inválida MUST impedir el arranque indicando la ruta afectada.

**Streaming, límites y errores**

- **FR-011**: Las respuestas en streaming MUST entregarse fragmento a fragmento, sin acumularlas ni comprimirlas de forma que se retengan, en toda la cadena desde el destino hasta el cliente.
- **FR-012**: La cancelación por parte del cliente MUST propagarse al destino.
- **FR-013**: Los tamaños máximos y tiempos de espera del gateway MUST NOT ser más restrictivos que los que hoy aplican el destino y la entrada del clúster.
- **FR-014**: Los errores originados en el gateway (destino inalcanzable, tiempo agotado, ruta inexistente) MUST usar el formato de error estándar de `shoplab-contracts` con códigos propios `SLHD_GATE_NNNNN`, sin detalles internos. Los errores del destino pasan tal cual.

**Observabilidad**

- **FR-015**: El gateway MUST generar o conservar el identificador de correlación y el contexto de traza, y propagarlos al destino.
- **FR-016**: El gateway MUST registrar de cada petición el prefijo, el destino, el estado y la duración, sin cuerpos, credenciales ni datos personales.
- **FR-017**: El gateway MUST publicar su estado de salud y métricas por prefijo y destino, recogidas por la observabilidad existente.

**Entornos y documentación**

- **FR-018**: El gateway MUST desplegarse en dev mediante el ApplicationSet de LAB-104, creando su carpeta en el repositorio de infraestructura, y la entrada del clúster MUST enviar `/api` al gateway en lugar de al monolito.
- **FR-019**: El entorno local MUST incluir el gateway (versión de imagen fijada, opción de construcción local y `clone-all`), y la aplicación web y las herramientas MUST usarlo como dirección de la API.
- **FR-020**: Una prueba automatizada MUST demostrar que un prefijo pasa del monolito a otro destino cambiando solo la configuración del gateway.
- **FR-021**: Se MUST registrar la decisión en un ADR, actualizar el mapa de servicios con los prefijos reales y documentar cómo mover una ruta y cómo revertirlo.

**Fuera de alcance**

- **FR-022**: El gateway MUST NOT validar identidad ni limitar la tasa de peticiones en esta historia (LAB-60). Tampoco incluye CORS nuevo, terminación TLS, reintentos automáticos, cortacircuitos, balanceo canario ni agregación de respuestas.

### Key Entities

- **Ruta**: asociación entre un prefijo público de la API y el destino que lo atiende. Atributos: identificador, prefijo, destino, límites de espera.
- **Destino**: componente que responde peticiones de uno o varios prefijos. Hoy, solo el monolito; después, cada servicio extraído.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los recorridos de chat, feedback, documentos, evaluaciones y herramienta de evaluación que funcionaban antes del gateway funcionan igual después, con cero cambios en el código de la aplicación web.
- **SC-002**: En una respuesta en streaming, cada fragmento llega al cliente con menos de 200 ms de retraso añadido respecto a recibirlo directo del destino.
- **SC-003**: En peticiones que no son de streaming, el gateway añade menos de 50 ms en el percentil 95 en el entorno local.
- **SC-004**: Un desarrollador mueve un prefijo a otro destino, o lo revierte, con un único cambio de configuración y en menos de 10 minutos desde que lo confirma hasta que está activo en dev.
- **SC-005**: Con un destino caído, el 100 % de las peticiones a los demás destinos siguen respondiendo con normalidad.
- **SC-006**: Dada una petición cualquiera, se puede seguir con un mismo identificador desde el registro del gateway hasta el del destino.

## Assumptions

- **Monolito como único destino inicial**: ningún servicio extraído existe todavía; la prueba de movimiento de ruta usa un destino sustituto.
- **`/api/assistant` se incluye aunque la historia no lo liste**: el monolito publica `GET /api/assistant/settings` (LAB-106) y hoy la entrada del clúster envía todo `/api` al monolito; omitirlo sería una regresión.
- **Prefijos reales frente al mapa de servicios**: `docs/mapa-servicios.md` indica `/api/retrieval/**`, pero las rutas publicadas son `/api/documents` y `/api/knowledge`. Mandan la historia y el código; el mapa se corrige (FR-021).
- **La web estática no pasa por el gateway**: «Angular solo habla con el gateway» se refiere a sus llamadas a la API. `/` lo sigue sirviendo el contenedor del frontend, aunque el mapa de servicios sugiera que el gateway también lo enrute.
- **Mismo origen**: web y API se siguen publicando bajo el mismo host, por lo que no se necesita CORS.
- **Plantilla de LAB-103**: a la fecha no existe un repositorio `shoplab-service-template` publicado en GitHub; su contenido está en `specs/LAB-103-reusable-workflows-service-template/shoplab-service-template/`. `shoplab-gateway` se crea a partir de ese contenido. Publicar la plantilla como repositorio queda fuera de esta historia.
- **Versiones de la plataforma**: la plantilla declara Spring Boot 3.4.0, mientras la constitución exige Spring Boot 4.x. El gateway sigue la constitución.
- **Monolito en local**: su puerto puede seguir publicado para depurar; deja de ser la dirección por defecto de los consumidores.
- **Dev con una sola réplica** del gateway; alta disponibilidad no es objetivo.

## Dependencies

- **LAB-103** (hecha): plantilla de servicio y workflows reutilizables.
- **LAB-104** (hecha): ApplicationSet y estructura `apps/<servicio>` en `shoplab-infra`.
- **LAB-106 / LAB-108** (hechas): formato de error y contratos publicados.
- **LAB-110** (hecha): entorno local multi-repo que hay que ampliar.
- **Posteriores**: LAB-129 (retrieval), LAB-59 (chat) y LAB-130 (eval) mueven sus prefijos usando este gateway; LAB-60 añade JWT y rate limiting.
