# Feature Specification: Trazas de LLM con Langfuse autoalojado

**Feature Branch**: `feature/LAB-64-langfuse-llm-traces`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "LAB-64 (F2-10) Como desarrollador, quiero ver cada turno del LLM en detalle desde que empieza el RAG. Criterios: Langfuse autoalojado en docker compose (perfil `llmops`) y en el clúster; cada traza muestra prompt, contexto recuperado, herramientas llamadas, tokens y coste por turno. Depende de: LAB-22. Las puntuaciones de evaluación se adjuntan a estas trazas en LAB-29. Funciona en local: sí; Langfuse usa Postgres, ClickHouse, Redis y almacenamiento S3 compatible (MinIO), así que va en un perfil aparte para no cargar la máquina si no se usa. Se deja funcionando en el perfil de DEV."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Levantar Langfuse en local con el perfil `llmops` (Priority: P1)

Como desarrollador, quiero activar el perfil `llmops` de docker compose y tener Langfuse listo para usar, para inspeccionar
los turnos del LLM en mi máquina sin instalar ni configurar nada a mano.

**Why this priority**: Es la base de todo lo demás: sin Langfuse en marcha no hay dónde enviar ni ver las trazas, y el
perfil `llmops` está reservado y vacío desde LAB-10, así que esta historia es la que le da contenido.

**Independent Test**: Levantar compose con el perfil `llmops`, abrir la interfaz de Langfuse con las credenciales locales
documentadas y comprobar que ya existe un proyecto con sus claves, sin ningún paso manual de alta.

**Acceptance Scenarios**:

1. **Given** compose sin el perfil `llmops`, **When** se levanta el entorno, **Then** no arranca ningún servicio de
   Langfuse ni de sus almacenes de datos y el comportamiento es el de antes de la historia.
2. **Given** compose con el perfil `llmops`, **When** se levanta, **Then** arrancan Langfuse y todo lo que necesita, con
   imágenes de versión fija, y sus comprobaciones de salud pasan.
3. **Given** el perfil activo por primera vez, **When** se abre la interfaz de Langfuse, **Then** ya existen la
   organización, el proyecto y las claves de acceso configurados por archivos versionados, y se puede iniciar sesión con
   las credenciales locales documentadas.
4. **Given** el perfil activo y un reinicio de los contenedores, **When** vuelven a levantar, **Then** el proyecto, las
   claves y las trazas ya guardadas siguen disponibles porque los datos viven en volúmenes con nombre.
5. **Given** el perfil `observability` y el perfil `llmops` activos a la vez, **When** se levanta el entorno, **Then**
   ambos conviven sin conflicto de puertos ni de nombres.

---

### User Story 2 - Ver cada turno del chat como una traza completa (Priority: P1)

Como desarrollador, quiero que cada pregunta respondida por el chat quede registrada como una traza con todo lo que pasó
en ese turno, para entender por qué el modelo respondió lo que respondió.

**Why this priority**: Es el valor de la historia. Sin la traza, depurar el RAG (qué contexto llegó, qué prompt se
envió, cuánto costó) sigue siendo adivinar a partir de logs.

**Independent Test**: Con Langfuse en marcha y el envío de trazas activado, hacer una pregunta al chat sobre un
documento indexado y comprobar, en la interfaz de Langfuse, que aparece una traza con el prompt, los fragmentos de
contexto recuperados, los tokens y el coste.

**Acceptance Scenarios**:

1. **Given** Langfuse en marcha y el envío de trazas activado, **When** el chat completa una respuesta con contexto,
   **Then** aparece una traza de ese turno con la pregunta, el prompt enviado al modelo y la respuesta generada.
2. **Given** una traza de un turno con contexto, **When** se abre en Langfuse, **Then** muestra los fragmentos de
   contexto recuperados que se entregaron al modelo, con su fuente y su puntuación de similitud, en el orden en que se
   numeraron para las citas.
3. **Given** una traza de un turno, **When** se abre, **Then** muestra los tokens de entrada, de salida y el total, y el
   coste calculado del turno.
4. **Given** un turno en el que el modelo llama a una herramienta, **When** se abre su traza, **Then** cada llamada
   aparece como un paso con el nombre de la herramienta, sus argumentos, su resultado y su duración.
5. **Given** una traza de un turno, **When** se abre, **Then** los pasos (recuperación de contexto, generación y
   llamadas a herramientas) aparecen anidados y en orden, cada uno con su duración, y la traza muestra la latencia total
   y el tiempo hasta el primer token.
6. **Given** un turno de una conversación con memoria, **When** se consultan sus trazas, **Then** los turnos de la misma
   conversación se agrupan en una sola sesión y el historial incluido en el prompt es visible.
7. **Given** un turno cualquiera, **When** se consulta su traza, **Then** el identificador de la petición del chat está
   en la traza, de modo que se pueda pasar del log del backend a la traza y al revés.

---

### User Story 3 - Tener Langfuse funcionando en el ambiente DEV del clúster (Priority: P2)

Como desarrollador, quiero que Langfuse corra también en el clúster y que el backend desplegado en DEV le envíe sus
trazas, para ver los turnos del ambiente compartido igual que veo los locales.

**Why this priority**: La historia lo pide de forma explícita («se deja funcionando en el perfil de DEV»), pero depende
de que las trazas ya existan (historia 2) y de que Langfuse sepa arrancar (historia 1).

**Independent Test**: Desplegar con Argo CD, hacer una pregunta al chat de DEV y comprobar en el Langfuse del clúster
que aparece la traza del turno, sin editar configuración a mano.

**Acceptance Scenarios**:

1. **Given** el ambiente `dev` sincronizado por Argo CD, **When** se consultan los recursos del clúster, **Then**
   Langfuse y sus almacenes de datos están en ejecución y sanos, con imágenes de versión fija.
2. **Given** Langfuse sano en el clúster, **When** el backend de DEV atiende una pregunta del chat, **Then** la traza del
   turno aparece en el Langfuse del clúster con el mismo contenido que en local.
3. **Given** el clúster recién creado (`destroy` y `apply`), **When** Argo CD sincroniza `dev`, **Then** Langfuse queda
   funcionando con su proyecto y sus claves, sin pasos manuales.
4. **Given** las credenciales de Langfuse en DEV, **When** se revisa el repositorio, **Then** ninguna credencial real
   está versionada: llegan por el mecanismo de secretos que ya usa el resto del ambiente.
5. **Given** el clúster local de un solo nodo, **When** Langfuse está desplegado, **Then** el backend y el resto de
   componentes siguen pudiendo ejecutarse con los recursos del nodo.

---

### User Story 4 - El chat no depende de las trazas (Priority: P2)

Como desarrollador, quiero que registrar trazas nunca estorbe a quien usa el chat, para poder dejar el envío activado sin
riesgo y apagarlo sin tocar código.

**Why this priority**: Una herramienta de observación que degrada o rompe el servicio observado no se puede dejar
activa. Es una garantía que acompaña a las historias 2 y 3.

**Independent Test**: Detener Langfuse con el envío de trazas activado, hacer varias preguntas al chat y comprobar que
las respuestas llegan completas y a la misma velocidad que sin trazas.

**Acceptance Scenarios**:

1. **Given** el envío de trazas activado y Langfuse detenido, **When** el chat atiende una pregunta, **Then** la
   respuesta se entrega completa, sin error visible para el usuario y sin retraso apreciable.
2. **Given** Langfuse lento o con credenciales inválidas, **When** el chat atiende preguntas, **Then** las respuestas no
   se retrasan ni fallan, y el problema queda registrado como aviso en el log del backend sin incluir el contenido de la
   conversación.
3. **Given** el envío de trazas desactivado (valor por defecto), **When** el backend arranca y atiende preguntas,
   **Then** no intenta conectarse a Langfuse y se comporta como antes de la historia.
4. **Given** Langfuse que vuelve a estar disponible tras una caída, **When** el chat atiende nuevas preguntas, **Then**
   las trazas de los turnos nuevos aparecen sin reiniciar el backend.

---

### User Story 5 - Distinguir los turnos que no son una respuesta normal (Priority: P3)

Como desarrollador, quiero ver también en Langfuse los turnos sin contexto, fallidos o cancelados, para depurar
justamente los casos en que el RAG no funcionó como se esperaba.

**Why this priority**: Los turnos con respuesta normal ya dan valor (historia 2); los casos raros son donde más se
necesita la traza, pero se pueden entregar después sin perder el MVP.

**Independent Test**: Preguntar algo sin documentos relevantes, interrumpir una respuesta a mitad y provocar un fallo
del modelo, y comprobar que cada caso deja una traza con su resultado.

**Acceptance Scenarios**:

1. **Given** una pregunta para la que no se recupera contexto útil, **When** el chat responde con el mensaje fijo
   configurado (sin llamar al modelo), **Then** la traza muestra la recuperación sin fragmentos, ninguna generación del
   modelo, y el resultado «sin contexto».
2. **Given** un turno que el usuario cancela a mitad de la respuesta, **When** se consulta su traza, **Then** aparece
   con el resultado «cancelado» y el texto parcial generado hasta ese momento.
3. **Given** un turno en el que falla la recuperación o el modelo, **When** se consulta su traza, **Then** aparece con
   el resultado «error», el código de error que recibió el usuario y el paso en que falló.

---

### Edge Cases

- ¿Qué pasa si el modelo no informa del uso de tokens (por ejemplo, un modelo que no lo devuelve)? Los tokens se
  muestran como «desconocidos», nunca como cero, y el coste del turno se marca como no calculable en vez de inventarse.
- ¿Qué pasa con el coste de los modelos locales (Ollama)? Su precio por token es cero por configuración, así que el coste
  del turno es 0 y no «desconocido»; un modelo sin entrada en la tabla de precios se marca como «sin precio».
- ¿Qué pasa si el turno no llama al modelo porque responde con el mensaje fijo de «sin contexto»? No hay generación ni
  tokens ni coste; la traza lo indica claramente y no muestra ceros engañosos.
- ¿Qué pasa si el prompt o el contexto son muy grandes? El contenido enviado a la traza tiene un tope de tamaño; al
  superarlo se trunca y la traza lo señala, sin afectar a lo que recibe el modelo ni el usuario.
- ¿Qué pasa si hay muchas preguntas a la vez? Las trazas se envían en segundo plano con un búfer acotado; si se llena, se
  descartan trazas (con aviso en el log) antes que bloquear o ralentizar el chat.
- ¿Qué pasa si el backend se detiene justo después de responder? Las trazas pendientes se intentan enviar al apagar, con
  un tiempo máximo; las que no se logren enviar se pierden sin impedir el apagado.
- ¿Qué pasa con datos sensibles? Las trazas contienen la conversación completa, por lo que solo se usan con datos
  públicos o de prueba (Olist y marcadores de posición); ni claves, ni cabeceras de autorización, ni credenciales entran
  en la traza.
- ¿Qué pasa si los puertos locales de Langfuse o de sus almacenes ya están ocupados? Los puertos se pueden cambiar con
  variables, como el resto de servicios de compose.
- ¿Qué pasa en una máquina con poca memoria? El perfil `llmops` es opcional y no se activa por defecto; el entorno
  `core` y el perfil `observability` no deben verse afectados por su existencia.
- ¿Qué pasa con los datos de Langfuse en el clúster? Viven en volúmenes persistentes del clúster (`local-path`): sobreviven
  al reinicio de los pods, pero se pierden al recrear el clúster (`destroy` y `apply`); esto se acepta y se documenta,
  porque el proyecto y las claves se recrean solos desde git.
- ¿Qué pasa si Langfuse arranca antes que sus almacenes de datos? Espera a que estén sanos (reintentos u orden de
  arranque) y no queda roto de forma permanente.

## Requirements *(mandatory)*

### Functional Requirements

**Langfuse en docker compose**

- **FR-001**: El perfil `llmops` de docker compose MUST arrancar Langfuse junto con los servicios que necesita (base de
  datos relacional, almacén analítico, caché y almacenamiento de objetos compatible con S3), y MUST NOT arrancar nada de
  ello cuando el perfil no está activo.
- **FR-002**: Todas las imágenes nuevas MUST estar fijadas a una versión concreta (nunca `latest`) y ser sobrescribibles
  con variables de entorno, igual que las demás imágenes de compose.
- **FR-003**: Los servicios nuevos MUST tener comprobación de salud y respetar las convenciones del archivo (volúmenes con
  nombre, `restart`, puertos configurables, dependencias con condición de salud).
- **FR-004**: Langfuse MUST arrancar con organización, proyecto, usuario inicial y claves de acceso ya creados a partir de
  configuración versionada, sin alta manual en la interfaz.
- **FR-005**: Las credenciales y claves locales MUST ser marcadores de posición obvios, válidos solo en local, y ningún
  secreto real MUST estar versionado.
- **FR-006**: Los datos de Langfuse MUST persistir entre reinicios de los contenedores mediante volúmenes con nombre.
- **FR-007**: El perfil `llmops` MUST convivir con `core` y con `observability` sin conflicto de puertos, nombres ni
  volúmenes, y activar `llmops` MUST NOT modificar el comportamiento de los otros perfiles.

**Trazas del backend**

- **FR-008**: El envío de trazas MUST controlarse por configuración (dirección de Langfuse, claves y un interruptor de
  activación), MUST estar desactivado por defecto y, desactivado, MUST NOT abrir conexiones hacia Langfuse.
- **FR-009**: Cada turno del chat MUST generar exactamente una traza, identificable por el identificador de la petición
  del chat, y los turnos de una misma conversación con memoria MUST agruparse en una sesión.
- **FR-010**: La traza MUST mostrar la pregunta del usuario, el prompt completo enviado al modelo (instrucciones del
  sistema, historial incluido y contexto) y la respuesta generada.
- **FR-011**: La traza MUST mostrar el contexto recuperado que se entregó al modelo: cada fragmento con su fuente, su
  puntuación y su posición para las citas; si no se entregó ninguno, MUST indicarlo.
- **FR-012**: La traza MUST mostrar cada llamada a herramienta que ocurra en el turno, con nombre, argumentos, resultado,
  duración y si terminó con error.
- **FR-013**: La traza MUST mostrar los tokens de entrada, salida y total del turno, y MUST distinguir «desconocido» de
  «cero».
- **FR-014**: La traza MUST mostrar el coste del turno, calculado a partir de los tokens y de una tabla de precios por
  modelo versionada en git y editable por configuración; los modelos locales MUST tener precio cero y un modelo sin
  precio MUST marcarse como «sin precio» en vez de inventar un valor.
- **FR-015**: Los pasos del turno (recuperación de contexto, generación, llamadas a herramientas) MUST aparecer anidados
  y en orden dentro de la traza, cada uno con su duración, y la traza MUST incluir la latencia total y el tiempo hasta el
  primer token.
- **FR-016**: La traza MUST registrar el resultado del turno (completado, sin contexto, cancelado o error) y, en caso de
  error, el código de error y el paso en que ocurrió; los turnos cancelados MUST conservar el texto parcial.
- **FR-017**: La traza MUST identificar el modelo usado y los parámetros relevantes de la generación.
- **FR-018**: El contenido enviado a la traza MUST tener un tope de tamaño; al superarlo, MUST truncarse y señalarse en la
  traza, sin alterar lo que ve el usuario ni lo que recibe el modelo.
- **FR-019**: Las trazas MUST NOT incluir claves de API, cabeceras de autorización ni credenciales; los logs del backend
  MUST seguir sin contener la pregunta, la respuesta ni los fragmentos (solo conteos), como hasta ahora.
- **FR-020**: Los identificadores de traza y de sesión MUST ser estables y derivables del turno, de modo que LAB-29 pueda
  adjuntar puntuaciones de evaluación a las trazas existentes; adjuntar esas puntuaciones queda fuera de esta historia.

**Resiliencia**

- **FR-021**: El envío de trazas MUST ser asíncrono respecto de la respuesta del chat: una falla, lentitud o rechazo de
  Langfuse MUST NOT retrasar, alterar ni hacer fallar la respuesta al usuario.
- **FR-022**: Cuando no se pueda enviar una traza, el backend MUST registrar un aviso sin contenido de la conversación y
  MUST usar un búfer acotado que descarte trazas antes que bloquear el chat.
- **FR-023**: Al reiniciar el backend o al volver Langfuse tras una caída, las trazas de los turnos nuevos MUST enviarse
  sin intervención manual; al apagar, el backend MUST intentar vaciar las pendientes con un tiempo máximo.

**Langfuse en el clúster (DEV)**

- **FR-024**: Langfuse y sus servicios de apoyo MUST desplegarse en el ambiente `dev` mediante Argo CD, con manifiestos
  versionados en el repositorio de infraestructura (`shoplab-infra`) y versiones fijas.
- **FR-025**: El backend desplegado en `dev` MUST estar configurado para enviar sus trazas al Langfuse del clúster, con
  el envío activado y las claves provistas por el mecanismo de secretos del ambiente.
- **FR-026**: Tras un `destroy` y un `apply` del clúster, Langfuse MUST quedar funcionando con su proyecto y sus claves
  sin pasos manuales; la persistencia de las trazas en el clúster MUST documentarse según el almacenamiento disponible.
- **FR-027**: Los recursos solicitados por los componentes nuevos en el clúster MUST ser mínimos y caber en el clúster
  local de un solo nodo junto con el resto de la pila.
- **FR-028**: El acceso a la interfaz de Langfuse en el clúster MUST documentarse, y los almacenes de datos de Langfuse
  MUST NOT publicarse fuera de la red interna del clúster.
- **FR-029**: El repositorio de infraestructura MUST validar en CI los manifiestos nuevos con las mismas políticas que ya
  aplica al resto.

**Calidad y documentación**

- **FR-030**: La generación de trazas MUST verificarse con pruebas automáticas deterministas que no dependan de un
  Langfuse real ni de un modelo real, cubriendo los casos de las historias 2, 4 y 5.
- **FR-031**: El procedimiento (activar el perfil, direcciones y credenciales locales, activar el envío de trazas en el
  backend, cómo leer una traza, cómo editar la tabla de precios y cómo ver las trazas de DEV) MUST estar documentado, y
  la tabla de perfiles del README MUST reflejar que `llmops` ya tiene servicios.
- **FR-032**: Las decisiones de arquitectura relevantes de esta historia (qué se envía a las trazas, relación con las
  métricas existentes) MUST registrarse como ADR en `docs/adr/`.
- **FR-033**: Quedan fuera de esta historia: los prompts versionados en Langfuse (LAB-85), el gateway de modelos con
  LiteLLM (LAB-84), las puntuaciones de evaluación sobre las trazas (LAB-29), los paneles de coste en Grafana (LAB-65) y
  las trazas distribuidas entre servicios (LAB-63).

### Key Entities *(include if feature involves data)*

- **Traza (turno)**: registro completo de una pregunta del chat y su respuesta; tiene identificador estable derivado de
  la petición, resultado, latencias, tokens, coste y los pasos que la componen.
- **Sesión**: conjunto de trazas de una misma conversación con memoria, en orden cronológico.
- **Paso**: parte de una traza con inicio, duración y resultado: recuperación de contexto, generación del modelo o
  llamada a herramienta; los pasos se anidan dentro de la traza.
- **Fragmento de contexto**: texto recuperado de la base de conocimiento que se entregó al modelo, con fuente,
  puntuación de similitud y posición para la cita.
- **Uso de tokens**: tokens de entrada, salida y total de una generación, con el estado «desconocido» cuando el modelo no
  los informa.
- **Tabla de precios**: precio por token de entrada y de salida para cada modelo, versionada en git; permite calcular el
  coste del turno.
- **Proyecto de Langfuse**: espacio donde viven las trazas, con sus claves de acceso; se crea solo a partir de
  configuración versionada.
- **Perfil `llmops`**: agrupación de servicios de compose (Langfuse y sus almacenes) que se activa aparte para no cargar
  la máquina.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el perfil `llmops` activo, un desarrollador pasa de `docker compose up` a ver la traza de su primera
  pregunta en Langfuse en menos de 5 minutos, sin más pasos manuales que activar el envío de trazas.
- **SC-002**: El 100 % de los turnos completados en una ejecución de 20 preguntas de prueba tienen una traza con prompt,
  contexto recuperado, tokens y coste (y, cuando hubo, herramientas llamadas).
- **SC-003**: Un desarrollador responde en menos de 1 minuto, desde la interfaz de Langfuse, a «¿qué contexto recibió el
  modelo en este turno y cuántos tokens costó?» para cualquier turno de la ejecución de prueba.
- **SC-004**: Con Langfuse detenido, el 100 % de las preguntas del chat se responde completa y sin error; con Langfuse
  activo, el tiempo hasta el primer token aumenta menos de un 5 % frente a tenerlo desactivado.
- **SC-005**: Sin el perfil `llmops`, el entorno levanta exactamente los mismos contenedores y en el mismo tiempo que
  antes de la historia.
- **SC-006**: En el clúster, una pregunta hecha al chat de DEV aparece como traza en el Langfuse del clúster en menos de
  1 minuto, sin editar configuración a mano.
- **SC-007**: Tras `destroy` y `apply` del clúster, Langfuse de DEV vuelve a estar funcionando con proyecto y claves sin
  intervención manual en el 100 % de las pruebas.
- **SC-008**: Ninguna traza de la ejecución de prueba contiene claves, cabeceras de autorización ni credenciales, y el
  repositorio no contiene ningún secreto ni imagen con versión `latest` añadidos por esta historia.

## Assumptions

- Esta historia depende de LAB-22 (respuestas aumentadas con los documentos), ya terminada: existen la recuperación de
  contexto, el prompt aumentado, las citas y la memoria de conversación sobre los que se construye la traza.
- Hoy el backend no define herramientas propias para el modelo; el requisito de registrar las herramientas llamadas
  aplica a cualquier herramienta que otro módulo publique y que el modelo invoque, y se verifica con una herramienta de
  prueba.
- El coste de los modelos locales (Ollama) es cero; la tabla de precios existe para que cualquier modelo de pago futuro
  (por ejemplo, tras LAB-84) reciba un coste sin cambiar el diseño de la traza.
- Las conversaciones que se envían a las trazas son datos públicos (Olist) o de prueba; no se envían datos reales de
  clientes. Si alguna vez se usaran, haría falta una historia de enmascarado de datos (relacionada con LAB-66).
- En local el backend corre en el host y Langfuse en contenedores; el backend alcanza a Langfuse por la dirección
  publicada en el host. El envío de trazas se activa con una variable de entorno o propiedad, no es parte del perfil
  `llmops`.
- El ambiente DEV es el único ambiente del clúster (ADR-0008). El clúster local ofrece almacenamiento persistente con
  `local-path` (lo instala la plataforma), que sobrevive al reinicio de pods pero no a recrear el clúster; las trazas de
  DEV se pierden entonces, pero el proyecto y las claves se recrean desde git.
- Los cambios de infraestructura que esta historia requiera en `infra/` o en `shoplab-infra` forman parte de la misma
  historia y no se difieren.
- No se instalan nuevos componentes de plataforma fuera de Langfuse y los servicios que él necesita; las métricas de
  Prometheus y Grafana (LAB-102) siguen siendo la fuente de verdad para métricas, y Langfuse la de trazas de LLM.
- El envío de trazas lo decide el backend por configuración; no hay interfaz de usuario final nueva en el frontend.
