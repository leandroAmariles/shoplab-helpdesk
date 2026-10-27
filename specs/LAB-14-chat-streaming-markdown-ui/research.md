# Research: Pantalla de chat con streaming y Markdown

Verificado el 2026-10-26 con `npm view` y con el repositorio (`frontend/`, `specs/LAB-12-*`, `specs/LAB-13-*`).

## R1. Cómo consumir un SSE con `POST`

- **Decision**: `fetch` con `method: 'POST'`, `Accept: text/event-stream` y `signal` de un `AbortController`; se lee
  `response.body.getReader()` y se decodifica con un `TextDecoder` reutilizado en modo `stream: true`.
- **Rationale**: `EventSource` solo admite `GET` (lo advierte el contrato de LAB-12). `HttpClient` no entrega el
  cuerpo de forma incremental de manera fiable para este caso. `fetch` es nativo, cancelable y no añade dependencias.
  `stream: true` evita cortar un carácter multibyte entre dos bloques (FR del spec: tildes y emojis íntegros).
- **Alternatives**: `@microsoft/fetch-event-source` (resuelve lo mismo con una dependencia más y ya sin mantenimiento
  activo); `HttpClient` con `observe: 'events'` y `reportProgress` (acopla el streaming al interceptor y a la
  detección de cambios, y los bloques parciales no están garantizados).
- **Consecuencia**: `fetch` no pasa por `apiErrorInterceptor`, así que el cliente mapea sus propios errores (R6) y
  no se muestra además el *snack bar* genérico.

## R2. Parser SSE

- **Decision**: función pura que recibe texto decodificado, mantiene un búfer de línea incompleta y emite eventos
  `{ event, data }` al encontrar la línea en blanco. Soporta `\n` y `\r\n`, varias líneas `data:` (se unen con `\n`)
  y comentarios (`:`) que se ignoran. El `data` de LAB-12 es siempre JSON de una línea; se parsea en el cliente, no
  en el parser, para poder probarlo de forma independiente.
- **Rationale**: ~60 líneas, sin dependencias, 100 % determinista y testeable alimentándolo con cortes arbitrarios
  (mitad de línea, mitad de evento, un byte a la vez). Es la pieza donde se pierden o duplican fragmentos si falla.
- **Alternatives**: librería SSE (ver R1).

## R3. Markdown → HTML

- **Decision**: `marked` (última `18.1.2`) con GFM activado (tablas, listas, código); `async: false` y sin
  extensiones. El HTML sin confiar nunca llega al DOM (R4).
- **Rationale**: pequeño, rápido, sin dependencias, GFM de serie y tolerante a Markdown incompleto (un bloque de
  código sin cerrar se renderiza como código abierto hasta el final), que es lo que necesita el streaming (FR-010).
- **Alternatives**: `markdown-it` (más extensible, más pesado, sin necesidad en F0); `ngx-markdown` (envuelve marked
  y añade resaltado y *Prism*; trae más superficie de la necesaria y oculta el punto de saneamiento).

## R4. Saneamiento y punto único de confianza

- **Decision**: `DOMPurify` (última `3.4.17`, trae tipos propios; el paquete `@types/dompurify` es un *stub* y no
  se instala) sobre la salida de `marked`, con lista blanca explícita de etiquetas y atributos (`p, h1–h6, strong, em,
  del, ul, ol, li, blockquote, code, pre, a, table, thead, tbody, tr, th, td, hr, br`; atributos `href`, `title`,
  `class` solo en `code`). Un *hook* `afterSanitizeAttributes` fuerza `target="_blank"` y
  `rel="noopener noreferrer"` en todo `<a>`, y descarta `href` con esquemas distintos de `http`, `https` y `mailto`.
  `img` no está en la lista: la imagen remota se sustituye por un enlace con su texto alternativo (sin carga
  automática, FR-009). El resultado se marca con `DomSanitizer.bypassSecurityTrustHtml` **solo** dentro de
  `MarkdownRenderer`.
- **Rationale**: Angular ya sanea `[innerHTML]`, pero su lista no es configurable (elimina enlaces seguros y no
  permite forzar `rel`); sanear con DOMPurify y confiar en un solo lugar hace la superficie auditable y testeable
  con una batería XSS. Defensa en profundidad: además se evita `[innerHTML]` con cadenas sin pasar por el servicio.
- **Alternatives**: confiar solo en el saneador de Angular (no deja controlar enlaces ni imágenes); sanear con
  expresiones regulares (frágil, descartado); renderizar Markdown a componentes Angular (más seguro por construcción,
  pero desproporcionado para F0).

## R5. Rendimiento durante el streaming

- **Decision**: el *store* acumula los fragmentos recibidos en un búfer y los vuelca al *signal* del mensaje como
  máximo una vez por fotograma (`requestAnimationFrame`; en pruebas se sustituye por un planificador manual). Solo
  la burbuja en curso se vuelve a renderizar (`computed` por mensaje, mensajes completos con `track` estable). Al
  recibir `done`, `error` o al detener, se vuelca el búfer pendiente antes de cambiar el estado.
- **Rationale**: renderizar Markdown completo en cada `token` es O(n²) para respuestas largas y bloquearía el botón
  «Detener» (SC-002). Agrupar por fotograma mantiene la sensación de escritura continua sin trabajo redundante.
- **Alternatives**: renderizar por cada token (simple; falla con respuestas largas); *debounce* por tiempo (retrasa
  el primer texto visible); parser incremental de Markdown (complejidad innecesaria).

## R6. Estados y mapeo de errores

- **Decision**: estado del mensaje del asistente `streaming | complete | stopped | failed`. `ChatStreamClient` lanza
  un `ChatStreamError` con `kind`: `network` (sin respuesta), `validation` (400), `unavailable` (503), `server`
  (otros 5xx), `stream` (evento `error` o corte tras iniciar). `chat-error.ts` traduce `kind` a un mensaje en español
  y nunca expone el `code` ni la descripción del backend (solo se conservan para depurar en el objeto, no en pantalla).
- **Rationale**: los errores previos al flujo llegan como JSON HTTP y los posteriores como evento `error` (contrato
  LAB-12); ambos se unifican para que la UI trate un único tipo (FR-011/FR-012).
- **Alternatives**: mostrar `message` del backend (filtra detalles internos y está en inglés).

## R7. Cancelación y ciclo de vida

- **Decision**: `stop()` llama a `abort()` del `AbortController`, vuelca el búfer y marca el mensaje `stopped`; un
  `AbortError` provocado por el propio usuario no es un error. `DestroyRef.onDestroy` del componente aborta
  cualquier conexión abierta (FR-016). Un envío nuevo se bloquea mientras `isStreaming()` sea verdadero, lo que
  evita el doble envío por Enter repetido.
- **Rationale**: cerrar la conexión es lo que hace que LAB-12 cancele la generación en Ollama; distinguir la
  cancelación del error evita avisos falsos y mantiene la métrica «cancelada» del backend correcta.

## R8. Accesibilidad y diseño

- **Decision**: historial con `role="log"` y `aria-live="polite"`; cada burbuja con `aria-label` de autor; el
  indicador de escritura expone `role="status"`. El campo es un `textarea` con `cdkTextareaAutosize` (CDK ya
  instalado), Enter envía y Mayús+Enter inserta salto. Burbujas con ancho máximo relativo y `overflow-wrap: anywhere`;
  bloques `pre` con `overflow-x: auto`. Seguimiento del final con un *signal* `pinnedToBottom` actualizado en el
  evento `scroll` del contenedor.
- **Rationale**: cubre FR-014, FR-015 y FR-017 con APIs de Angular/CDK ya presentes, sin librerías extra.

## R9. Pruebas

- **Decision**: Vitest + jsdom (los de la base). El parser y el cliente se prueban con un `ReadableStream` construido
  a mano que entrega bytes en cortes arbitrarios; el saneamiento con una tabla de cargas maliciosas (`<script>`,
  `onerror=`, `<iframe>`, `javascript:`, `data:text/html`, `<img src=x onerror>`, `[x](javascript:...)`,
  HTML dentro de bloques de código) y comprobando el DOM resultante; el *store* con un cliente falso y el
  planificador manual. No se prueban estilos visuales.
- **Rationale**: son los riesgos reales (pérdida/duplicado de texto, XSS, estado colgado) y se verifican sin
  navegador ni backend, como exige el pipeline de CI (FR-019, SC-008).

## R10. Alcance de despliegue

- **Decision**: no se modifica `nginx.conf` ni el ingreso. La imagen sirve la app, pero `/api` no se enruta al
  backend desde ese `nginx`; el chat se valida en desarrollo con el proxy de LAB-13 (`proxy.conf.json`, ya sin
  acumular SSE) y se deja anotado como siguiente paso del despliegue (ingreso con rutas `/api` y
  `X-Accel-Buffering: no`, ya emitido por el backend).
- **Rationale**: el criterio de la historia es la pantalla; el enrutado de `/api` en el clúster pertenece a la
  cadena de despliegue, no a esta historia.
