# Quickstart: validar la pantalla de chat

Guía para comprobar la historia de punta a punta en local. Contratos: [chat-stream-client.md](contracts/chat-stream-client.md)
y [markdown-rendering.md](contracts/markdown-rendering.md). Estructuras: [data-model.md](data-model.md).

## Requisitos

- Node `^22.22.3` (ver `frontend/.nvmrc`) y dependencias instaladas: `cd frontend && npm ci`.
- Entorno local de LAB-10 levantado (`docker compose -f infra/docker-compose.yml up`) con el modelo de chat ya
  descargado, y el backend de LAB-12 en `http://localhost:8080`.
- Para los casos de error: poder detener el contenedor de Ollama o el backend a voluntad.

## 1. Pruebas automáticas (sin backend ni navegador)

```bash
cd frontend
npm run lint
npm test -- --watch=false
npm run build
```

Resultado esperado: lint sin advertencias, todas las pruebas en verde (parser SSE, cliente, *store*, renderizador
Markdown con la batería XSS, componentes) y build de producción sin errores ni excesos de presupuesto.

## 2. Arrancar la app en desarrollo

```bash
cd frontend
npm start
```

Abrir `http://localhost:4200/chat`. Debe verse el layout de LAB-13, la entrada «Chat» en la navegación y el
historial vacío con la invitación a escribir.

## 3. Escenarios (por historia del spec)

| # | Historia | Pasos | Resultado esperado |
|---|----------|-------|--------------------|
| 1 | US1 | Escribir «Hola, ¿qué puedes hacer?» y pulsar Enter | Burbuja del usuario al instante; burbuja del asistente que crece con indicador de escritura; el indicador desaparece al terminar; el campo recupera el foco |
| 2 | US1 | Enviar un mensaje con tildes, eñes y emojis | Caracteres íntegros en ambas burbujas |
| 3 | US1 | Pedir una respuesta larga y subir con la rueda a mitad | La vista deja de seguir el final y ofrece volver; al volver, sigue el streaming |
| 4 | US2 | Pedir una respuesta larga y pulsar «Detener» | Cesa el texto nuevo, desaparece el indicador, se conserva lo recibido y queda marcada como interrumpida; el siguiente mensaje se envía con normalidad |
| 5 | US2 | Con la respuesta terminada, mirar el área de entrada | No hay botón «Detener» |
| 6 | US3 | Pedir «una lista, una tabla y un bloque de código en Python» | Lista, tabla y bloque monoespaciado con scroll horizontal, sin romper el ancho de la burbuja |
| 7 | US3 | Pedir un enlace en la respuesta y pulsarlo | Se abre en pestaña nueva; `rel="noopener noreferrer"` en el DOM |
| 8 | US3 | Escribir `<script>alert(1)</script> **negrita**` como mensaje | El texto se ve literal en la burbuja del usuario; no se ejecuta nada |
| 9 | US3 | Con el backend simulado (ver §4), responder `<img src=x onerror=alert(1)>` | No aparece alerta ni `img` en el DOM |
| 10 | US4 | Detener el backend y enviar un mensaje | Aviso en español de conexión; sin indicador colgado; chat utilizable |
| 11 | US4 | Detener Ollama (backend arriba) y enviar | Aviso de asistente no disponible (503); opción de reintentar |
| 12 | US4 | Cortar Ollama a mitad de una respuesta | Texto parcial conservado, burbuja marcada como fallida, aviso y opción de reintentar |
| 13 | US4 | Pulsar «Reintentar» tras un fallo | Se reenvía el último mensaje y se crea una burbuja de asistente nueva |
| 14 | US5 | Campo vacío / solo espacios | Botón de enviar deshabilitado |
| 15 | US5 | Pegar 4001 caracteres; luego 4000 | Contador en error y envío bloqueado con 4001; con 4000 se envía |
| 16 | US5 | Mayús+Enter y Enter repetido rápido | Salto de línea; un único mensaje enviado |
| 17 | US6 | Ancho de ventana a 360 px y a 1440 px; recorrer todo con Tab | Sin desbordes; foco visible; enviar y detener sin ratón |
| 18 | FR-016 | Con una respuesta en curso, navegar a Inicio | La petición se cancela (en el backend queda como «cancelada», no como error) |

## 4. Respuestas controladas (sin Ollama)

Para los casos de XSS, Markdown incompleto y errores a mitad de flujo, las pruebas automáticas usan un `fetch`
simulado. Para verlo en el navegador basta un servidor SSE local mínimo (por ejemplo un script de Node de unas
líneas que responda a `POST /api/chat` en el puerto 8080 con eventos `token`/`done`/`error` a medida) apagando el
backend real; no se versiona.

## 5. Comprobar la métrica de cancelación en el backend

Tras el escenario 4 o 18, consultar `http://localhost:8080/actuator/prometheus` y comprobar que el contador de
respuestas de chat con resultado `cancelled` aumentó y el de `error` no.

## Límite conocido

La imagen de contenedor de `frontend` no enruta `/api` al backend (lo resuelve el ingreso del clúster, fuera de
esta historia): el chat se valida en desarrollo con el proxy de LAB-13.
