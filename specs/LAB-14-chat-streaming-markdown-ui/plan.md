# Implementation Plan: Pantalla de chat con streaming y Markdown

**Branch**: `feature/LAB-14-chat-streaming-markdown-ui` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-14-chat-streaming-markdown-ui/spec.md`

## Summary

Se añade a la app Angular de LAB-13 la ruta `/chat` (carga diferida, entrada en la navegación) con una pantalla
compuesta por un historial de burbujas, un indicador de escritura y un área de entrada con botón «Detener». El flujo
de `POST /api/chat` (LAB-12) se consume con `fetch` + `ReadableStream` (la API `EventSource` no admite `POST`): un
parser SSE puro y probado ensambla los eventos `token` / `done` / `error` a partir de bloques de bytes, y un cliente
delgado expone el flujo como un `Observable` cancelable con `AbortController`. El estado de la conversación vive en
un *store* de signals que agrupa los fragmentos por fotograma para no re-renderizar en cada token. Las respuestas del
asistente se convierten de Markdown a HTML con `marked` y se sanean con `DOMPurify` (lista blanca, enlaces
`target="_blank" rel="noopener noreferrer"`, sin imágenes remotas) en un único servicio auditado; solo ese servicio
marca el HTML como confiable. El texto del cliente se muestra siempre como texto literal. Decisiones y riesgos en
[research.md](research.md).

## Technical Context

**Language/Version**: TypeScript (modo estricto) sobre Angular 22.x (la base de LAB-13); Node `^22.22.3`

**Primary Dependencies**: las de LAB-13 (`@angular/core|router|forms|material|cdk`) más `marked` (Markdown → HTML)
y `dompurify` (saneamiento; trae sus propios tipos). Sin NgRx ni librerías SSE: el parser son ~60 líneas

**Storage**: N/A (la conversación vive en memoria de la pestaña; se pierde al recargar)

**Testing**: Vitest + jsdom (runner por defecto de la base). Pruebas unitarias del parser, del cliente (con un
`fetch` simulado que entrega bloques de bytes arbitrarios), del *store*, del renderizador Markdown (batería XSS) y
de los componentes

**Target Platform**: navegadores evergreen; desarrollo con `ng serve` y el proxy `/api` de LAB-13

**Project Type**: web-application (solo `frontend/`; no se toca el backend)

**Performance Goals**: mensaje del usuario visible en < 100 ms; fragmento en pantalla < 300 ms tras emitirse;
«Detener» responde en < 500 ms con una respuesta larga (SC-001, SC-002). Se logra agrupando las actualizaciones por
`requestAnimationFrame` y renderizando Markdown solo de la burbuja en curso

**Constraints**: sin `EventSource`; HTML siempre saneado antes de llegar al DOM; UI en español, identificadores en
inglés; límite de 4000 caracteres alineado con el backend; la conexión se cierra al detener o al salir de la ruta

**Scale/Scope**: 1 ruta, ~8 archivos de código nuevos en `features/chat/` más 1 servicio en `core/`; sin cambios en
el layout salvo la entrada de navegación (vía `data.nav` de la ruta)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|-----------|------------|--------|
| I. Monolito modular | No toca el backend | N/A |
| II. Hexagonal | Aplica al backend. En el frontend el acceso a `/api` queda tras `ChatStreamClient` (puerto de salida del cliente), el estado tras `ChatStore` y los componentes solo presentan: ningún componente llama a `fetch` | Pasa |
| III. Identificadores en inglés | Código en inglés; textos de interfaz y errores visibles en español | Pasa |
| IV. Test-first y cobertura | El umbral del 80 % es del backend; en el frontend las pruebas se escriben con cada pieza y cubren parser, cliente, *store*, saneamiento y componentes (FR-019), corriendo en CI | Pasa |
| V. RAG evaluado | No aplica (sin recuperación ni citas) | N/A |
| VI. Observabilidad y secretos | Sin secretos ni datos reales; no se registra el contenido de los mensajes en consola. El identificador de correlación lo gestiona el backend | Pasa |
| VII. Simplicidad y ADR | Sin gestor de estado ni librería SSE (YAGNI). Elegir una librería de Markdown y de saneamiento es una decisión con impacto de seguridad: se registra en `docs/adr/0014-...` | Pasa, con ADR pendiente |

**Re-evaluación tras el diseño (Phase 1)**: sin cambios. No se añaden servicios en el backend ni persistencia.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-14-chat-streaming-markdown-ui/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1
│   ├── chat-stream-client.md
│   └── markdown-rendering.md
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── package.json                         # + marked, dompurify
└── src/app/
    ├── app.routes.ts                    # + ruta /chat (loadComponent, title, data.nav)
    ├── core/
    │   └── markdown/
    │       ├── markdown-renderer.ts     # marked + DOMPurify -> SafeHtml (único punto de confianza)
    │       └── markdown-renderer.spec.ts
    └── features/chat/
        ├── chat.ts / chat.html / chat.scss          # pantalla (contenedor)
        ├── chat.spec.ts
        ├── chat-message.ts                          # modelo: autor, contenido, estado
        ├── chat-store.ts                            # signals: mensajes, envío, detener, reintentar
        ├── chat-store.spec.ts
        ├── chat-stream.client.ts                    # fetch POST + ReadableStream -> Observable de eventos
        ├── chat-stream.client.spec.ts
        ├── sse-parser.ts                            # bytes/texto -> eventos SSE (puro)
        ├── sse-parser.spec.ts
        ├── chat-error.ts                            # fallo -> mensaje en español
        ├── message-bubble/                          # burbuja + Markdown + indicador de escritura
        └── message-input/                           # textarea, contador, enviar/detener
docs/adr/0014-markdown-seguro-en-el-chat.md
README.md                                            # sección frontend: chat
```

**Structure Decision**: la pantalla es una carpeta nueva `features/chat/` y una entrada en `app.routes.ts`, tal como
dejó previsto LAB-13 (SC-004). El renderizador de Markdown va en `core/` porque la consola de agentes (F3) lo
reutilizará, y es el único lugar donde se llama a `bypassSecurityTrustHtml`. El *store* se provee a nivel de
componente (no `root`) para que la conversación y la conexión mueran con la pantalla (FR-016).

## Complexity Tracking

Sin violaciones de la constitución que justificar. Límite conocido, fuera de alcance: el `nginx` de la imagen no
enruta `/api` al backend (lo resuelve el ingreso del clúster, ver cadena de despliegue); el chat se valida en
desarrollo con el proxy de LAB-13.
