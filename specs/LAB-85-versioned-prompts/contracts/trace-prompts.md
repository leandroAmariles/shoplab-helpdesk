# Contrato: prompts en la traza de cada turno (delta de LAB-64)

Se añade a [`specs/LAB-64-langfuse-llm-traces/contracts/trace-schema.md`](../../LAB-64-langfuse-llm-traces/contracts/trace-schema.md);
no cambia nada de lo que allí se define.

## Atributos nuevos

| Dónde | Atributo | Valor | Cuándo |
|---|---|---|---|
| Generación | `langfuse.observation.prompt.name` | nombre del prompt principal (`chat-system` o `chat-small-talk`) | solo si su origen es Langfuse |
| Generación | `langfuse.observation.prompt.version` | versión (entero) del prompt principal | solo si su origen es Langfuse |
| Generación | `langfuse.observation.metadata.prompt.<nombre>` | `<versión>` o `default` | por cada prompt que usó el turno |
| Traza (span raíz) | `langfuse.trace.metadata.prompt_<nombre con _>` | `<versión>` o `default` | por cada prompt que usó el turno |
| Traza (span raíz) | `langfuse.trace.metadata.prompt_override` | `true` | solo si la petición fijó versiones |

`<nombre con _>` es el nombre con los guiones cambiados por guion bajo: `prompt_chat_system`, `prompt_chat_context`…

## Reglas

- Una generación enlaza **un** prompt (limitación de Langfuse). En un turno de conocimiento es `chat-system`; en una cortesía,
  `chat-small-talk`. Los demás van solo como metadatos.
- Con el valor por defecto **no** se escribe el enlace nativo (Langfuse no crea enlace con un prompt de reemplazo) y el metadato
  vale `default`: es la señal inequívoca de FR-014.
- Solo aparecen los prompts que el turno usó: una cortesía no lleva `chat-context` ni `chat-empty-context`; un turno de
  conocimiento con fragmentos no lleva `chat-empty-context`; un turno que responde con el mensaje fijo (sin llamar al modelo) no
  lleva ningún prompt.
- La versión registrada es la que el turno **leyó al empezar** (instantánea), aunque la etiqueta se mueva mientras dura.
- Registrar los prompts no puede retrasar ni romper el turno: sigue la regla de LAB-64 (todo con `safely`, sin contenido en los
  avisos).

## Verificación

`quickstart.md` §4: tras un turno de conocimiento y una cortesía, la traza muestra el enlace al prompt con su versión, y la
lista de trazas se filtra por `prompt_chat_system`.
