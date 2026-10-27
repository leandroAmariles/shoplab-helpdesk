# ADR-0014: Streaming por fetch y Markdown saneado en el chat

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

LAB-14 añade la pantalla de chat de `frontend/`. Debe consumir el flujo SSE de `POST /api/chat` (LAB-12) y mostrar
las respuestas del modelo, que llegan en Markdown. Hay dos decisiones con impacto en seguridad y mantenimiento:

1. **Cómo leer un SSE con `POST`.** `EventSource` solo admite `GET`.
2. **Cómo renderizar Markdown generado por un modelo.** La respuesta es contenido no confiable: puede incluir HTML,
   scripts, enlaces `javascript:` o imágenes remotas que filtran datos de navegación.

Se evaluaron para el streaming: `fetch` + `ReadableStream`, `@microsoft/fetch-event-source` y `HttpClient` con
`reportProgress`. Para el Markdown: `marked` + `DOMPurify`, `markdown-it`, `ngx-markdown` y confiar solo en el
saneador de Angular.

## Decisión

- **Streaming**: `fetch` con `AbortController` y un parser SSE propio (~60 líneas, función pura, probada con cortes
  arbitrarios). Sin librería SSE ni `EventSource`.
- **Markdown**: `marked` (GFM) para convertir y `DOMPurify` con lista blanca explícita de etiquetas, atributos y
  esquemas de URL. Los enlaces siempre llevan `target="_blank"` y `rel="noopener noreferrer"`; las imágenes no se
  renderizan (se muestra su texto alternativo como enlace).
- **Punto único de confianza**: solo `MarkdownRenderer` (`core/markdown/`) llama a `bypassSecurityTrustHtml`. El
  texto del cliente nunca pasa por Markdown: se muestra con interpolación.

## Consecuencias

- Dos dependencias nuevas (`marked`, `dompurify`) que hay que mantener al día; una vulnerabilidad en cualquiera de
  ellas afecta directamente al chat, por lo que Dependabot y el escaneo de CI deben cubrir `frontend/`.
- `fetch` no pasa por `apiErrorInterceptor`: `ChatStreamClient` mapea sus propios errores y el chat los muestra en la
  propia pantalla en lugar del *snack bar* genérico.
- La batería de pruebas XSS (`markdown-renderer.spec.ts`) es parte del contrato: cualquier cambio en la lista blanca
  o en las bibliotecas debe pasarla.
- Si la consola de agentes (F3) muestra contenido del modelo, reutiliza `MarkdownRenderer`; no se añade otro punto de
  `bypassSecurityTrustHtml`.
- Si se necesitara resaltado de sintaxis, copiar código o citas con enlaces, se extiende este renderizador y se
  revisa la lista blanca; no se sustituye por una biblioteca que oculte el saneamiento.
