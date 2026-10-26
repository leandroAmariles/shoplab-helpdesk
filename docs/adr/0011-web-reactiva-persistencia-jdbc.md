# ADR-0011: Web reactiva con persistencia JDBC

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

LAB-12 entregó `POST /api/chat` como un flujo SSE de larga duración: cada conversación mantiene una conexión
abierta mientras el modelo genera. El camino del chat ya era reactivo por dentro (`Flux<ChatEvent>`,
`ChatClient.stream()`), pero corría sobre Spring MVC y Tomcat, donde cada flujo abierto reserva un hilo de servlet
(o su hilo asíncrono) mientras dura, y nada impedía que un bloqueo accidental degradara a todos los demás.

La persistencia prevista es JDBC y no se discute aquí: PostgreSQL con pgvector (ADR-0002), el almacén vectorial y la
memoria de conversación de Spring AI, el registro de publicación de eventos de Spring Modulith (ADR-0001) y Flyway
dependen de JDBC. Hoy el backend aún no tiene ningún acceso a datos; llegan con las historias de RAG y de
conversaciones (por ejemplo LAB-14 y LAB-105 se apoyan en esta decisión).

## Decisión

La capa web y el cliente del modelo son reactivos de extremo a extremo; la persistencia sigue siendo JDBC y se aísla.

- **Web**: `spring-boot-starter-webflux` (Netty) sustituye a `spring-boot-starter-webmvc`. `POST /api/chat` sigue
  devolviendo el mismo flujo SSE, byte a byte, a partir de `ChatClient.stream()`. El filtro de correlación es un
  `WebFilter` que propaga el `requestId` por el contexto de Reactor al MDC. Un único `GlobalExceptionHandler`
  reactivo sigue siendo el único traductor de excepciones a HTTP.
- **Llamadas salientes**: solo con `WebClient`. Una prueba de arquitectura (ArchUnit) rechaza `RestTemplate`,
  `RestClient`, `HttpURLConnection` y `java.net.http.HttpClient` en el código de producción.
- **Persistencia JDBC**: toda llamada bloqueante se ejecuta en `Schedulers.boundedElastic()` y solo desde la capa
  `infrastructure`, a través del ayudante `BlockingCalls` (módulo `shared`). Los puertos de aplicación exponen
  `Mono`/`Flux`; el dominio, los casos de uso y los controladores no conocen ni el planificador ni JDBC. Reglas
  ArchUnit lo verifican.
- **Guardia automática**: BlockHound está activo en toda la suite de pruebas y hace fallar cualquier operación
  bloqueante que corra en un hilo del bucle de eventos. Una prueba negativa demuestra que la detección funciona, tanto
  en una cadena aislada como en el servidor en ejecución.

Alternativas descartadas:

- **R2DBC**: obligaría a reemplazar o duplicar justo lo que depende de JDBC. El almacén vectorial de Spring AI para
  pgvector, el registro de eventos de Modulith y las migraciones de Flyway son JDBC; no hay soporte R2DBC oficial
  para ellos, así que habría que escribir y mantener adaptadores propios sobre un driver menos maduro, sin
  que el chat lo necesite: el cuello de botella del chat es el modelo, no la base de datos.
- **MVC con hilos virtuales**: es la alternativa más simple y habría sido válida para la persistencia. Se descarta para
  la capa web del chat porque sigue dedicando un hilo (virtual) por flujo abierto, la cancelación del cliente llega
  como interrupción del hilo en lugar de como señal `cancel` que atraviesa la cadena hasta el modelo, depende de que
  nada fije el hilo portador (`synchronized`, código nativo, drivers) y no ofrece una guardia equivalente a BlockHound.
  Spring AI además entrega el stream del modelo como `Flux`, así que MVC tendría que adaptarlo igualmente. Se puede
  reconsiderar si el coste del modelo reactivo supera su beneficio.
- **Mantener MVC devolviendo `Flux` (estado de LAB-12)**: funcionaba y es honesto decir que MVC soporta tipos
  reactivos. Se descarta porque no cumple el objetivo de no reservar un hilo por conexión de streaming y deja los
  bloqueos accidentales sin detección.

## Consecuencias

- Un flujo de chat abierto ya no ocupa un hilo del servidor: los hilos que atienden peticiones se mantienen acotados
  aunque haya muchas conexiones de streaming simultáneas, y cancelar la conexión cancela la suscripción al modelo.
- Regla de convivencia: **todo acceso bloqueante (JDBC u otro) entra por `BlockingCalls`** desde `infrastructure`. Un
  acceso directo desde otra capa rompe una regla ArchUnit; uno que llegue al bucle de eventos rompe BlockHound en CI.
- Las transacciones de Spring (`@Transactional`) y cualquier `ThreadLocal` no cruzan saltos de hilo: el contexto de
  petición (`requestId`) viaja por el contexto de Reactor y se copia al MDC con la propagación automática de contexto.
  Las transacciones JDBC se abren y cierran dentro de un único bloque ejecutado en `boundedElastic`.
- Cancelar una suscripción interrumpe el hilo de `boundedElastic`, pero JDBC puede ignorar la interrupción: la
  consulta en curso termina y su resultado se descarta.
- El adaptador de Ollama transmite desde `ChatModel.stream()` y no desde `ChatClient.stream()`: el asesor terminal
  del cliente (`ChatModelStreamAdvisor`) republica cada stream en `Schedulers.boundedElastic()`, lo que fija un hilo
  por stream abierto (medido con 50 streams simultáneos: +46 hilos frente a 0) y haría que el número de conversaciones
  concurrentes dependiera del límite de ese planificador. La petición que llega a Ollama es idéntica. Una prueba
  impide reintroducir el salto de hilo. Si más adelante se necesitan asesores (memoria, RAG) se revisará en un ADR
  nuevo, sabiendo que su ejecución cuesta un hilo por stream.
- Como el flujo del chat se emite en el hilo del cliente HTTP de Netty, BlockHound vigila también el caso de uso:
  un `Thread.sleep` metido a propósito en él hace fallar la suite.
- Las pruebas son más verbosas (`WebTestClient`, `StepVerifier`) y exigen `-XX:+AllowRedefinitionToAddDeleteMethods`
  en la JVM de pruebas por BlockHound.
- Las métricas HTTP de LAB-102 se conservan; desaparecen solo las series propias de Tomcat (`tomcat_*`), que ningún
  panel usa.
- Lo que pase de aquí a F4/F5 (extracción a servicios, otros almacenes) se decidirá en un ADR nuevo que, si procede,
  sustituya a este.
