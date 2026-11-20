# Feature Specification: Prompts versionados en Langfuse

**Feature Branch**: `feature/LAB-85-versioned-prompts`

**Created**: 2026-11-19

**Status**: Draft

**Input**: User description: "LAB-85 (F2-09) Como desarrollador, quiero gestionar los prompts fuera del código para cambiarlos, compararlos y revertirlos sin redesplegar. Criterios: ningún prompt de sistema queda en el código; se cargan desde Langfuse con caché local y un valor por defecto si Langfuse no responde; cada traza guarda el nombre y la versión del prompt usado; cambiar la etiqueta `production` a otra versión cambia el comportamiento sin redesplegar; la evaluación de F2 puede ejecutarse contra dos versiones y compara sus métricas. Funciona en local con Langfuse autoalojado (LAB-64). Depende de LAB-64."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Los prompts viven en Langfuse y el chat los carga de allí (Priority: P1)

Como desarrollador, quiero que los textos que le dicen al modelo cómo comportarse (el prompt de sistema del chat, el de la
charla de cortesía y las dos plantillas del mensaje del cliente: con contexto recuperado y sin él) se guarden y se editen en Langfuse, y que el chat los
cargue de allí, para ajustarlos sin tocar el código ni redesplegar.

**Why this priority**: Es la base de la historia. Hoy los prompts son texto de configuración empaquetado con el backend, así
que cada ajuste exige cambiar archivos, compilar y redesplegar. Sin esto no hay nada que versionar, comparar ni revertir.

**Independent Test**: Con Langfuse local activo y los prompts publicados, preguntar al chat y comprobar que la respuesta
sigue las reglas del prompt que está en Langfuse; editar una palabra distintiva del prompt, publicar la versión y comprobar
que el chat la refleja sin reiniciar el backend.

**Acceptance Scenarios**:

1. **Given** Langfuse con los prompts publicados y la etiqueta `production` en una versión de cada uno, **When** el chat
   atiende una pregunta con conocimiento de la tienda, **Then** el modelo recibe el prompt de sistema y la plantilla de
   contexto de esas versiones.
2. **Given** lo mismo, **When** el chat atiende una cortesía («Hola», «Gracias»), **Then** el modelo recibe el prompt de
   charla de cortesía de la versión con etiqueta `production`.
3. **Given** el repositorio tal como queda al terminar la historia, **When** se revisa el código de la aplicación,
   **Then** no contiene el texto de ningún prompt: solo existe el valor por defecto de la historia 3, en archivos de
   recursos versionados aparte de la lógica.
4. **Given** un entorno nuevo con Langfuse recién creado y sin prompts, **When** el desarrollador ejecuta el procedimiento
   documentado de publicación inicial, **Then** cada prompt queda creado en Langfuse como versión 1 con la etiqueta
   `production`, con el mismo texto que el valor por defecto, y puede repetirse sin crear versiones nuevas.
5. **Given** un prompt cuya versión etiquetada `production` no declara una variable que el sistema necesita (por ejemplo,
   el hueco de los temas en el prompt de cortesía), **When** el chat intenta usarla, **Then** la rechaza, usa la última
   versión válida conocida o el valor por defecto, y deja un aviso que nombra el prompt y la versión rechazada.

---

### User Story 2 - Cada traza dice qué prompt y qué versión se usó (Priority: P1)

Como desarrollador, quiero ver en la traza de cada turno el nombre y la versión de cada prompt que se envió al modelo, para
saber con qué texto exacto se produjo una respuesta buena o mala.

**Why this priority**: Sin esa referencia no se puede atribuir un cambio de calidad a un cambio de prompt, y la comparación de
la historia 5 queda sin base. Es barata y completa el valor de las historias 1 y 3.

**Independent Test**: Hacer una pregunta de conocimiento y una cortesía, abrir ambas trazas en Langfuse y comprobar que cada
una indica el nombre y la versión de los prompts usados, y que se puede saltar desde la traza a esa versión del prompt.

**Acceptance Scenarios**:

1. **Given** un turno de conocimiento completado, **When** se abre su traza, **Then** muestra el nombre y la versión del
   prompt de sistema y de la plantilla de contexto con que se generó la respuesta.
2. **Given** un turno de cortesía, **When** se abre su traza, **Then** muestra el nombre y la versión del prompt de
   cortesía, y no muestra prompts que ese turno no usó.
3. **Given** que durante un turno la etiqueta `production` se mueve a otra versión, **When** el turno termina, **Then** su
   traza refleja la versión con que realmente empezó y terminó el turno, no la nueva.
4. **Given** un turno atendido con el valor por defecto porque Langfuse no respondió, **When** se abre su traza, **Then**
   el prompt aparece marcado como valor por defecto en lugar de con una versión de Langfuse.
5. **Given** dos turnos hechos antes y después de cambiar la versión `production`, **When** se filtran las trazas por el
   nombre y la versión del prompt, **Then** se separan los turnos de cada versión.

---

### User Story 3 - El chat no depende de Langfuse para tener prompt (Priority: P1)

Como desarrollador, quiero que el chat siga respondiendo con el prompt correcto aunque Langfuse esté lento o caído, para que
gestionar prompts fuera del código no añada un punto de fallo a la atención al cliente.

**Why this priority**: Es la condición para poder quitar los prompts del código con seguridad. Una dependencia dura de
Langfuse convertiría una herramienta de observabilidad en un requisito de disponibilidad del chat, algo que LAB-64 ya
descartó para las trazas.

**Independent Test**: Arrancar el backend con Langfuse detenido y hacer preguntas: el chat responde con el valor por defecto.
Arrancarlo con Langfuse activo, hacer una pregunta, detener Langfuse y seguir preguntando: el chat sigue usando el último
prompt cargado.

**Acceptance Scenarios**:

1. **Given** Langfuse detenido y el backend recién arrancado, **When** llega una pregunta, **Then** el chat responde con los
   prompts por defecto, sin error visible para el cliente.
2. **Given** prompts ya cargados y Langfuse que deja de responder, **When** llega una pregunta, **Then** el chat usa la
   última versión cargada de cada prompt, incluso si su tiempo de vigencia ya venció.
3. **Given** Langfuse que responde con lentitud, **When** llega una pregunta, **Then** la espera por el prompt está acotada y
   el turno no se retrasa más allá de ese tope: pasado el tope se usa la última versión conocida o el valor por defecto.
4. **Given** prompts cargados y vigentes, **When** llegan varias preguntas seguidas, **Then** no se consulta a Langfuse por
   cada pregunta, sino solo cuando vence la vigencia.
5. **Given** que Langfuse vuelve a responder tras una caída, **When** vence la vigencia siguiente, **Then** el chat vuelve a
   usar la versión `production` de Langfuse sin reiniciar el backend.
6. **Given** un prompt que no existe en Langfuse (no se publicó), **When** llega una pregunta que lo necesita, **Then** el
   chat usa el valor por defecto y deja un aviso que nombra el prompt faltante, sin repetirlo en cada pregunta.
7. **Given** el valor por defecto en uso, **When** se inspecciona el aviso del backend, **Then** no contiene texto de la
   conversación ni claves.

---

### User Story 4 - Cambiar la etiqueta `production` cambia el comportamiento sin redesplegar (Priority: P1)

Como desarrollador, quiero publicar una versión nueva de un prompt, ponerla en producción moviendo la etiqueta `production`,
y volver a la anterior si no funciona, sin compilar ni redesplegar nada.

**Why this priority**: Es el resultado que pide la historia: ciclo de cambio y reversión en minutos, no en un despliegue. Sin
él, versionar los prompts sería solo un archivo de historial.

**Independent Test**: Con una versión 1 en `production`, crear una versión 2 que cambie de forma visible la respuesta (por
ejemplo, un cierre fijo), mover `production` a la versión 2 y comprobar el cambio en el chat; moverla de nuevo a la versión 1
y comprobar que vuelve el comportamiento anterior. Ningún paso reinicia el backend.

**Acceptance Scenarios**:

1. **Given** el chat respondiendo con la versión 1 en `production`, **When** el desarrollador publica una versión 2 sin
   etiquetarla, **Then** el chat sigue respondiendo con la versión 1.
2. **Given** lo mismo, **When** mueve la etiqueta `production` a la versión 2, **Then** pasado como mucho el tiempo de
   vigencia configurado, las preguntas nuevas se responden con la versión 2, sin reiniciar ni redesplegar el backend.
3. **Given** la versión 2 en `production`, **When** mueve la etiqueta de vuelta a la versión 1, **Then** pasado el mismo
   tiempo, las preguntas nuevas vuelven al comportamiento de la versión 1.
4. **Given** una conversación con memoria en curso, **When** cambia la versión `production`, **Then** el siguiente turno de
   esa misma conversación usa la versión nueva sin perder el historial.
5. **Given** dos instancias del backend en marcha, **When** se mueve la etiqueta, **Then** ambas pasan a la versión nueva
   dentro del mismo tiempo de vigencia, sin intervención en ninguna.
6. **Given** que el desarrollador quiere comparar dos versiones antes de ponerlas en producción, **When** abre el prompt en
   Langfuse, **Then** puede ver el texto de cada versión y las diferencias entre ellas.

---

### User Story 5 - Evaluar y comparar dos versiones de un prompt (Priority: P2)

Como desarrollador, quiero ejecutar la evaluación de F2 contra dos versiones de un prompt y ver sus métricas una junto a la
otra, para decidir con datos cuál de las dos poner en `production`.

**Why this priority**: Cierra el ciclo de la Constitución (V): un cambio de prompt sin resultado de evaluación se rechaza. No
es P1 porque las historias 1 a 4 ya entregan valor sin él, y porque depende de que la medición de calidad de LAB-29 esté
disponible.

**Independent Test**: Con dos versiones de un prompt publicadas, ejecutar la medición una vez por versión sin mover
`production`, y pedir la comparación: se obtiene una tabla con cada métrica para ambas versiones y su diferencia.

**Acceptance Scenarios**:

1. **Given** dos versiones publicadas de un prompt y `production` en una tercera o en una de ellas, **When** el desarrollador
   ejecuta la medición fijando la versión A y luego la versión B, **Then** cada ejecución consulta al asistente con la versión
   fijada y no cambia la etiqueta `production` ni afecta al chat de otros usuarios.
2. **Given** una ejecución de evaluación terminada, **When** se lee su resultado, **Then** registra el nombre y la versión de
   cada prompt usado, de modo que dos resultados puedan distinguirse y reproducirse.
3. **Given** los resultados de las dos ejecuciones sobre el mismo dataset, **When** el desarrollador pide compararlos,
   **Then** obtiene por cada métrica la media de A, la media de B y su diferencia, con el número de casos que entró en cada
   media.
4. **Given** una versión fijada que no existe en Langfuse, **When** se lanza la medición, **Then** termina con error y código
   distinto de cero antes de consultar al asistente en cantidad, y nombra el prompt y la versión que faltan.
5. **Given** dos resultados con parámetros que los hacen incomparables (otro modelo del asistente, otro juez o un dataset
   distinto), **When** se piden comparar, **Then** la comparación lo señala en lugar de presentar una diferencia engañosa.
6. **Given** las trazas de las dos ejecuciones en Langfuse, **When** se filtran por la ejecución, **Then** cada traza lleva el
   nombre y la versión del prompt con que se generó.
7. **Given** una persona que usa el chat con normalidad, **When** envía un mensaje cualquiera, **Then** no tiene forma de
   elegir la versión de un prompt: esa elección solo la hace quien ejecuta la evaluación.

---

### Edge Cases

- **Edición mala en Langfuse**: una versión vacía, sin una variable obligatoria o con una variable desconocida y puesta en
  `production` no debe romper el chat. Se rechaza al cargarla, se conserva la última versión válida o el valor por defecto
  y se avisa (escenario 5 de la historia 1).
- **Primer arranque con Langfuse sin prompts**: el chat usa los valores por defecto y avisa una sola vez por prompt faltante.
- **Cambio de etiqueta durante un turno**: el turno que ya empezó termina con la versión con que empezó; el cambio solo
  alcanza a los turnos nuevos.
- **Etiqueta `production` retirada**: si ninguna versión de un prompt lleva la etiqueta, se trata como prompt no disponible
  y se usa la última versión válida conocida o el valor por defecto, con aviso.
- **Prompt con texto muy grande**: una versión cuyo texto supera un tope razonable se rechaza como una edición mala, para no
  inflar cada petición al modelo ni la traza.
- **Valor por defecto distinto de lo publicado**: el texto por defecto del repositorio puede ir cambiando y Langfuse es la
  fuente de verdad; la publicación inicial solo crea los prompts que no existen y nunca toca los que ya tienen versiones. Las
  versiones nuevas de un prompt existente se crean en Langfuse.
- **Envío de trazas desactivado pero prompts gestionados**: la gestión de prompts y el envío de trazas se activan por
  separado; con trazas apagadas los prompts se siguen cargando, y con la gestión apagada se usan siempre los valores por
  defecto.
- **Contenido sensible en un prompt**: un prompt no debe contener claves ni datos de clientes; la guía lo dice y las trazas ya
  aplican el tope y el saneado de LAB-64.
- **Evaluación contra una etiqueta en vez de una versión**: fijar la etiqueta (por ejemplo, `staging`) debe poder servir como
  alternativa a fijar un número de versión; el resultado registra la versión concreta a la que apuntaba la etiqueta en ese
  momento.
- **Caída de Langfuse durante una evaluación**: la evaluación falla con un error claro y no mide con el valor por defecto sin
  avisar, porque eso falsearía la comparación.

## Requirements *(mandatory)*

### Functional Requirements

**Origen de los prompts**

- **FR-001**: Los textos de los prompts que el backend envía al modelo (prompt de sistema del chat, prompt de la charla de
  cortesía, plantilla del mensaje con contexto recuperado y plantilla del mensaje sin contexto) MUST obtenerse de Langfuse, identificados por un nombre estable cada uno.
- **FR-002**: El código de la aplicación MUST NOT contener el texto de ningún prompt. El valor por defecto de cada prompt MUST
  vivir en archivos de recursos versionados, separados de la lógica, y ser la única copia del texto en el repositorio.
- **FR-003**: Por omisión el backend MUST pedir a Langfuse la versión con la etiqueta `production` de cada prompt; el nombre de
  la etiqueta MUST ser configurable.
- **FR-004**: Un prompt cargado MUST validarse antes de usarse: no vacío, dentro de un tope de tamaño, con las
  variables que el sistema necesita rellenar y sin ninguna otra que impida construir el mensaje. Si no es válido MUST descartarse, conservar la última versión válida o, si no hay,
  el valor por defecto, y emitir un aviso con el nombre del prompt y el motivo.
- **FR-005**: Debe existir un procedimiento documentado, repetible y sin pasos manuales en la interfaz, que publique en
  Langfuse el valor por defecto de cada prompt como versión inicial con la etiqueta `production`, para el entorno local y para
  DEV. Solo MUST crear los prompts que no existen: ejecutarlo de nuevo, o contra un Langfuse que ya los tiene, MUST NOT
  crear versiones ni mover la etiqueta `production` de un prompt existente, aunque su texto difiera del valor por defecto.

**Caché y resiliencia**

- **FR-006**: El backend MUST guardar localmente los prompts cargados con un tiempo de vigencia configurable; mientras no
  venza, MUST NOT consultar a Langfuse por cada pregunta.
- **FR-007**: Al vencer la vigencia, la actualización MUST hacerse sin retrasar la pregunta que la dispara más allá del tope de
  espera de FR-009, y MUST propagar un cambio de etiqueta a todas las instancias del backend dentro de esa vigencia, sin
  reinicio.
- **FR-008**: Si Langfuse no responde, rechaza la petición o el prompt no existe, el backend MUST usar, por este orden, la
  última versión válida cargada (aunque haya vencido) y el valor por defecto. MUST NOT devolver error al cliente por esta
  causa.
- **FR-009**: La espera por Langfuse al resolver un prompt MUST tener un tope configurable y corto; superado, se aplica
  FR-008.
- **FR-010**: Los avisos por prompt no disponible o inválido MUST emitirse con frecuencia acotada (no uno por pregunta) y MUST
  NOT incluir texto de la conversación, claves ni cabeceras de autorización.
- **FR-011**: La gestión de prompts MUST poder activarse o desactivarse por configuración, independientemente del envío de
  trazas; desactivada, el backend usa siempre los valores por defecto y no contacta con Langfuse para prompts.
- **FR-012**: Cada turno MUST fijar la versión de cada prompt al empezar y mantenerla hasta terminar; un cambio de etiqueta
  durante el turno no lo afecta.

**Trazabilidad**

- **FR-013**: La traza de cada turno MUST registrar, por cada prompt usado, su nombre y su versión, de modo que Langfuse pueda
  enlazar la generación con esa versión del prompt y filtrar las trazas por ellos.
- **FR-014**: Cuando se use el valor por defecto, la traza MUST indicarlo de forma inequívoca en lugar de una versión de
  Langfuse.
- **FR-015**: La traza MUST registrar solo los prompts que ese turno usó (la charla de cortesía no registra la plantilla de
  contexto).
- **FR-016**: El registro del prompt en la traza MUST NOT impedir ni retrasar la respuesta del chat; si no puede registrarse,
  se aplican las reglas de LAB-64 para fallas de trazas.

**Evaluación**

- **FR-017**: La medición de calidad de respuesta de F2 MUST poder ejecutarse fijando, por prompt, una versión o una etiqueta
  distinta de `production`, sin cambiar la etiqueta `production` en Langfuse ni el comportamiento del chat para el resto de
  los usuarios.
- **FR-018**: Fijar una versión para una evaluación MUST ser una acción reservada a quien ejecuta la evaluación; un cliente del
  chat MUST NOT poder elegir la versión de un prompt mediante su petición ni su mensaje.
- **FR-019**: Antes de consultar al asistente en cantidad, la evaluación MUST comprobar que cada versión fijada existe y es
  válida; si no, MUST terminar con error, código de salida distinto de cero, sin escribir resultado y nombrando el prompt y la
  versión.
- **FR-020**: El resultado de cada evaluación MUST registrar el nombre y la versión concreta de cada prompt usado (si se fijó
  una etiqueta, la versión a la que apuntaba), junto con los parámetros que ya hacen comparable un resultado (juez, modelo del
  asistente, dataset).
- **FR-021**: Debe existir una comparación de dos resultados que muestre, por métrica, la media de cada uno, la diferencia y el
  número de casos incluidos, e indique qué prompts y versiones distinguen a un resultado del otro.
- **FR-022**: La comparación MUST advertir cuando los dos resultados no son comparables (distinto dataset, modelo del asistente
  o juez) y MUST NOT presentar una diferencia sin esa advertencia.
- **FR-023**: Una evaluación con Langfuse caído MUST fallar con un error claro; MUST NOT medir con el valor por defecto sin
  avisarlo, porque falsearía la comparación.
- **FR-024**: Las trazas de la medición MUST llevar el nombre y la versión de los prompts con que se generaron, y la ejecución
  a la que pertenecen (identificador de LAB-29).

**Calidad y documentación**

- **FR-025**: La carga, validación, caché, resolución de reemplazo y registro en la traza MUST verificarse con pruebas
  automáticas deterministas que no dependan de un Langfuse real ni de un modelo real. La lógica de comparación de resultados
  MUST poder probarse sin llamar a ningún modelo.
- **FR-026**: El procedimiento para editar un prompt, probar una versión, compararla, ponerla en producción y revertirla, y las
  nuevas variables de configuración con sus valores locales, MUST documentarse junto con las instrucciones de LAB-64.
- **FR-027**: Las decisiones de arquitectura relevantes (Langfuse como fuente de los prompts, caché y reemplazo, cómo se fija
  una versión en la evaluación) MUST registrarse como ADR en `docs/adr/`.
- **FR-028**: La historia MUST NOT cambiar el texto de ningún prompt ni el comportamiento del asistente: la versión 1 de cada
  prompt es el valor actual, de modo que activar la gestión no altera las respuestas.
- **FR-029**: Quedan fuera de esta historia: la evaluación en CI con línea base y umbrales de bloqueo (LAB-30), la persistencia
  de ejecuciones en base de datos (LAB-31), la pantalla de administración de evaluaciones (LAB-32), una interfaz propia para
  editar prompts dentro de ShopLab (se usa la de Langfuse), el despliegue de Langfuse en el clúster (LAB-64) y las pruebas A/B
  con tráfico real de clientes.

### Key Entities *(include if feature involves data)*

- **Prompt**: texto de instrucciones que se envía al modelo, con un nombre estable (por ejemplo, prompt de sistema, de
  cortesía, plantilla de contexto) y las variables que el sistema rellena en cada turno.
- **Versión de prompt**: edición numerada e inmutable de un prompt en Langfuse; cada cambio crea una versión nueva y las
  anteriores se conservan.
- **Etiqueta**: marca movible que apunta a una versión; `production` indica la que usa el chat. Mover la etiqueta es cómo se
  promueve o revierte una versión.
- **Valor por defecto**: texto de un prompt guardado en recursos versionados del repositorio; se usa solo si Langfuse no puede
  entregar uno válido y sirve de versión inicial al publicar un prompt nuevo.
- **Referencia de prompt en traza**: nombre y versión (o marca de valor por defecto) de cada prompt que usó un turno.
- **Ejecución de evaluación con prompts fijados**: medición de LAB-29 que registra qué versión de cada prompt se usó.
- **Comparación de versiones**: lectura lado a lado de dos resultados de evaluación: métricas de cada uno, diferencias y
  advertencias de comparabilidad.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los textos de prompt que el asistente envía al modelo proviene de Langfuse o, en su defecto, de los
  archivos de valores por defecto; una búsqueda de esos textos en el código de la aplicación no encuentra ninguno.
- **SC-002**: Un desarrollador pasa de «quiero cambiar una instrucción del asistente» a verla funcionando en el chat en menos
  de 5 minutos, sin compilar, reiniciar ni redesplegar nada.
- **SC-003**: Tras mover la etiqueta `production`, el 100 % de las preguntas hechas pasado el tiempo de vigencia configurado
  (por omisión, 1 minuto o menos) usa la versión nueva, en todas las instancias del backend.
- **SC-004**: Revertir a la versión anterior lleva menos de 2 minutos y restaura exactamente el comportamiento anterior.
- **SC-005**: El 100 % de los turnos de una ejecución de 20 preguntas de prueba tiene en su traza el nombre y la versión de
  cada prompt usado, y un desarrollador responde en menos de 1 minuto «¿con qué versión del prompt se generó esta
  respuesta?».
- **SC-006**: Con Langfuse detenido, el 100 % de las preguntas del chat se responde completa y sin error; con Langfuse activo,
  el tiempo hasta el primer token aumenta menos de un 5 % frente a usar los valores por defecto.
- **SC-007**: Ninguna edición inválida de un prompt en Langfuse provoca una respuesta fallida: en las pruebas con una versión
  vacía, una sin variable obligatoria y una demasiado grande, el 100 % de las preguntas se responde con la última versión
  válida o con el valor por defecto.
- **SC-008**: En un entorno local nuevo, un solo procedimiento documentado deja los prompts publicados y funcionando en
  menos de 5 minutos, y ejecutarlo de nuevo no cambia nada.
- **SC-009**: Un desarrollador obtiene la comparación de dos versiones de un prompt sobre el dataset golden con una ejecución
  por versión y un solo comando de comparación, y la tabla incluye todas las métricas de calidad de LAB-29.
- **SC-010**: Una evaluación contra una versión fijada deja intacta la etiqueta `production` y no cambia la respuesta de
  ninguna pregunta hecha al chat durante la evaluación (0 diferencias).
- **SC-011**: Con los textos actuales como versión 1, las respuestas del asistente a las preguntas del dataset golden no
  empeoran en las métricas de recuperación (LAB-28) ni en las de calidad (LAB-29) respecto a antes de la historia.

## Assumptions

- Esta historia depende de LAB-64 (Langfuse autoalojado y trazas), ya fusionada: aporta el Langfuse local y del clúster, sus
  claves y las trazas de cada turno donde se registra la referencia del prompt. También se apoya en LAB-114 (charla de
  cortesía), que añadió el prompt de cortesía, y en LAB-84 (gateway de modelos).
- Son «prompts» de esta historia los cuatro textos que hoy el backend envía al modelo: el prompt de sistema del chat, el de la
  charla de cortesía, la plantilla del mensaje con contexto recuperado y la del mensaje sin contexto (esta solo se usa cuando
  la ausencia de contexto se deja responder al modelo). Otros textos (mensajes de error al cliente, la lista
  cerrada de cortesías y los temas que anuncia el asistente) son datos de configuración, no prompts, y siguen donde están. Si
  se quiere dejar alguna plantilla fuera, basta quitarla de FR-001 y de la publicación inicial.
- El valor por defecto de cada prompt es el texto actual de la configuración, trasladado a archivos de recursos sin
  modificarlo; así se cumple «ningún prompt en el código» y el chat sigue funcionando sin Langfuse. Esto sustituye las
  propiedades de configuración con texto de prompt y sus variables de entorno de sustitución, lo cual es un cambio de
  configuración que se documenta.
- La medición de calidad de respuesta de LAB-29 (consulta al asistente real, juez configurable, CSV y puntuaciones en la
  traza) ya está fusionada en `main` y es la base de la historia 5. Las historias 1 a 4 no
  dependen de ella. Las métricas de recuperación de LAB-28 no cambian con el prompt y no forman parte de la comparación.
- La evaluación se ejecuta contra un backend arrancado por quien la ejecuta (local o DEV), no contra el chat que ven los
  clientes; el mecanismo exacto para fijar la versión en esa ejecución se decide en el plan, con la condición de FR-018.
- Langfuse conserva todas las versiones de un prompt y permite etiquetas y comparación de versiones en su interfaz; esta
  historia usa esas capacidades y no construye una interfaz propia.
- Las trazas y los prompts se mueven por datos públicos o de prueba; los prompts no deben contener datos reales de clientes
  ni claves.
- El ambiente DEV es el único ambiente del clúster (ADR-0008). Los cambios de infraestructura que esta historia requiera en
  `infra/` o en `shoplab-infra` forman parte de la misma historia y no se difieren (por ejemplo, ejecutar la publicación
  inicial de los prompts en DEV y las variables del backend desplegado).
- Esta historia es de F2 y se ejecuta en local con el perfil `llmops` de docker compose; no añade servicios nuevos.
- Cambiar un prompt es un cambio que la Constitución (V) exige evaluar; esta historia entrega la herramienta para hacerlo y
  no cambia el texto de ningún prompt.
- «Tiempo de vigencia» (FR-006, FR-007, FR-009, SC-003) equivale al intervalo de refresco configurable: cada prompt se vuelve a
  consultar en segundo plano y la última versión válida nunca caduca. Ninguna pregunta dispara ni espera una consulta; el tope
  de espera de FR-009 acota cada petición de refresco, no el turno.
