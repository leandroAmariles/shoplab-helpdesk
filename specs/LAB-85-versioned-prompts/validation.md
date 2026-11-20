# Validación: Prompts versionados en Langfuse

Resultados de las comprobaciones manuales de [quickstart.md](quickstart.md). Se completa a medida que se implementa.

## Textos originales (T002, FR-028)

SHA-256 de los cuatro textos tal como los enlazaba Spring antes de moverlos a `backend/src/main/resources/prompts/`
(UTF-8, sin BOM). `PromptDefaultsTests` los comprueba.

| Prompt | Archivo | Caracteres | SHA-256 |
|---|---|---|---|
| `chat-system` | `prompts/chat-system.txt` | 1495 | `5c3f999d23aa1ffac66ab8f9877022230d2b0863f119142b45ff452f69dd345d` |
| `chat-small-talk` | `prompts/chat-small-talk.txt` | 1008 | `cac17ad10eb782c478b8a5a79b946a42ab46f38709bca27145062ce9a81a4b8d` |
| `chat-context` | `prompts/chat-context.txt` | 256 | `9239e2e217b2c605835e9fcfda9fb2ef8239ae67845cb2eb56a0d01aeea18752` |
| `chat-empty-context` | `prompts/chat-empty-context.txt` | 302 | `0c0641448594adbc312b27d01ecaa465e1094759f9f19633b69da9159ecaaba5` |

## T001 · API de prompts de Langfuse 4.77.0 (2026-11-19)

Langfuse local arrancado con el compose del repositorio (ver «Hallazgo de entorno»). Resultado con las claves locales:

| Petición | Resultado |
|---|---|
| `GET /v2/prompts/no-existe?label=production` | `404` `{"error":"LangfuseNotFoundError","message":"Prompt not found: 'no-existe' with label 'production'"}` |
| `GET /v2/prompts/no-existe` (sin etiqueta) | `404`, mismo error: sin `label` ni `version` se asume `label=production` |
| `POST /v2/prompts` `{name,type:"text",prompt,labels:["production"],commitMessage}` | `201`, `version: 1`, `labels: ["production","latest"]` |
| `POST` repetido con el mismo nombre y `labels: []` | `201`, `version: 2`, `labels: ["latest"]` (no es idempotente) |
| `GET ...?label=production` | `200` con la versión 1 (`type`, `prompt`, `version`, `labels`, `config`) |
| `GET ...?version=2` / `?version=99` | `200` / `404` («with version 99») |
| `GET ...?label=staging` (el prompt existe, la etiqueta no) | `404`, mismo error y mismo código que un prompt inexistente |
| `GET ...?label=latest` | `200` si el prompt existe (la última versión); `404` si no existe |
| `GET` con claves incorrectas | `401` `UnauthorizedError` |
| `POST` y `GET` con `type: "chat"` | se aceptan; la lectura devuelve `type: "chat"` y `prompt` como lista |
| `DELETE /v2/prompts/{name}` | `204` |

**Consecuencia para el diseño**: la API no distingue «prompt inexistente» de «etiqueta inexistente». El publicador
comprueba con `?label=latest` (Langfuse la pone automáticamente en la última versión de todo prompt): `404` ⇒ no existe, se
crea; `200` ⇒ existe, no se toca. Recogido en [contracts/langfuse-prompts-api.md](contracts/langfuse-prompts-api.md).

## Hallazgo de entorno (fuera del alcance de LAB-85)

`infra/docker-compose.yml` monta `langfuse_postgres_data` en `/var/lib/postgresql/data` con la imagen `postgres:18.6`
(subida por dependabot en #138). Las imágenes de PostgreSQL 18 exigen montar en `/var/lib/postgresql`, así que el servicio
`langfuse-postgres` se reinicia en bucle y el perfil `llmops` no arranca, ni con volumen nuevo. Para verificar se usó un
override local con `volumes: !override [langfuse_postgres_data:/var/lib/postgresql]` y el proyecto `lab85` (volúmenes
propios, sin tocar los de `shoplab`). Ver el resumen final por si se corrige en el repositorio.

## T025 · Publicación inicial y cambio de etiqueta contra el Langfuse real (2026-11-19)

Comprobado con los componentes reales del backend (`LangfusePromptClient`, `CachedPromptCatalog`, `PromptRefresher` y
`PromptPublisher`, con `publish-missing=true`, intervalo de 1 s) contra el Langfuse local 4.77.0 vacío de estos prompts, con
una prueba temporal que no se conserva:

| Paso | Resultado |
|---|---|
| Arranque con Langfuse sin los cuatro prompts | los cuatro se crean como **versión 1** con la etiqueta `production`; el texto servido es idéntico al valor por defecto |
| Repetir la publicación | siguen en la versión 1: no se crea ninguna versión |
| Crear la versión 2 de `chat-system` **sin etiqueta** | el chat sigue sirviendo la versión 1 |
| Mover `production` a la versión 2 | en ≤ 1 intervalo se sirve la versión 2, sin reiniciar |
| Mover `production` de vuelta a la versión 1 | se restaura exactamente el texto anterior |
| Leer la versión 2 fijada con `pinned` | se lee sin mover `production` ni cambiar lo que sirve el chat |

**Pendiente** (S-3, pasos 1 a 3 con respuesta real): preguntar al chat y ver que la respuesta cambia, que necesita un modelo.
Ollama se detuvo por falta de memoria (código 137) y no se levantó en esta sesión.

## T030 · La traza en Langfuse (2026-11-19)

Se envió un turno trazado de verdad (exportador OTLP de `TracingConfig`) al Langfuse local con `chat-system` v1 y
`chat-context` con el valor por defecto, y se leyó lo almacenado con `GET /api/public/v2/observations` (en el modo
`events_only` de Langfuse 4.77 los endpoints `/api/public/traces` y `/api/public/observations` no existen):

| Dónde | Resultado |
|---|---|
| Generación | `promptName: chat-system`, `promptVersion: 1` y `promptId` del prompt real: **el enlace nativo funciona** y permite saltar a la versión |
| Generación (metadatos) | `prompt.chat-system: 1`, `prompt.chat-context: "default"` |
| Traza (`chat-turn`, metadatos) | `prompt_chat_system: 1`, `prompt_chat_context: "default"` |
| Con el valor por defecto como principal | sin enlace nativo (comprobado en `LangfuseChatTracePromptsTests`) |

**Pendiente**: el filtro de la lista de trazas por metadato en la interfaz (S-4 paso 3) y las 20 preguntas con el modelo real.
Los metadatos están en la traza con las claves del contrato, así que la columna de metadatos sirve en cualquier caso.

## T070 · Verificación automática (2026-11-19)

| Comprobación | Resultado |
|---|---|
| `./mvnw verify` (backend) | 1236 pruebas, 0 fallos; «All coverage checks have been met» (JaCoCo ≥ 80 %); 0 violaciones de Checkstyle |
| `uv run pytest` y `ruff check` (eval) | 546 pruebas, cobertura 98 %, lint limpio |
| Prueba de arquitectura reactiva | el arranque espera la carga con un `Future` (no con `block()`), como exige `ReactiveArchitectureTests` |
| `NoPromptTextInCodeTests` (SC-001) | ninguna frase de los prompts fuera de `resources/prompts/` |
| Cambio de etiqueta contra Langfuse real | ver «T025» |
| Traza real en Langfuse | ver «T030» |
| Resolución de versiones de `eval/` contra Langfuse real | versión que existe, etiqueta `production`, versión 99 y etiqueta `staging` inexistentes: el resultado esperado en cada caso |

## Sin comprobar todavía (necesitan el modelo, el clúster o una persona)

- **T025/T030/T043 (S-3, S-4)**: preguntar al chat con el modelo real y ver que la respuesta cambia al mover la etiqueta; las 20
  preguntas de SC-005, el filtro de la lista de trazas por metadato y el cronometraje de SC-002/SC-003 con dos instancias.
  Ollama se detuvo por falta de memoria (código 137).
- **T037 (S-5)**: Langfuse detenido en un backend real y el proxy lento; la lógica está cubierta por pruebas con WireMock.
- **T046 (S-9)**: el despliegue en DEV (el cambio de `shoplab-infra` está hecho en el árbol de trabajo, sin commit).
- **T065/T071 (S-7, S-8)**: dos mediciones `quality` con el juez real y `compare`; la recuperación antes y después; la calidad
  antes y después. El `git grep` de SC-001 sí se hizo.
