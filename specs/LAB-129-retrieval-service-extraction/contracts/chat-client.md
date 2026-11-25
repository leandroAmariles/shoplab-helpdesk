# Contrato: cliente del chat hacia retrieval-service

## Puerto (sin cambios)

`KnowledgeContextPort.retrieve(question, limit)` → lista de `ContextFragment`
(`source`, `title`, `section`, `position`, `text`, `score`). El resto de `chat` no cambia.

## Adaptadores

| `shoplab.retrieval.client.mode` | Release A | Release B |
|---|---|---|
| `in-process` (por defecto en A) | `KnowledgeSearchAdapter` | se elimina |
| `http` | adaptador sobre la interfaz generada | único; desaparece la propiedad `mode` |

El adaptador HTTP sustituye a `HttpKnowledgeSearchClient` y usa la interfaz generada de `KnowledgeSearch`
(`shoplab-contracts` 1.3.0) sobre `WebClient`.

## Comportamiento

| Caso | Resultado |
|---|---|
| `200` con resultados | fragmentos en el mismo orden |
| Resultado sin texto | se descarta con aviso, sin registrar su contenido |
| Título vacío | se deriva del nombre del archivo, como hoy |
| `200` sin resultados | lista vacía (no es fallo) |
| `4xx`, `5xx`, conexión rechazada, tiempo agotado, cuerpo ilegible | `KnowledgeRetrievalException` → `SLHD_CHAT_00004` (503) |

- Tiempo máximo: `shoplab.retrieval.client.timeout`, 15 s por defecto. Sin reintentos.
- Cabeceras: `X-Request-Id` del contexto de la petición; `traceparent` si llegó.
- La pregunta llega sin alterar: caracteres especiales y la consulta compuesta más larga.
- Umbral, `top-k` y presupuesto de contexto siguen en `chat`.

## Pruebas

- Consumidor contra los stubs de `shoplab-retrieval-service` (acierto, vacío, `422`).
- WireMock: tiempo agotado, `503`, cabeceras propagadas, codificación de la consulta.
- Las pruebas de integración del chat que hoy usan el módulo real pasan en la release B a un doble de
  `KnowledgeContextPort`.
