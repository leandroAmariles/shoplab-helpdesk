# Contrato: renderizado seguro de Markdown

**Feature**: LAB-14 | Ubicación: `frontend/src/app/core/markdown/markdown-renderer.ts`

## `MarkdownRenderer`

```ts
interface MarkdownRenderer {
  /** Markdown (posiblemente incompleto) -> HTML saneado, apto para [innerHTML]. */
  render(markdown: string): SafeHtml;
}
```

Es el **único** lugar del proyecto que llama a `bypassSecurityTrustHtml`. Ningún componente enlaza `[innerHTML]`
con una cadena que no venga de `render`. El contenido del cliente no pasa por aquí: se muestra con interpolación
(`{{ }}`), que Angular escapa.

## Lista blanca

| Tipo | Permitido |
|------|-----------|
| Etiquetas | `p, br, hr, h1, h2, h3, h4, h5, h6, strong, em, del, ul, ol, li, blockquote, code, pre, a, table, thead, tbody, tr, th, td` |
| Atributos | `href` y `title` en `a`; `class` en `code` (lenguaje del bloque); `start` en `ol`; `align` en `th`/`td` |
| Esquemas de `href` | `http`, `https`, `mailto` (y rutas relativas de ancla `#`); cualquier otro se elimina |

Todo lo demás se elimina conservando el texto interno cuando sea seguro (`<b>` → no permitido: queda su texto).

## Transformaciones obligatorias

| Elemento | Resultado |
|----------|-----------|
| `a` | `target="_blank"` y `rel="noopener noreferrer"` siempre |
| `img` (`![alt](url)`) | No se renderiza como imagen: se muestra el texto alternativo (o la URL) como enlace. No hay petición automática |
| `script`, `style`, `iframe`, `object`, `embed`, `form`, `input`, `svg`, `math` | Eliminados con su contenido |
| Atributos `on*`, `style`, `srcdoc`, `formaction` | Eliminados |
| HTML crudo dentro de bloques de código | Se muestra como texto (escapado), no como HTML |

## Tolerancia al streaming

| Entrada parcial | Comportamiento |
|-----------------|----------------|
| Bloque de código con ``` sin cerrar | Se renderiza como código abierto hasta el final del texto |
| Lista o tabla a medias | Se renderiza lo completo; el resto aparece al llegar más texto |
| Énfasis sin cerrar (`**texto`) | Texto literal hasta que se cierre |
| Cadena vacía | Cadena vacía (la burbuja muestra solo el indicador) |

**Invariante de consistencia**: para cualquier texto `T`, `render(T)` es idéntico se obtenga de una sola llamada o
tras renderizar sus prefijos sucesivos; solo el último render importa (no hay estado entre llamadas).

## Batería de pruebas obligatoria (SC-004)

Cada caso comprueba el DOM resultante, no solo la cadena:

| Entrada | Resultado esperado |
|---------|--------------------|
| `<script>alert(1)</script>` | Sin `script`; no se ejecuta |
| `<img src=x onerror=alert(1)>` | Sin `img` ni `onerror` |
| `[clic](javascript:alert(1))` | Sin `href` o con `href` eliminado |
| `[clic](data:text/html;base64,...)` | Sin `href` |
| `<iframe src="https://example.com"></iframe>` | Sin `iframe` |
| `<a href="https://x" onclick="...">x</a>` | `href` conservado, sin `onclick`, con `rel="noopener noreferrer"` |
| `![logo](https://example.com/a.png)` | Sin `<img>`; texto/enlace visible |
| `` `<script>x</script>` `` y bloque ``` | Texto escapado dentro de `code` |
| `<svg onload=alert(1)>` | Sin `svg` |
| `<style>body{display:none}</style>` | Sin `style` |
| HTML mal formado (`<p><b>x`) | DOM válido, sin romper el contenedor |
| Markdown estándar (listas, tablas, énfasis, citas, código) | Estructura HTML esperada conservada |
