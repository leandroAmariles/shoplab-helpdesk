# Validación: Pantalla de chat con streaming y Markdown

## Verificación automática (2026-10-26)

Node 22.22.3, Angular 22.2.x, `marked` 18.1.1, `dompurify` 3.4.17.

| Comando | Resultado |
|---|---|
| `npm audit` | OK, 0 vulnerabilidades |
| `npm run lint` | OK, 0 errores y 0 advertencias |
| `npm test -- --watch=false` | OK, 14 archivos, 165 pruebas |
| `npm run build` | OK, bundle inicial 533.07 kB, sin avisos |

**Presupuesto de bundle**: el inicial pasó de 475.65 kB (LAB-13) a 533.07 kB al registrar la ruta `/chat`
(esbuild mueve a un *chunk* compartido, cargado al inicio, código común al `main` y a la pantalla). `marked` y
`DOMPurify` **no** están en el inicial: viven en el *chunk* diferido de `chat`. Se subió el aviso de
`angular.json` (`initial.maximumWarning`) de 500 kB a 600 kB; el límite de error sigue en 1 MB. **Pendiente**:
analizar qué módulos entraron al inicial y recuperar margen si hace falta.

## Trazabilidad requisito → comprobación

| Requisito | Comprobación |
|---|---|
| FR-001 ruta `/chat`, layout, navegación | `app.routes.spec.ts` («renders the chat inside the shell…»), `chat.spec.ts` («empty screen») |
| FR-002 burbujas por autor | `message-bubble.spec.ts` («author») |
| FR-003 envío inmediato y `POST /api/chat` | `chat-stream.client.spec.ts` («posts the message…»), `chat-store.spec.ts` («sending»), `chat.spec.ts` («conversation») |
| FR-004 consumo `token`/`done`/`error` | `sse-parser.spec.ts`, `chat-stream.client.spec.ts`, `chat-store.spec.ts` («streaming») |
| FR-005 indicador de escritura | `message-bubble.spec.ts` («typing indicator»), `chat.spec.ts` |
| FR-006 detener | `chat-store.spec.ts` («stopping»), `message-input.spec.ts` («stop button»), `chat.spec.ts` («stop button») |
| FR-007 Markdown / texto literal | `markdown-renderer.spec.ts`, `message-bubble.spec.ts` («Markdown», «author») |
| FR-008 saneamiento | `markdown-renderer.spec.ts` («malicious content»), regla ESLint `no-restricted-syntax` |
| FR-009 enlaces e imágenes | `markdown-renderer.spec.ts` («links and images») |
| FR-010 Markdown incompleto | `markdown-renderer.spec.ts` («incomplete input»), `message-bubble.spec.ts` |
| FR-011 errores en español | `chat-error.spec.ts`, `chat-store.spec.ts` («errors»), `message-bubble.spec.ts` («failed answer») |
| FR-012 texto parcial y reintento | `chat-store.spec.ts` («retry»), `chat.spec.ts` («errors») |
| FR-013 validación de envío | `chat-store.spec.ts` («canSend»), `message-input.spec.ts` («counter») |
| FR-014 Enter / Mayús+Enter / foco | `message-input.spec.ts` («sending»), `chat.spec.ts` («returns the focus…») |
| FR-015 seguimiento del final | `chat.spec.ts` («following the answer») |
| FR-016 cerrar al salir | `chat-store.spec.ts`, `chat.spec.ts` («cancels the open connection…») |
| FR-017 responsive y accesibilidad | `chat.spec.ts` («accessibility») — **estilos sin revisar visualmente**, ver pendientes |
| FR-018 signals, standalone, lógica separada | estructura (`chat-store.ts`, `chat-stream.client.ts`, componentes de presentación) |
| FR-019 pruebas del flujo | `sse-parser.spec.ts`, `chat-stream.client.spec.ts`, `markdown-renderer.spec.ts` |
| FR-020 idioma | revisión de textos de interfaz (español) y de identificadores (inglés) |
| FR-021 fuera de alcance | — |

## Contra el stack real (T042, 2026-10-27)

Stack local: `docker compose -f infra/docker-compose.yml up -d --wait` (Postgres + Ollama con `llama3.2:3b`),
backend de LAB-12 con `./mvnw spring-boot:run` y la app con `npm start` (proxy `/api` → `localhost:8080`).
Se ejecutaron `ChatStreamClient`, `ChatStore` y `MarkdownRenderer` **reales** desde pruebas temporales (no
versionadas) apuntando al proxy, más `curl -N` para los fallos del modelo.

| Escenario del quickstart | Resultado |
|---|---|
| Primer fragmento (SC-001), 5 peticiones con el modelo caliente | 173, 85, 98, 79 y 86 ms: todas < 2 s |
| Respuesta larga llega incremental (SC-002) | 193 fragmentos repartidos en 12,1 s; el primero a 123 ms |
| «Detener» tras 5 fragmentos (SC-003, escenarios 4 y 18) | `shoplab_chat_response_seconds_count{outcome="cancelled"}` pasó de 2 a 3 y `outcome="error"` se quedó en 0; la siguiente petición terminó con `done` |
| Mensaje vacío, solo espacios y de 4001 caracteres (escenarios 14–15) | El backend responde 400 y el cliente lo convierte en `validation` |
| Ollama detenido antes de empezar (escenario 11) | HTTP 503 `SLHD_CHAT_00002` → `unavailable` |
| Ollama detenido a mitad de una respuesta (escenario 12) | Evento `error` `SLHD_CHAT_00003` tras varios `token` → texto parcial conservado y estado `failed` |
| Markdown real del modelo (escenario 6) | Lista (3 `li`), tabla y bloque de código renderizados, sin `script`; mensaje visible → primer texto en 216 ms |
| Formato real del SSE | El backend envía `event:token` / `data:{…}` **sin espacio** tras los dos puntos; el parser lo admite (probado) |

**Hallazgo corregido**: con el backend caído, el proxy de desarrollo responde 502 y el cliente lo trataba como
error de servidor. Ahora 502 y 504 se mapean a `network` («No se pudo conectar con el servidor») con su prueba.

## Pendientes (no verificados)

- **Navegador**: no hubo ningún navegador disponible en esta sesión. Siguen sin verificar a ojo los escenarios 1–3,
  5, 7–10, 13 y 16–17 (interacción visual, enlaces en pestaña nueva, foco, teclado) y **SC-006** (360 px y
  1440 px) y **SC-007** (30 s de la primera conversación). Su lógica está cubierta por pruebas unitarias.
- **Backend caído en el navegador (escenario 10)**: cubierto por prueba unitaria (502 y fallo de `fetch`), no
  probado con el backend apagado.
- **T043 — CI en el pull request**: ver el estado del PR.
- **T044 — commit y PR**: ver el estado del PR.
