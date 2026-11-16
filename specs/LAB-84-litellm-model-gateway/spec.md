# Feature Specification: Gateway de modelos con LiteLLM

**Feature Branch**: `feature/LAB-84-litellm-model-gateway`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "LAB-84 (F2-08) Como desarrollador, quiero que todas las llamadas a modelos pasen por un gateway para cambiar de modelo, tener fallback y controlar el coste sin tocar el código. Criterios: LiteLLM proxy en docker compose y en el clúster; el backend y `ingest` solo hablan con él (API compatible con OpenAI). Ruta por defecto a Ollama local; un proveedor cloud es opcional y queda desactivado si no hay clave. Fallback demostrable entre dos modelos locales (por ejemplo, uno grande que cae a uno pequeño). Presupuesto por clave virtual y entorno, con precios configurados para medir el coste aunque el modelo sea local. Coste y tokens por conversación visibles en los logs del gateway. Funciona en local: sí, 100 % sin claves de nube. Depende de: LAB-22."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Todas las llamadas a modelos pasan por el gateway (Priority: P1)

Como desarrollador, quiero que el chat y los embeddings del backend (y cualquier llamada a un modelo que haga `ingest`)
salgan siempre hacia un único gateway que decide a qué modelo enviarlas, para que el resto del sistema no conozca ni
dependa de qué servidor de modelos hay detrás.

**Why this priority**: Es la base de todo lo demás: el cambio de modelo, el fallback, el presupuesto y el coste solo
pueden aplicarse si no existe ningún camino que se salte el gateway. Sin esta historia no hay gateway, solo un servicio
más en compose.

**Independent Test**: Levantar el entorno local solo con Ollama y el gateway, hacer una pregunta al chat sobre un documento
indexado y subir un documento nuevo (que genera embeddings), y comprobar en los registros del gateway que ambas
operaciones llegaron por él y que el servidor de modelos recibió las peticiones del gateway y de nadie más.

**Acceptance Scenarios**:

1. **Given** el entorno local con el gateway en marcha, **When** el chat atiende una pregunta, **Then** la generación de
   la respuesta se pide al gateway y llega al cliente en streaming con los mismos eventos que antes de la historia.
2. **Given** el entorno local con el gateway en marcha, **When** el backend calcula embeddings (subida de un documento o
   recuperación de contexto), **Then** la petición pasa por el gateway y devuelve vectores de la misma dimensión que
   antes.
3. **Given** el backend configurado, **When** se revisa su configuración y su código, **Then** no queda ninguna dirección
   ni credencial de un servidor de modelos concreto: solo la dirección del gateway y la clave virtual del consumidor.
4. **Given** un servicio que no sea el gateway, **When** se revisa la red del entorno local y la del clúster, **Then**
   ningún componente de la aplicación (backend, `ingest`) tiene una ruta hacia el servidor de modelos; la única entrada
   es el gateway.
5. **Given** una pregunta que usa memoria de conversación, recuperación de contexto y herramientas, **When** el chat la
   responde a través del gateway, **Then** el comportamiento (citas, historial, llamadas a herramientas, uso de tokens
   informado) es equivalente al anterior a la historia.
6. **Given** el gateway detenido, **When** el chat recibe una pregunta, **Then** el usuario recibe el mismo error tipado
   de «modelo no disponible» que hoy recibe cuando el servidor de modelos está caído, sin detalles internos.

---

### User Story 2 - Cambiar de modelo sin tocar el código (Priority: P1)

Como desarrollador, quiero cambiar el modelo que responde (o el que calcula embeddings) editando solo la configuración del
gateway, para probar otro modelo sin recompilar ni redesplegar el backend.

**Why this priority**: Es el primer motivo de la historia («cambiar de modelo… sin tocar el código») y lo que hace
rentable pasar todo por un gateway.

**Independent Test**: Con el entorno en marcha, cambiar en la configuración del gateway el modelo al que apunta el nombre
lógico de chat, recargar el gateway y hacer una pregunta; la traza y los registros del gateway muestran el modelo nuevo y
el backend no se ha tocado ni reiniciado.

**Acceptance Scenarios**:

1. **Given** el backend que pide siempre el mismo nombre lógico de modelo, **When** se cambia en la configuración del
   gateway el modelo real asociado a ese nombre, **Then** las preguntas siguientes se responden con el modelo nuevo sin
   modificar ni reiniciar el backend.
2. **Given** una configuración de modelos con un error (por ejemplo, un modelo que no existe en el servidor de modelos),
   **When** el gateway arranca o se recarga, **Then** el error queda visible en sus registros y el sistema indica qué
   modelo falla, en vez de fallar de forma opaca en la primera pregunta.
3. **Given** el nombre lógico de embeddings, **When** se quiere asociar a un modelo con distinta dimensión de vector,
   **Then** la documentación indica que exige reindexar (ADR-0003) y el sistema no mezcla en silencio vectores de
   dimensiones distintas.
4. **Given** una petición del backend con un nombre de modelo que el gateway no tiene configurado, **When** llega, **Then**
   el gateway la rechaza con un error claro y el usuario del chat recibe el error tipado de modelo, no un fallo genérico.

---

### User Story 3 - Fallback demostrable entre dos modelos locales (Priority: P2)

Como desarrollador, quiero que, si el modelo principal falla, el gateway responda con un modelo de respaldo, para que el
chat siga contestando aunque el modelo grande no esté disponible o tarde demasiado.

**Why this priority**: Aporta resiliencia y es el criterio que más cuesta demostrar sin nube; depende de que las llamadas
ya pasen por el gateway (historia 1) y de poder cambiar de modelo por configuración (historia 2).

**Independent Test**: Con dos modelos locales configurados (uno principal y otro de respaldo), dejar el principal
inaccesible siguiendo el procedimiento documentado, hacer una pregunta y comprobar que la respuesta llega completa, que
los registros del gateway indican que cayó al modelo de respaldo, y que al restaurar el principal las preguntas vuelven a
usarlo.

**Acceptance Scenarios**:

1. **Given** un modelo principal y otro de respaldo configurados para el nombre lógico de chat, **When** el principal no
   está disponible, **Then** la respuesta se genera con el de respaldo y el usuario la recibe completa, sin error.
2. **Given** una respuesta servida por el respaldo, **When** se consultan los registros del gateway y la traza del turno,
   **Then** ambos identifican qué modelo respondió realmente y que hubo fallback, además del modelo que se pidió.
3. **Given** el principal que tarda más que el tiempo máximo configurado en dar el primer fragmento, **When** el gateway
   agota ese tiempo, **Then** cae al respaldo igual que si hubiera fallado.
4. **Given** el principal restablecido, **When** llegan nuevas preguntas, **Then** vuelven a usar el principal sin
   reiniciar nada.
5. **Given** el principal y el respaldo no disponibles a la vez, **When** llega una pregunta, **Then** el usuario recibe el
   error tipado de «modelo no disponible» tras un número acotado de intentos, no una espera indefinida.
6. **Given** el procedimiento de demostración documentado, **When** un desarrollador lo sigue en su máquina, **Then**
   reproduce el fallback en menos de 5 minutos sin claves de nube y sin editar código.

---

### User Story 4 - Presupuesto por clave virtual y entorno (Priority: P2)

Como desarrollador, quiero que cada consumidor del gateway (backend, `ingest`) tenga su propia clave virtual por entorno,
con un presupuesto de gasto, para que ningún componente consuma más de lo previsto y para atribuir el coste a quien lo
genera.

**Why this priority**: Es el control de coste que justifica el gateway. Los precios (incluso de modelos locales) permiten
ejercitarlo sin gastar dinero real, pero requiere que las llamadas ya pasen por el gateway.

**Independent Test**: Configurar una clave con un presupuesto muy pequeño, hacer preguntas hasta superarlo y comprobar que
el gateway rechaza las siguientes peticiones de esa clave, mientras las claves de otros consumidores y entornos siguen
funcionando.

**Acceptance Scenarios**:

1. **Given** el entorno local, **When** se levanta el gateway por primera vez, **Then** ya existen una clave virtual por
   consumidor (backend, `ingest`) con su presupuesto, creadas desde configuración versionada, sin alta manual.
2. **Given** la clave del backend en `dev` y otra en local, **When** se consulta el gasto, **Then** cada una lleva su
   propio contador y presupuesto, y el gasto de una no afecta a la otra.
3. **Given** una clave que ha agotado su presupuesto, **When** el backend hace otra petición, **Then** el gateway la
   rechaza con un error de presupuesto agotado y el usuario del chat recibe un error tipado distinto del de «modelo no
   disponible», sin detalles internos.
4. **Given** una clave con presupuesto agotado, **When** llega el siguiente periodo de reinicio del presupuesto, **Then**
   la clave vuelve a aceptar peticiones sin intervención manual.
5. **Given** un modelo local, **When** responde una pregunta, **Then** su uso se valora con el precio configurado para ese
   modelo, de modo que el gasto sube aunque no haya coste real y el presupuesto se puede ejercitar sin nube.
6. **Given** una petición con una clave inexistente, revocada o ausente, **When** llega al gateway, **Then** se rechaza
   con un error de autenticación y no se atiende ningún modelo.

---

### User Story 5 - Ver coste y tokens por conversación en los logs del gateway (Priority: P2)

Como desarrollador, quiero ver en los logs del gateway cuántos tokens y cuánto coste ha tenido cada petición y a qué
conversación pertenece, para saber cuánto cuesta una conversación sin abrir ninguna otra herramienta.

**Why this priority**: Es el criterio de visibilidad del coste. Se puede entregar después del enrutado y del presupuesto
sin perder el MVP, pero sin él el presupuesto no se puede auditar.

**Independent Test**: Mantener una conversación de tres turnos en el chat y, solo con los logs del gateway, obtener el
total de tokens de entrada, de salida y el coste de esa conversación.

**Acceptance Scenarios**:

1. **Given** una petición atendida, **When** se lee su línea de log en el gateway, **Then** contiene el consumidor (clave
   virtual), el identificador de la conversación, el modelo pedido y el que respondió, tokens de entrada y de salida,
   coste, latencia y resultado.
2. **Given** tres turnos de una misma conversación, **When** se suman sus líneas de log por identificador de
   conversación, **Then** el total coincide con la suma de los tokens y costes de cada turno.
3. **Given** una petición del chat, **When** llega al gateway, **Then** lleva el identificador de la petición del chat
   (el mismo de los logs y de las trazas del backend) para poder pasar de un log a otro.
4. **Given** una petición de embeddings, **When** se registra, **Then** se atribuye a la operación (subida de documento o
   recuperación de contexto) y no a una conversación inexistente.
5. **Given** cualquier petición, **When** se inspeccionan los logs del gateway, **Then** no contienen el texto de las
   preguntas, las respuestas, el contexto recuperado ni las claves; solo metadatos y conteos.
6. **Given** el procedimiento documentado, **When** un desarrollador quiere el coste de una conversación, **Then** puede
   obtenerlo con un comando o filtro documentado en menos de 1 minuto.

---

### User Story 6 - Proveedor cloud opcional, desactivado sin clave (Priority: P3)

Como desarrollador, quiero poder añadir un proveedor de modelos en la nube solo con aportar su clave, y que sin clave no
exista ni cause errores, para que el laboratorio siga funcionando al 100 % en local.

**Why this priority**: Demuestra que el gateway sirve para cambiar de proveedor, pero no es necesario para el MVP, que debe
funcionar sin claves de nube.

**Independent Test**: Arrancar el entorno sin ninguna clave de nube y comprobar que todo funciona y que el modelo cloud no
figura como disponible; después aportar una clave de prueba (o un servidor simulado compatible) y comprobar que el modelo
cloud pasa a estar disponible y se puede pedir.

**Acceptance Scenarios**:

1. **Given** el entorno sin ninguna clave de proveedor cloud, **When** se levanta, **Then** el gateway arranca sin error ni
   aviso alarmante, los modelos locales funcionan y el modelo cloud no aparece como disponible.
2. **Given** una petición al nombre del modelo cloud sin clave configurada, **When** llega al gateway, **Then** se
   rechaza con un error claro de «modelo no disponible», sin intentar salir a Internet.
3. **Given** una clave de proveedor cloud aportada por configuración, **When** se reinicia el gateway, **Then** el modelo
   cloud pasa a estar disponible, sujeto a presupuesto y a registro de coste como cualquier otro.
4. **Given** un modelo cloud disponible, **When** no se ha configurado explícitamente como principal ni como respaldo,
   **Then** ninguna petición del chat lo usa.
5. **Given** el repositorio, **When** se revisa, **Then** no contiene ninguna clave de proveedor cloud y las pruebas
   automáticas no dependen de una cuenta real.

---

### User Story 7 - Gateway funcionando en el ambiente DEV del clúster (Priority: P2)

Como desarrollador, quiero que el gateway corra en el clúster y que el backend desplegado en DEV lo use, para que el
ambiente compartido tenga los mismos controles (fallback, presupuesto, coste) que el local.

**Why this priority**: El criterio lo pide de forma explícita («en docker compose y en el clúster»), pero depende de que el
gateway ya funcione en local.

**Independent Test**: Desplegar con Argo CD, hacer una pregunta al chat de DEV y comprobar en los logs del gateway del
clúster que la petición llegó por él con su consumidor, modelo, tokens y coste.

**Acceptance Scenarios**:

1. **Given** el ambiente `dev` sincronizado por Argo CD, **When** se consultan los recursos del clúster, **Then** el
   gateway (y su almacén de estado) están en ejecución y sanos, con imágenes de versión fija.
2. **Given** el gateway sano en el clúster, **When** el backend de DEV atiende una pregunta, **Then** la petición llega al
   servidor de modelos del clúster solo a través del gateway.
3. **Given** el clúster recién creado (`destroy` y `apply`), **When** Argo CD sincroniza `dev`, **Then** el gateway queda
   funcionando con sus modelos, claves virtuales y presupuestos, sin pasos manuales.
4. **Given** las credenciales del gateway en DEV (clave maestra, claves virtuales, clave de proveedor cloud si la hubiera),
   **When** se revisa el repositorio, **Then** ninguna credencial real está versionada: llegan por el mecanismo de
   secretos que ya usa el resto del ambiente.
5. **Given** el clúster local de un solo nodo, **When** el gateway está desplegado, **Then** el backend, Ollama, Langfuse
   y el resto de componentes siguen pudiendo ejecutarse con los recursos del nodo.

---

### Edge Cases

- ¿Qué pasa si el primer fragmento ya se emitió y el modelo falla a mitad de la respuesta? El fallback solo puede ocurrir
  antes de que se emita el primer fragmento; una vez empezada la respuesta, un fallo se trata como hoy (error a mitad de
  stream conservando el texto parcial), no se reinicia con otro modelo.
- ¿Qué pasa si el modelo de respaldo no informa del uso de tokens? Los tokens se registran como «desconocidos», nunca como
  cero, y el coste de esa petición se marca como no calculable, igual que en las trazas de LAB-64.
- ¿Qué pasa con el fallback de embeddings? No se configura por defecto: un respaldo con otra dimensión de vector
  corrompería el índice. Si se configurase, debe exigir la misma dimensión y el gateway debe rechazar la configuración en
  caso contrario.
- ¿Qué pasa con el precio de un modelo local? Es un precio de referencia configurado (no un cobro real) que sirve para
  medir y presupuestar; los registros lo indican como coste estimado. Un modelo sin entrada en la tabla de precios se
  marca como «sin precio» en vez de valer cero o inventarse un valor.
- ¿Qué pasa si el coste del gateway y el de las trazas de Langfuse difieren? Son dos mediciones con tablas de precios
  distintas (el gateway usa precios de referencia, las trazas conservan la tabla de LAB-64); la documentación lo explica
  y queda como decisión del plan si se unifican.
- ¿Qué pasa si el gateway pierde su almacén de estado? Las claves virtuales y los presupuestos se recrean desde la
  configuración versionada al arrancar; el gasto acumulado del periodo puede perderse y se acepta y documenta.
- ¿Qué pasa si el gateway arranca antes que el servidor de modelos? Espera a que esté sano o reintenta; no queda roto de
  forma permanente ni marca como inválidos los modelos que aún no responden.
- ¿Qué pasa con peticiones simultáneas de varias conversaciones? Cada una se registra con su propia conversación y consumo;
  el presupuesto se descuenta de forma consistente y nunca se supera por mucho en carreras (se acepta un pequeño exceso
  de las peticiones ya en vuelo).
- ¿Qué pasa si el gateway añade latencia? El tiempo hasta el primer token con el gateway no debe aumentar de forma
  apreciable (ver SC-004); el gateway no almacena en búfer la respuesta completa antes de entregarla.
- ¿Qué pasa con la interfaz de administración del gateway? No se expone: la administración es por configuración
  versionada, y cualquier acceso administrativo queda protegido por la clave maestra y fuera de redes públicas.
- ¿Qué pasa en una máquina con poca memoria? El gateway es ligero y el modelo de respaldo se elige pequeño; el modelo
  principal por defecto no obliga a descargar modelos más grandes que hoy.
- ¿Qué pasa si los puertos locales del gateway o de su almacén ya están ocupados? Se pueden cambiar con variables, como el
  resto de servicios de compose.

## Requirements *(mandatory)*

### Functional Requirements

**Un único punto de salida hacia los modelos**

- **FR-001**: El backend (chat y embeddings) y `ingest` MUST hacer toda llamada a un modelo exclusivamente al gateway,
  usando una API compatible con OpenAI, y MUST NOT contener la dirección ni las credenciales de ningún servidor de modelos
  concreto.
- **FR-002**: El gateway MUST ser la única entrada hacia el servidor de modelos local y hacia cualquier proveedor cloud:
  ningún otro componente de la aplicación MUST tener ruta hacia ellos, ni en compose ni en el clúster.
- **FR-003**: El chat MUST mantener su comportamiento observable al pasar por el gateway: streaming con los mismos eventos,
  memoria de conversación, citas, llamadas a herramientas y uso de tokens informado, sin cambios en el contrato con el
  frontend.
- **FR-004**: Los errores del gateway (modelo no disponible, modelo desconocido, presupuesto agotado, clave inválida) MUST
  traducirse a errores tipados del backend, con código propio en `ResponseCodeEnum`, y MUST NOT filtrar detalles internos
  al usuario; el de presupuesto agotado MUST ser distinguible del de modelo no disponible.
- **FR-005**: El acceso a modelos en el backend MUST seguir detrás de los puertos existentes de modo que el dominio no
  dependa del gateway ni de la API OpenAI (reglas de arquitectura del proyecto).

**Modelos configurables sin tocar código**

- **FR-006**: El gateway MUST exponer nombres lógicos de modelo (al menos uno para chat y otro para embeddings) y asociar
  cada uno a uno o más modelos reales mediante configuración versionada; el backend e `ingest` MUST pedir solo nombres
  lógicos.
- **FR-007**: Cambiar el modelo real asociado a un nombre lógico MUST requerir solo editar la configuración del gateway y
  recargarlo, sin recompilar ni redesplegar el backend.
- **FR-008**: El gateway MUST validar su configuración al arrancar y MUST dejar en sus registros qué modelo está mal
  configurado o no disponible.
- **FR-009**: Los modelos de embeddings MUST NOT tener fallback a un modelo con otra dimensión de vector; la
  documentación MUST advertir de que cambiar el modelo de embeddings exige reindexar.

**Ruta por defecto local y proveedor cloud opcional**

- **FR-010**: La ruta por defecto de todos los nombres lógicos MUST ser Ollama local y MUST funcionar al 100 % sin ninguna
  clave de nube.
- **FR-011**: Un proveedor cloud MUST ser opcional: sin su clave MUST quedar desactivado (no disponible, sin intentos de
  conexión, sin errores al arrancar); con su clave MUST quedar disponible bajo su propio nombre lógico y sujeto a
  presupuesto y registro de coste.
- **FR-012**: Un modelo cloud MUST NOT usarse como principal ni como respaldo salvo que se configure explícitamente.

**Fallback**

- **FR-013**: Para el nombre lógico de chat el gateway MUST poder configurar un modelo principal y un modelo de respaldo
  (ambos locales por defecto) y MUST usar el de respaldo cuando el principal falle, no responda o supere el tiempo máximo
  configurado antes del primer fragmento.
- **FR-014**: Los intentos MUST estar acotados (número de reintentos y tiempo máximo configurables); agotados, el gateway
  MUST devolver un error y el usuario MUST recibir el error tipado de modelo no disponible.
- **FR-015**: Una respuesta servida por el respaldo MUST identificar, en los registros del gateway y en la traza del turno,
  el modelo que realmente respondió y que hubo fallback; el usuario del chat MUST NOT recibir ningún error.
- **FR-016**: Tras restablecerse el modelo principal, las peticiones siguientes MUST volver a usarlo sin intervención
  manual.
- **FR-017**: El fallback MUST ser demostrable en local: MUST existir un procedimiento documentado, reproducible sin claves
  de nube y sin editar código, para provocar la caída del principal y observar el respaldo.

**Presupuesto, claves virtuales y precios**

- **FR-018**: El gateway MUST tener una clave virtual por consumidor (backend, `ingest`) y entorno (local, `dev`), creadas
  desde configuración versionada al arrancar, sin alta manual.
- **FR-019**: Cada clave virtual MUST tener un presupuesto de gasto con un periodo de reinicio, configurables por
  consumidor y entorno, con contadores independientes entre claves.
- **FR-020**: Al agotarse el presupuesto de una clave, el gateway MUST rechazar sus peticiones siguientes hasta el reinicio
  del periodo, sin afectar a otras claves, y MUST registrar el rechazo.
- **FR-021**: Las peticiones con clave inexistente, revocada o ausente MUST rechazarse con error de autenticación.
- **FR-022**: Cada modelo, incluidos los locales, MUST tener un precio configurado (por token de entrada y de salida),
  versionado en git, con el que el gateway calcula el coste y descuenta del presupuesto; un modelo sin precio MUST marcarse
  como «sin precio» y no valer cero ni inventarse un valor.
- **FR-023**: Las claves virtuales, los presupuestos y el gasto MUST persistir entre reinicios del gateway; si se pierde
  el estado, las claves y los presupuestos MUST recrearse desde configuración versionada.

**Coste y tokens en los logs**

- **FR-024**: Por cada petición, el gateway MUST registrar: consumidor, identificador de conversación (o de operación, para
  embeddings), identificador de la petición del chat, modelo pedido, modelo servido, si hubo fallback, tokens de entrada,
  salida y total, coste, latencia y resultado.
- **FR-025**: El backend MUST enviar al gateway, en cada petición, el identificador de la conversación y el de la petición
  del chat, de modo que los logs del gateway, los del backend y las trazas de Langfuse se puedan cruzar.
- **FR-026**: Los logs del gateway MUST NOT contener el texto de preguntas, respuestas ni contexto recuperado, ni claves ni
  cabeceras de autorización; solo metadatos y conteos.
- **FR-027**: MUST existir un procedimiento documentado (comando o filtro) para obtener tokens y coste totales de una
  conversación a partir de los logs del gateway.
- **FR-028**: Un uso de tokens ausente MUST registrarse como «desconocido», nunca como cero.

**Despliegue en docker compose**

- **FR-029**: El gateway MUST arrancar en `infra/docker-compose.yml` junto con el almacén de estado que necesite, con
  imágenes de versión fija sobrescribibles por variables, comprobación de salud, volúmenes con nombre, `restart`, puertos
  configurables y dependencias con condición de salud, siguiendo las convenciones del archivo.
- **FR-030**: Las credenciales y claves locales MUST ser marcadores de posición obvios, válidos solo en local, y ningún
  secreto real MUST estar versionado.
- **FR-031**: Los demás perfiles (`observability`, `llmops`) MUST seguir funcionando sin cambios y convivir con el gateway
  sin conflictos de puertos, nombres ni volúmenes.

**Despliegue en el clúster (DEV)**

- **FR-032**: El gateway y su almacén de estado MUST desplegarse en el ambiente `dev` mediante Argo CD, con manifiestos
  versionados en el repositorio de infraestructura (`shoplab-infra`) y versiones fijas.
- **FR-033**: El backend desplegado en `dev` MUST estar configurado para usar el gateway del clúster, con su clave virtual
  provista por el mecanismo de secretos del ambiente (`ExternalSecret`).
- **FR-034**: Tras un `destroy` y un `apply` del clúster, el gateway MUST quedar funcionando con sus modelos, claves
  virtuales y presupuestos sin pasos manuales; la persistencia del estado MUST documentarse según el almacenamiento
  disponible.
- **FR-035**: Los recursos solicitados por los componentes nuevos MUST ser mínimos y caber en el clúster local de un solo
  nodo junto con el resto de la pila; el gateway y su almacén MUST NOT publicarse fuera de la red interna del clúster.
- **FR-036**: El repositorio de infraestructura MUST validar en CI los manifiestos nuevos con las mismas políticas que ya
  aplica al resto.

**Calidad y documentación**

- **FR-037**: El enrutado, el fallback, el presupuesto y la traducción de errores del backend MUST verificarse con pruebas
  automáticas deterministas que no dependan de un modelo real ni de una cuenta de nube (servidores simulados).
- **FR-038**: El procedimiento (levantar el gateway, nombres lógicos, cambiar de modelo, demostrar el fallback, claves y
  presupuestos, leer el coste por conversación, activar un proveedor cloud y ver el gateway de DEV) MUST estar documentado,
  y el README MUST reflejar los servicios nuevos.
- **FR-039**: La decisión de pasar todo por un gateway (qué contiene, qué se registra, relación con las métricas y las
  trazas existentes, y el efecto sobre ADR-0003) MUST registrarse como ADR en `docs/adr/`.
- **FR-040**: Quedan fuera de esta historia: prompts versionados (LAB-85), puntuaciones de evaluación sobre trazas
  (LAB-29), paneles de coste en Grafana (LAB-65), trazas distribuidas entre servicios (LAB-63), caché de respuestas,
  límites de tasa por usuario final, guardarraíles de contenido y varias réplicas del gateway.

### Key Entities *(include if feature involves data)*

- **Gateway de modelos**: punto único por el que pasan todas las llamadas a modelos; aplica enrutado, fallback,
  presupuesto y registro de coste.
- **Nombre lógico de modelo**: nombre estable que piden los consumidores (por ejemplo, «chat» o «embeddings»); se asocia
  por configuración a uno o más modelos reales.
- **Modelo real**: modelo concreto de un proveedor (Ollama local o proveedor cloud opcional), con su precio por token de
  entrada y de salida.
- **Ruta con fallback**: para un nombre lógico, un modelo principal, uno o más de respaldo, número de reintentos y tiempo
  máximo.
- **Clave virtual**: credencial de un consumidor en un entorno, con su presupuesto, su periodo de reinicio y su gasto
  acumulado.
- **Consumidor**: componente que llama al gateway (backend, `ingest`).
- **Tabla de precios**: precio por token de entrada y de salida para cada modelo real, versionada en git.
- **Registro de petición**: línea de log del gateway con consumidor, conversación, modelo pedido y servido, tokens, coste,
  latencia y resultado.
- **Conversación**: conjunto de turnos del chat al que se atribuyen los tokens y el coste.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de las llamadas a modelos del backend e `ingest` en una ejecución de 20 preguntas y 3 subidas de
  documento aparecen en los registros del gateway, y 0 llegan al servidor de modelos por otra vía.
- **SC-002**: Un desarrollador cambia el modelo de chat editando solo la configuración del gateway y ve la primera
  respuesta con el modelo nuevo en menos de 3 minutos, sin reiniciar el backend.
- **SC-003**: Con el modelo principal inaccesible, el 100 % de las preguntas de una ejecución de 20 se responde completa
  con el modelo de respaldo, sin error visible para el usuario; el procedimiento documentado reproduce el fallback en menos
  de 5 minutos.
- **SC-004**: El tiempo hasta el primer token del chat con el gateway aumenta menos de un 10 % frente a la misma pregunta
  sin él, y las respuestas siguen emitiéndose en streaming.
- **SC-005**: Al superar el presupuesto de una clave, el 100 % de las peticiones siguientes de esa clave se rechazan, y el
  100 % de las de otras claves se siguen atendiendo.
- **SC-006**: Un desarrollador obtiene los tokens y el coste totales de una conversación de tres turnos a partir de los
  logs del gateway en menos de 1 minuto, y el total coincide con la suma de sus turnos.
- **SC-007**: Con el entorno levantado sin ninguna clave de nube, el 100 % de las funciones del chat y de la ingesta
  funcionan, y el repositorio no contiene ninguna clave de proveedor cloud ni imagen con versión `latest` añadidas por esta
  historia.
- **SC-008**: Tras `destroy` y `apply` del clúster, el gateway de DEV vuelve a atender preguntas del chat con sus claves y
  presupuestos sin intervención manual en el 100 % de las pruebas.
- **SC-009**: Ningún log del gateway de la ejecución de prueba contiene texto de preguntas, respuestas o contexto, ni
  claves ni cabeceras de autorización.

## Assumptions

- Esta historia depende de LAB-22 (respuestas aumentadas con los documentos), ya terminada. LAB-64 (trazas con Langfuse)
  también está fusionada: el gateway se integra con sus trazas (modelo servido, fallback) pero no las reemplaza; las
  métricas de Prometheus siguen siendo la fuente de métricas (ADR-0009) y Langfuse la de trazas (ADR-0022).
- Hoy `ingest` solo parsea y trocea documentos y no llama a ningún modelo; el requisito de que `ingest` hable solo con el
  gateway se cumple dándole su clave virtual y su presupuesto y prohibiendo cualquier ruta directa, de modo que cualquier
  llamada a modelos futura ya nazca por el gateway.
- El gateway pertenece al perfil `core` de compose (siempre activo), porque pasa a ser el único camino hacia los modelos:
  sin él el chat no funciona. Necesita su propio almacén de estado, que no mezcla datos con las tablas de la aplicación
  (principio de módulos sin tablas compartidas).
- Los modelos locales por defecto siguen siendo `llama3.2:3b` para chat y `bge-m3` para embeddings (ADR-0003); el modelo de
  respaldo por defecto es otro modelo local más pequeño que se descarga solo si se activa el fallback. «Grande» y
  «pequeño» son relativos al par configurado y no se fijan en la especificación.
- Los precios de los modelos locales son valores de referencia configurados (por ejemplo, equivalentes a un modelo cloud
  comparable) para poder medir y presupuestar; no representan dinero real. Las trazas de LAB-64 conservan su propia tabla
  de precios (los modelos locales cuestan cero allí); unificar ambas queda como decisión del plan.
- Los entornos son dos: local (docker compose) y `dev` (clúster, único ambiente según ADR-0008). Cada uno tiene claves y
  presupuestos propios; el presupuesto se expresa en una unidad monetaria de referencia con un periodo de reinicio por
  defecto diario.
- El identificador de conversación existe en el backend desde LAB-24 (memoria de conversación) y se envía al gateway como
  metadato de la petición; el identificador de la petición del chat es el de LAB-64 (`X-Request-Id`).
- Las conversaciones que pasan por el gateway son datos públicos (Olist) o de prueba; por eso el gateway no guarda el
  contenido, solo metadatos (FR-026).
- Los cambios de infraestructura que esta historia requiera en `infra/` o en `shoplab-infra` forman parte de la misma
  historia y no se difieren.
- Las pruebas automáticas usan servidores simulados del gateway y del servidor de modelos; el proveedor cloud se verifica
  con un servidor simulado compatible, nunca con una cuenta real.
- No hay interfaz de usuario nueva en el frontend; la administración del gateway es por configuración versionada.
