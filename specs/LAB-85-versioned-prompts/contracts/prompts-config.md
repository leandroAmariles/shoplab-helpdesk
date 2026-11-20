# Contrato: configuración de los prompts

## Propiedades nuevas (`shoplab.chat.prompts.*`)

| Propiedad | Variable de entorno | Por omisión | Qué hace |
|---|---|---|---|
| `enabled` | `SHOPLAB_CHAT_PROMPTS_ENABLED` | `false` | Carga los prompts de Langfuse. Apagado: se usan siempre los valores por defecto y no se crea ningún cliente ni hilo |
| `base-url` | `SHOPLAB_CHAT_PROMPTS_BASE_URL` | `http://localhost:3001` | Dirección de Langfuse (sin `/api/...`) |
| `public-key` | `SHOPLAB_CHAT_PROMPTS_PUBLIC_KEY`, y si falta `SHOPLAB_CHAT_TRACING_PUBLIC_KEY` | vacío | Clave pública del proyecto |
| `secret-key` | `SHOPLAB_CHAT_PROMPTS_SECRET_KEY`, y si falta `SHOPLAB_CHAT_TRACING_SECRET_KEY` | vacío | Clave secreta; nunca se imprime |
| `label` | `SHOPLAB_CHAT_PROMPTS_LABEL` | `production` | Etiqueta que se pide |
| `refresh-interval` | `SHOPLAB_CHAT_PROMPTS_REFRESH_INTERVAL` | `60s` | Cada cuánto se vuelve a preguntar. Es el tiempo máximo en que un cambio de etiqueta llega al chat (≥ 5 s) |
| `request-timeout` | `SHOPLAB_CHAT_PROMPTS_REQUEST_TIMEOUT` | `2s` | Tiempo máximo de cada petición a Langfuse, incluida la carga de arranque |
| `max-prompt-characters` | `SHOPLAB_CHAT_PROMPTS_MAX_PROMPT_CHARACTERS` | `20000` | Tope de tamaño de un prompt; mayor se rechaza |
| `publish-missing` | `SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING` | `false` | Al arrancar, crea en Langfuse los prompts que no existen con su valor por defecto y la etiqueta `production` |
| `override-token` | `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` | vacío | Token que permite a la evaluación fijar versiones. Vacío = capacidad apagada; también lo está con `enabled=false` |

Reglas de validación al arrancar (con `enabled=true`): `base-url` válida; `refresh-interval` ≥ 5 s; `request-timeout` entre 100 ms y
30 s; `max-prompt-characters` ≥ 100; `label` no vacía. Faltan las claves: aviso y se comporta como apagado (igual que las trazas).
`toString` muestra `keys=set|missing` y `override-token=set|unset`, nunca los valores.

## Propiedades retiradas

| Retirada | Variable | Dónde está ahora |
|---|---|---|
| `shoplab.chat.system-prompt` | `SHOPLAB_CHAT_SYSTEM_PROMPT` | `chat-system` en Langfuse; valor por defecto en `prompts/chat-system.txt` |
| `shoplab.chat.small-talk.system-prompt` | `SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT` | `chat-small-talk`; `prompts/chat-small-talk.txt` |
| `shoplab.chat.rag.context-template` | (sin variable) | `chat-context`; `prompts/chat-context.txt` |
| `shoplab.chat.rag.empty-context-template` | (sin variable) | `chat-empty-context`; `prompts/chat-empty-context.txt` |

Si el entorno todavía define una de las dos variables retiradas, el backend arranca y deja un aviso de migración (una vez) que
nombra la variable y dice dónde se edita ahora el prompt. No se usa su valor.

## Se quedan donde están

`shoplab.chat.small-talk.topics` y las cinco listas de cortesías, `rag.no-context-message`, `rag.no-context-mode`, `top-k`,
umbrales y límites: son datos de configuración, no prompts.

## Valores locales sugeridos (backend en el host, perfil `llmops` activo)

```bash
SHOPLAB_CHAT_PROMPTS_ENABLED=true
SHOPLAB_CHAT_PROMPTS_BASE_URL=http://localhost:3001
SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true          # solo la primera vez, o siempre en local
SHOPLAB_CHAT_PROMPTS_REFRESH_INTERVAL=10s          # para ver un cambio de etiqueta casi al instante
SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN=local-eval-token   # marcador de posición, solo para local
# las claves se toman de SHOPLAB_CHAT_TRACING_PUBLIC_KEY / SHOPLAB_CHAT_TRACING_SECRET_KEY
```

## DEV (`../shoplab-infra/k8s/overlays/dev/kustomization.yaml`)

```yaml
  - SHOPLAB_CHAT_PROMPTS_ENABLED=true
  - SHOPLAB_CHAT_PROMPTS_BASE_URL=http://langfuse-web:3000
  - SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true
```

DEV **no lleva** `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` (R13): no admite fijar versiones de prompts. Las claves son las de las trazas.
