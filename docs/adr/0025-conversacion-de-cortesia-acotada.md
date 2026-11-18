# ADR-0025: Conversación de cortesía acotada

- Estado: aceptado
- Fecha: 2026-11-18
- Complementa a: ADR-0018 (recuperación del contexto en el chat) y a la decisión D-9 de LAB-22 (la defensa de prompt es una mitigación, no una garantía); no los sustituye

## Contexto

LAB-114 pide que el chat responda con naturalidad a saludos y cortesías aunque no haya contexto, sin perder las defensas
frente a la inyección de instrucciones. Antes de esta decisión, con el modo por defecto (`no-context-mode=fixed-message`),
un «hola» no recuperaba ningún fragmento y recibía el mensaje fijo de «no encontré información»; con `model`, el modelo
contestaba a **cualquier** cosa sin contexto, no solo a cortesías.

- La garantía de LAB-22 (FR-006: nunca afirmar datos de la tienda sin contexto) debe seguir intacta.
- Un clasificador basado en un modelo sería un segundo componente al que el cliente le habla: más latencia, más coste y una
  superficie nueva de inyección.
- El mensaje del cliente, el historial y los fragmentos recuperados ya llegaban al modelo, pero sin delimitar, y el prompt de
  sistema no prohibía revelar sus instrucciones.
- La evaluación automática de la calidad de las respuestas aún no existe (LAB-29 y LAB-30).

## Decisión

1. **Una vía de charla decidida antes de recuperar.** `StreamChatUseCase` pregunta a `SmallTalk` si el mensaje es solo una
   cortesía, antes de leer el historial y antes de buscar. Si lo es, responde sin búsqueda, sin historial, sin herramientas y
   sin evento `sources`; el contrato SSE no cambia. Cualquier otro mensaje (pregunta, mixto, ambiguo) sigue el flujo de
   LAB-22/23/24 sin cambios.
2. **El clasificador es determinista y de léxico cerrado.** `SmallTalkClassifier` normaliza el mensaje (minúsculas, sin
   acentos, sin signos ni emojis, letras repetidas reducidas) y exige que **todo** él se descomponga en frases de un
   vocabulario cerrado (saludos, agradecimientos, despedidas, acuses, «¿qué puedes hacer?», en español e inglés), con un
   máximo de 80 caracteres y 10 palabras. Ante la duda, el mensaje es una pregunta. Las cinco listas por defecto están en
   `SmallTalkConstants` y cada una se puede **reemplazar por configuración**
   (`shoplab.chat.small-talk.greetings|thanks|farewells|acknowledgments|capabilities`, separadas por comas); el arranque falla
   si una frase queda vacía, lleva cifras o tiene más de 10 palabras.
3. **La respuesta de charla la genera el modelo con un prompt propio y mínimo**, a través de un puerto nuevo
   (`SmallTalkModelPort`, implementado por `GatewayChatAdapter`): rol, temas en general, ningún dato de la tienda, y que el
   mensaje del cliente son datos. Sin historial, sin fragmentos y sin herramientas. `ChatModelPort` no cambia.
4. **El delimitado se hace en código.** `PromptData` entrega el mensaje del cliente (y las preguntas del historial) entre
   `<mensaje_del_cliente>` y `</mensaje_del_cliente>`, y neutraliza en esos textos, en las respuestas anteriores y en los
   fragmentos todo lo que imite un delimitador o un rol del chat (etiquetas, `<|…|>`, `[INST]`, `<<SYS>>`, líneas de guiones).
   Un operador que cambie `context-template` o `system-prompt` no lo quita. El prompt de sistema gana dos reglas: no revelar
   instrucciones ni configuración, y tratar el mensaje delimitado como datos.
5. **Las cortesías no contaminan la búsqueda.** `ConversationHistory.searchText` ignora los turnos de cortesía al construir
   la consulta (se reclasifica la pregunta guardada; sin migración). El historial que ve el modelo los conserva.
6. **Se mide por tipo de turno**: `shoplab.chat.turns{kind=small_talk|with_context|no_context}`, más una marca en la traza.
   Una recuperación fallida no tiene tipo, porque ya la cuenta `shoplab.chat.context.retrieval{outcome=failed}`.

Alternativas descartadas:

- **Que un modelo clasifique** («¿es esto una cortesía?»): añade una llamada, latencia y una superficie de inyección.
- **Similitud de embeddings con ejemplos de cortesía**: necesita el servicio de embeddings, justo lo que la charla debe
  evitar, y un umbral difícil de hacer reproducible.
- **Detectar por prefijo** («empieza por hola»): clasificaría «hola, ¿cuánto tarda un envío?» como charla.
- **Plantillas fijas por categoría e idioma**: deterministas pero poco naturales y obligan a resolver combinaciones e idioma;
  quedan como plan B tras el mismo puerto si el modelo local responde fuera de alcance.
- **Escapar todos los `<` y `>`**: altera texto legítimo de documentos y preguntas y degrada las citas.
- **Delimitar en las plantillas de configuración**: el operador puede romperlo.

## Consecuencias

- Un «hola» deja de recibir el mensaje fijo y el modo `model` ya no es necesario para que el bot salude. Con
  `SHOPLAB_CHAT_SMALL_TALK_ENABLED=false` el comportamiento es exactamente el anterior.
- La charla funciona con la recuperación o la memoria caídas, porque no las usa (el guardado del turno sí sigue la política
  de LAB-24).
- **El delimitado y la neutralización son una mitigación, no una garantía.** Una secuencia no prevista puede pasar. Las
  pruebas automáticas verifican lo que controla el sistema (clasificación, delimitado, prompt de charla sin datos de la
  tienda); que el modelo real no revele sus instrucciones se comprueba a mano (`validation.md`). Los guardrails formales, la
  detección de salida y la limitación de ritmo son de LAB-66, que ampliará esta decisión.
- El léxico cerrado deja fuera cortesías legítimas poco comunes («holis», «grasias»): el coste es un mensaje fijo, no una
  respuesta inventada. Ampliarlo ya no exige tocar el código, pero **quien lo amplía asume el riesgo**: todo lo que figure en
  las listas se salta la búsqueda y la respuesta fundamentada, y `GoldenDatasetClassifierTests` solo comprueba las listas por
  defecto contra el dataset (una lista propia debe revisarse con los casos `small_talk`, `injection` y mixtos del golden).
  Nunca deben incluirse «sí», «no» ni nada que lleve una pregunta. (Esta decisión sustituye a la del primer borrador, que dejaba
  el léxico solo en el código para que cada ampliación pasara por revisión.)
- **El texto por defecto del prompt de sistema vive en `application.yml`** (`shoplab.chat.system-prompt`), no en una constante
  de Java, y se reemplaza con `SHOPLAB_CHAT_SYSTEM_PROMPT`. Ya no hay valor de reserva en el código: un prompt en blanco hace
  fallar el arranque. Un prompt propio no hereda ninguna de las reglas del de por defecto (incluidas la 7 y la 8).
- El cambio de prompt de sistema (reglas 7 y 8) toca la generación y no hay aún evaluación automática de respuestas
  (principio V de la Constitución): hasta LAB-29 y LAB-30 se compara la recuperación (LAB-28) antes y después y se valida a mano.
- Una charla no registra `shoplab.chat.first.token`, igual que el mensaje fijo: ese temporizador mide respuestas con
  recuperación; su duración queda en `shoplab.chat.response`.
- Las pruebas que usaban «Hola» como pregunta cualquiera usan ahora una pregunta neutra.
- El dataset golden crece con casos de charla, de inyección y mixtos (LAB-27), y una prueba del backend impide que el
  clasificador y el dataset se contradigan.

## Cómo cambiar una decisión

Un ADR aceptado no se edita ni se borra (salvo erratas). Para cambiarlo:

1. Crear un ADR nuevo con la línea `- Sustituye a: ADR-YYYY`.
2. En el ADR original, cambiar solo la línea de estado a `sustituido por ADR-XXXX`.
3. Actualizar el estado en el índice.
