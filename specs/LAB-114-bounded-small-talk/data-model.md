# Data Model: Conversación natural acotada

No hay tablas ni migraciones nuevas. Este documento describe los tipos, la configuración y las categorías del dataset que
introduce la historia. Los nombres de campos y valores son los que usará el código.

## Tipos del módulo `chat`

### `TurnKind` (enum, `domain/model/enums`)

| Valor | `tagValue()` | Cuándo |
|---|---|---|
| `SMALL_TALK` | `small_talk` | El mensaje se clasificó como cortesía y se atiende por la vía de charla |
| `WITH_CONTEXT` | `with_context` | Pregunta con al menos un fragmento seleccionado |
| `NO_CONTEXT` | `no_context` | Pregunta sin fragmentos útiles (mensaje fijo o modo `model`) |

Etiqueta del contador `shoplab.chat.turns`. Un turno cuya recuperación falla no tiene tipo (lo cuenta
`shoplab.chat.context.retrieval{outcome=failed}`).

### `TurnResult` (enum existente, `domain/model/enums`)

Se añade `SMALL_TALK` (`traceValue()` = `small_talk`): resultado de la traza de un turno de charla completado.

### `SmallTalkConstants` (constantes, `domain/model/constants`)

| Constante | Contenido |
|---|---|
| `GREETINGS`, `THANKS`, `FAREWELLS`, `ACKNOWLEDGMENTS`, `CAPABILITIES` | Listas por defecto del léxico: conjuntos inmutables de frases ya normalizadas (sin acentos, minúsculas, sin signos). `SmallTalkProperties` las usa cuando la lista correspondiente no está configurada; una lista configurada las reemplaza |
| `MAX_MESSAGE_CHARACTERS` = 80 | Más largo, nunca es cortesía |
| `MAX_TOKENS` = 10 | Más tokens, nunca es cortesía (también el máximo de palabras de una frase configurada) |

El tope de la segmentación (palabras de la frase más larga) ya no es una constante: `SmallTalkClassifier` lo calcula del léxico
en uso (`maxPhraseTokens()`), que ahora puede venir de la configuración. `ChatConstants.DEFAULT_SYSTEM_PROMPT` desapareció: su
texto está en `application.yml`.
| `DEFAULT_SYSTEM_PROMPT` | Prompt de alcance de la charla, con el marcador `{topics}` |
| `DEFAULT_TOPICS` | `pagos, privacidad, devoluciones, envíos y garantías` |
| `TOPICS_PLACEHOLDER` | `{topics}` |

`ChatConstants` añade `TURNS_METRIC = "shoplab.chat.turns"` y `KIND_TAG = "kind"`; las reglas 7 y 8 del prompt de las
respuestas con contexto están en el texto por defecto de `shoplab.chat.system-prompt` (`application.yml`).

### `SmallTalkClassifier` (`application/usecase`, sin estado)

| Entrada | Salida | Reglas |
|---|---|---|
| `String message` | `boolean isSmallTalk(message)` | Normaliza; falso si es nulo/vacío, supera los límites o contiene un token fuera del léxico, un dígito o ninguna frase; verdadero si el léxico cubre todos los tokens |

Transformaciones de normalización, en orden: `Normalizer` NFD + eliminar marcas diacríticas → minúsculas (`Locale.ROOT`) →
sustituir todo carácter que no sea letra, dígito o espacio por espacio (esto quita signos y emojis) → reducir 3 o más
repeticiones de una letra a una → colapsar espacios y recortar.

### `SmallTalk` (`application/usecase`)

| Operación | Descripción |
|---|---|
| `boolean handles(String message)` | `properties.enabled() && classifier.isSmallTalk(message)` |
| `boolean isCourtesy(String question)` | `properties.enabled() && classifier.isSmallTalk(question)`: con la vía desactivada ninguna pregunta se filtra de la búsqueda; la usa `ConversationHistory` |
| `Flux<String> reply(String message)` | Delegada en `SmallTalkModelPort.streamReply` |

### `SmallTalkModelPort` (`application/port/out`)

`Flux<String> streamReply(String message)`: fragmentos de la respuesta de charla en orden de generación; el mensaje es el
que el clasificador aceptó; cancelar la suscripción detiene al modelo; cualquier fallo es una excepción tipada del módulo
(`ChatModelException`), igual que `ChatModelPort`.

### `SmallTalkProperties` (`config`, prefijo `shoplab.chat.small-talk`)

| Campo | Tipo | Defecto | Validación |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | — |
| `systemPrompt` | `String` | `SmallTalkConstants.DEFAULT_SYSTEM_PROMPT` si está vacío | Debe contener `{topics}` |
| `topics` | `String` | `SmallTalkConstants.DEFAULT_TOPICS` si está vacío | No vacío tras recortar |

Un valor no válido hace fallar el arranque con un mensaje que nombra la propiedad (patrón de `RagProperties`).

### `PromptData` (`infrastructure/out/gateway`, estático y sin estado)

| Operación | Entrada → salida | Garantías |
|---|---|---|
| `neutralize(String)` | texto no confiable → mismo texto con las secuencias de frontera en forma inerte | Idempotente; no cambia el resto del texto; `null` → cadena vacía |
| `wrap(String)` | texto del cliente → `<mensaje_del_cliente>\n…\n</mensaje_del_cliente>` con el contenido neutralizado | Una sola pareja de etiquetas, siempre bien cerrada |

Secuencias que neutraliza: etiquetas `mensaje_del_cliente`, `contexto`, `historial`, `sistema`, `system`, `assistant`, `user`,
`human` (abiertas o cerradas, con espacios y sin distinguir mayúsculas); `<|…|>`; `[INST]` y `[/INST]`; `<<SYS>>` y
`<</SYS>>`; líneas de 5 o más guiones (se reducen a `---`).

### Dónde se aplica en la petición al modelo

| Parte de la petición | Rol del mensaje | Tratamiento |
|---|---|---|
| Prompt de sistema (RAG) o de charla | `system` | Texto de configuración; sin datos del cliente |
| Pregunta anterior del historial | `user` | `wrap` |
| Respuesta anterior del asistente | `assistant` | `neutralize` |
| Mensaje actual con contexto | `user` | `{query}` ← `wrap(mensaje)`; cada fragmento con `neutralize(texto)` bajo su encabezado `[n] Título › sección` |
| Mensaje actual sin contexto (modo `model`) | `user` | `{query}` ← `wrap(mensaje)` |
| Mensaje de charla | `user` | `wrap(mensaje)` (ya es vocabulario cerrado) |

## Dataset golden (`eval/golden.jsonl`)

Esquema sin cambios (`id`, `question`, `reference_answer`, `expected_documents`, `category`). Cambia el conjunto de categorías:

| `category` | Tipo (`GoldenCase.kind`) | `expected_documents` | Mínimo | Se mide en recuperación |
|---|---|---|---|---|
| `payments`, `privacy`, `returns`, `shipping`, `warranty` | `answered` (o `unanswered_topic` si va vacío) | 1-2 | Reglas de LAB-27 | Sí, si tiene documentos |
| `off_topic` | `off_topic` | vacío | Reglas de LAB-27 | No |
| `small_talk` (nueva) | `small_talk` | vacío | 10 (se entregan 12) | No (sin búsqueda por diseño) |
| `injection` (nueva) | `injection` | vacío | 10 (se entregan 12) | No |

Casos mixtos: sin categoría propia; son casos `answered` de su tema con una cortesía al inicio de la pregunta (6 en total).

Relaciones y reglas del conjunto:

- Las reglas de conjunto de LAB-27 operan sobre el subconjunto de conocimiento (temas + `off_topic`): 90 + 6 = 96, dentro de
  80-100.
- Los casos de conversación no cuentan como «sin respuesta» (`G-UNANSWERED*`) ni como «excluidos» de la recuperación.
- Longitud de pregunta: 10-300 para conocimiento, 2-300 para conversación. Longitud de respuesta de referencia: 20-800 para
  todos. Respuestas sin cifras para todo caso sin documentos (`G-NOANSWER-DIGITS`).

## Transiciones de estado

Ninguna entidad persistente cambia de estado. El flujo de un mensaje:

```text
mensaje ──validar──▶ clasificar ──cortesía y vía activa──▶ charla ──▶ token… ──▶ guardar turno ──▶ done
                         │
                         └──si no──▶ cargar historial ──▶ recuperar ──┬─ con contexto ──▶ sources + token… ──▶ guardar ──▶ done
                                                                       ├─ sin contexto (fixed-message) ──▶ mensaje fijo ──▶ guardar ──▶ done
                                                                       └─ fallo de recuperación ──▶ error tipado
```
