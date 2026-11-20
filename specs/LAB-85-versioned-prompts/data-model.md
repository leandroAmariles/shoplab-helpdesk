# Modelo de datos: Prompts versionados en Langfuse

No hay tablas nuevas ni migraciones. Todo el estado del backend está en memoria; el estado persistente (las versiones y las
etiquetas) es de Langfuse.

## Backend (módulo `chat`)

### `PromptName` (enum, `domain/model/enums`)

| Valor | Nombre en Langfuse | Marcadores obligatorios | Recurso por defecto | Cuándo se usa |
|---|---|---|---|---|
| `CHAT_SYSTEM` | `chat-system` | ninguno | `prompts/chat-system.txt` | todo turno de conocimiento |
| `SMALL_TALK` | `chat-small-talk` | `{topics}` | `prompts/chat-small-talk.txt` | turno de cortesía |
| `CONTEXT` | `chat-context` | `{query}`, `{context}`; debe poder renderizarse | `prompts/chat-context.txt` | turno de conocimiento con fragmentos |
| `EMPTY_CONTEXT` | `chat-empty-context` | `{query}` | `prompts/chat-empty-context.txt` | turno de conocimiento sin fragmentos y `no-context-mode=model` |

El **prompt principal** del turno (el que se enlaza con el atributo nativo de Langfuse) es `CHAT_SYSTEM` en un turno de
conocimiento y `SMALL_TALK` en una cortesía.

### `PromptSource` (enum)

`LANGFUSE` (versión cargada de Langfuse) o `DEFAULT` (valor por defecto del repositorio).

### `PromptReference` (record)

| Campo | Tipo | Regla |
|---|---|---|
| `name` | `PromptName` | obligatorio |
| `version` | `Integer` | número ≥ 1 si `source = LANGFUSE`; `null` si `DEFAULT` |
| `source` | `PromptSource` | obligatorio |

Su forma textual para las trazas es `<versión>` o `default`.

### `ResolvedPrompt` (record)

`text` (no vacío) + `reference`. Es lo que el adaptador del gateway usa para construir el mensaje.

### `PromptOverrides` (record)

Mapa `PromptName → versión (≥ 1)`; vacío por omisión (`PromptOverrides.NONE`). Viaja en `StreamChatCommand` y, de allí, en el
contexto de Reactor del turno (`PromptConstants.OVERRIDES_CONTEXT_KEY`).

### Estado de un prompt en la caché (infraestructura, no es un tipo público)

```text
        carga inicial / refresco válido                  refresco válido y distinto
VACÍO ─────────────────────────────────▶ CARGADO(v, texto) ─────────────────────────▶ CARGADO(v', texto')
  │                                          │  ▲
  │ sin carga válida                         │  │ refresco inválido, ausente o fallido:
  ▼                                          │  │ se conserva CARGADO(v, texto) y se avisa
 se sirve el valor por defecto               └──┘
```

- `VACÍO` no desaparece al fallar: cada intervalo se reintenta.
- `CARGADO` nunca vuelve a `VACÍO` (la última versión válida se conserva aunque Langfuse deje de responder o la etiqueta se
  retire).
- Cambiar de `CARGADO(v)` a `CARGADO(v')` es una referencia atómica: un turno que ya leyó `v` termina con `v` (FR-012).
- La caché de **versiones fijadas** (evaluación) es aparte: clave `(nombre, versión)`, a lo sumo 32 entradas, sin caducidad
  porque una versión no cambia.

### Reglas de validación (`PromptValidator`)

Orden de comprobación; la primera que falla da el **motivo** que va al aviso y a la métrica:

| # | Regla | Motivo |
|---|---|---|
| 1 | `type` de Langfuse es `text` | `not-text` |
| 2 | texto no vacío ni en blanco | `blank` |
| 3 | longitud ≤ `max-prompt-characters` | `too-long` |
| 4 | contiene los marcadores obligatorios de su `PromptName` | `missing-placeholder` |
| 5 | (solo `CONTEXT`) se renderiza con `query` y `context` (con `PromptRenderer`) | `not-renderable` |

El valor por defecto pasa por las mismas reglas al arrancar; si no las cumple, el arranque falla (error de programación).

### Excepciones nuevas (`domain/exception`)

| Excepción | Cuándo | HTTP | Código |
|---|---|---|---|
| `PromptOverrideForbiddenException` | cabecera de versiones sin token configurado o con token distinto | 403 | `ResponseCodeEnum` (nuevo) |
| `PromptOverrideException` | cabecera mal formada (400, antes del stream); o versión inexistente/inválida durante el turno (evento `error`) | 400 / evento | `ResponseCodeEnum` (nuevo) |

## Evaluación (`eval/`, fase B)

### `PromptSelection`

Lista ordenada de `(name, requested, resolved_version)`: `requested` es lo escrito (`3` o `staging`) y `resolved_version` el
número al que apuntaba al empezar. Vacía si no se fijó nada (se usa `production`).

### `PromptComparison`

`rows` (una por métrica del ámbito `all`: `metric`, `mean_a`, `n_a`, `mean_b`, `n_b`, `delta`), `differing_prompts` (nombres cuyo
valor de `prompt_versions` difiere) y `warnings` (lista de motivos de incomparabilidad). `delta` queda vacío si falta una de las
dos medias.

### Cambio en el CSV de LAB-29

`quality-summary.csv` añade **al final** la columna `prompt_versions` (ver [contracts/eval-compare.md](contracts/eval-compare.md)).
