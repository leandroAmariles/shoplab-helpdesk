# ADR-0027: Prompts versionados en Langfuse

- Estado: aceptado
- Fecha: 2026-11-19
- Complementa a: ADR-0022 (trazas de LLM con Langfuse por OTLP) y a ADR-0025 (conversación de cortesía acotada); no los sustituye

## Contexto

LAB-85 pide gestionar los prompts fuera del código para cambiarlos, compararlos y revertirlos sin redesplegar. Hasta ahora los
cuatro textos que el chat envía al modelo (prompt de sistema, prompt de cortesía y las dos plantillas del mensaje del cliente,
con y sin contexto) eran configuración empaquetada con el backend: cada ajuste exigía cambiar archivos, compilar y desplegar.

- Langfuse ya está en el entorno (ADR-0022) con las claves del proyecto, y guarda versiones numeradas e inmutables de un prompt
  con etiquetas movibles, comparación de versiones y enlace desde la traza.
- El chat no puede depender de Langfuse para responder: es una herramienta de observabilidad y LAB-64 ya descartó que lo sea
  para las trazas.
- La Constitución (V) rechaza un cambio de prompt sin resultado de evaluación; hace falta poder medir dos versiones sin mover
  la que ven los clientes.
- Esta historia no cambia el texto de ningún prompt: la versión 1 de cada uno es el valor actual.

## Decisión

1. **Langfuse es la fuente de verdad de los prompts.** El backend pide la versión con la etiqueta `production` de
   `chat-system`, `chat-small-talk`, `chat-context` y `chat-empty-context` con la API REST de prompts (`WebClient`, claves del
   proyecto), sin añadir ninguna dependencia. El texto por defecto vive en `backend/src/main/resources/prompts/*.txt`, que es la
   única copia del texto en el repositorio, y las cuatro propiedades y variables que lo llevaban se retiran.
2. **El turno solo lee memoria.** Una caché con una referencia atómica por prompt guarda la última versión **válida**; un
   refresco en segundo plano (60 s por omisión) la reemplaza solo si lo recibido es válido y distinto. La entrada nunca
   caduca: con Langfuse caído, una etiqueta retirada o una edición mala se sigue sirviendo la última versión válida y, si no hay
   ninguna, el texto por defecto. Un cambio de etiqueta llega a todas las instancias en un intervalo, sin coordinarlas. La carga
   de arranque es acotada (un `request-timeout`), de modo que con Langfuse sano el primer turno ya usa la versión etiquetada.
3. **Una versión se valida antes de usarse.** Debe ser de tipo texto, no estar en blanco, no superar un tope (20 000
   caracteres), llevar los marcadores que el sistema rellena (`{topics}`, `{query}`, `{context}`) y, en la plantilla de contexto,
   poder renderizarse sin variables desconocidas. Un `PromptRenderer` propio, sin dependencias de Spring, lo comparten el
   validador y el adaptador, así que lo que se acepta es exactamente lo que se puede renderizar. El aviso nombra el prompt, la
   versión y el motivo, nunca el texto.
4. **Cada turno fija sus versiones al empezar** (instantánea) y las usa hasta terminar, también en la traza.
5. **La traza dice qué prompt se usó.** La generación se enlaza con el prompt principal (`chat-system` o `chat-small-talk`) con
   los atributos nativos de Langfuse, que solo admite un enlace por generación; los cuatro se registran además como metadatos
   filtrables (`prompt_<nombre>`). Con el texto por defecto no hay enlace y el metadato dice `default`.
6. **Fijar versiones solo para evaluar.** Una petición puede llevar `X-Prompt-Versions` y `X-Prompt-Token`. La capacidad solo
   existe si los prompts están gestionados y hay un token configurado; sin ella, o con otro token, la respuesta es 403 y nunca se
   ignora la cabecera: una evaluación mal configurada mediría con los prompts equivocados. Una versión que no existe es un 422
   y no se cae al valor por defecto. La selección vive en el contexto de esa petición: no mueve etiquetas ni afecta a otras.
7. **Publicación inicial por el propio backend.** Con `publish-missing=true` crea, al arrancar, los prompts que no existen con su
   texto por defecto y la etiqueta `production`. Nunca crea una versión de un prompt que existe ni mueve una etiqueta. La API
   responde 404 tanto para un prompt como para una etiqueta inexistente, así que se comprueba con la etiqueta `latest`, que
   Langfuse pone en la última versión de todo prompt.
8. **Evaluar y comparar.** `quality --prompt` fija versiones (un número o una etiqueta, que se resuelve a su versión antes de
   empezar y es la que queda registrada), y `compare` lee dos resúmenes y muestra medias, diferencia y prompts que difieren, con
   una advertencia si los resultados no son comparables. El resumen gana la columna `prompt_versions`.

## Alternativas descartadas

- **Un SDK de Langfuse en el backend**: no hay uno de Java para prompts, y añadiría una dependencia para dos llamadas.
- **Consultar a Langfuse en el turno con una vigencia (TTL)**: el primer turno tras vencer pagaría la latencia o el tope de
  espera, y con varias instancias habría ráfagas de consultas. La lectura de memoria con refresco aparte no espera nunca.
- **Conservar las propiedades como texto por defecto sobrescribible**: obliga a mantener el texto en `application.yml` y a decidir
  quién gana frente a Langfuse. Dos fuentes de verdad.
- **Mover `production` durante una evaluación** o **una instancia aparte por versión**: lo primero afecta a todos los clientes y
  lo segundo exige un reinicio por cada comparación.
- **Un servicio de prompts propio o una pantalla de edición dentro de ShopLab**: se usa la interfaz de Langfuse, que ya compara
  versiones.
- **Crear desde el repositorio una versión nueva cuando el texto por defecto cambia**: pisa ediciones hechas en Langfuse.

## Consecuencias

- El chat deja de depender de Langfuse para tener prompt, y un cambio de etiqueta cambia las respuestas en un intervalo, sin
  reiniciar. Revertir es mover la etiqueta de vuelta.
- Cambio de configuración **incompatible**: `SHOPLAB_CHAT_SYSTEM_PROMPT` y `SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT`, y las cuatro
  propiedades de texto, ya no se leen; el backend avisa una vez si el entorno aún define una de las variables.
- Un cambio de etiqueta tarda como máximo `refresh-interval` en llegar a cada instancia; es el coste de que el turno no espere.
- Hay una superficie nueva en `POST /api/chat` (las dos cabeceras), apagada por omisión y protegida por token. Con varias réplicas
  arrancando a la vez sobre un Langfuse vacío podrían crear dos versiones idénticas de un prompt (inocuo; DEV tiene una réplica).
- Los resultados de evaluación son comparables entre versiones de prompt, y el resumen registra qué versión se midió.
- Quedan fuera: la evaluación en CI con umbrales de bloqueo (LAB-30), la persistencia de ejecuciones (LAB-31) y las pruebas A/B con
  tráfico real.
