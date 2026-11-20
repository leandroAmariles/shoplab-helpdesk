# Quickstart: verificar los prompts versionados

Guía de validación de extremo a extremo. Los detalles de cada interfaz están en [contracts/](contracts/); los tipos, en
[data-model.md](data-model.md). Las pruebas automáticas no sustituyen a estas comprobaciones: aquí se usa un Langfuse y un
modelo reales.

## Requisitos

- Docker con el perfil `llmops` (Langfuse en `http://localhost:3001`; credenciales locales del README, sección de Langfuse).
- El stack `core` con Ollama y el backend arrancado desde el host (ver el README).
- Variables del backend (valores locales en [contracts/prompts-config.md](contracts/prompts-config.md)):
  `SHOPLAB_CHAT_PROMPTS_ENABLED=true`, `..._PUBLISH_MISSING=true`, `..._REFRESH_INTERVAL=10s`,
  `..._OVERRIDE_TOKEN=local-eval-token` y las claves del envío de trazas con `SHOPLAB_CHAT_TRACING_ENABLED=true`.

## S-1. Comprobar lo no verificado de la API (antes de implementar el cliente)

Con `curl` y las claves locales:

1. `GET /api/public/v2/prompts/no-existe?label=production` → anotar el código y el cuerpo.
2. `POST /api/public/v2/prompts` con un prompt de prueba `lab85-prueba` (`type: text`, `labels: ["production"]`) → anotar el
   cuerpo aceptado y la versión devuelta; repetirlo y comprobar que crea la versión 2.
3. Quitarle la etiqueta desde la interfaz y repetir el `GET ?label=production` → ¿mismo código que un prompt inexistente?
4. Borrar `lab85-prueba`.

**Resultado esperado**: lo anotado coincide con [langfuse-prompts-api.md](contracts/langfuse-prompts-api.md) o se corrige ese
documento antes de seguir.

## S-2. Publicación inicial (historia 1, FR-005, SC-008)

1. Con Langfuse vacío y `PUBLISH_MISSING=true`, arrancar el backend.
2. En la interfaz de Langfuse (Prompts) aparecen `chat-system`, `chat-small-talk`, `chat-context` y `chat-empty-context`, cada uno
   en la versión 1 con la etiqueta `production`.
3. Reiniciar el backend: sigue habiendo la versión 1 de cada uno (no se creó ninguna).
4. Editar `chat-system` en Langfuse (versión 2, sin etiqueta) y reiniciar: sigue `production` en la versión 1.

## S-3. El chat usa lo publicado y un cambio de etiqueta cambia el comportamiento (historias 1 y 4, SC-002 a SC-004)

1. Preguntar «¿cuánto tarda el reembolso de un boleto?» y guardar la respuesta.
2. Crear la versión 2 de `chat-system` con una regla visible, por ejemplo «termina siempre con la frase "— Equipo ShopLab"».
   Sin etiquetarla, volver a preguntar: la respuesta **no** cambia.
3. Mover `production` a la versión 2 y, pasados ≤ 10 s, preguntar de nuevo: la respuesta termina con la frase, sin reiniciar el
   backend.
4. Mover `production` de nuevo a la versión 1 y, pasados ≤ 10 s, preguntar: vuelve a no terminar con la frase.
5. Con una conversación abierta (mismo `conversationId`), repetir el cambio entre dos turnos: el segundo usa la versión nueva y
   conserva el historial.

## S-4. La traza dice qué prompt se usó (historia 2, SC-005)

1. Con el tracing activado, hacer una pregunta de conocimiento y un «Hola».
2. En Langfuse, la traza de conocimiento muestra en su generación el enlace a `chat-system` v`N` (y se puede saltar a esa
   versión); sus metadatos incluyen `prompt_chat_system`, `prompt_chat_context` con la versión. La de «Hola» enlaza
   `chat-small-talk` y **no** tiene `prompt_chat_context`.
3. Filtrar la lista de trazas por el metadato `prompt_chat_system = 2` muestra solo los turnos de S-3 paso 3.
4. Mover la etiqueta justo mientras una respuesta larga se emite y comprobar que su traza conserva la versión con que empezó.

## S-5. Langfuse caído, lento y ediciones malas (historia 3, SC-006, SC-007)

1. **Caído al arrancar**: detener Langfuse, arrancar el backend (tarda como mucho 2 s más) y preguntar: responde con los valores
   por defecto, sin error; la traza (si se pudiera enviar) marca `default`.
2. **Cae después de cargar**: con prompts cargados, detener Langfuse y preguntar durante más de un intervalo: sigue respondiendo
   con la última versión; en el registro hay avisos estrangulados (no uno por pregunta) y sin texto de conversación ni claves.
3. **Vuelve**: arrancar Langfuse y, tras un intervalo, comprobar que un cambio de etiqueta vuelve a surtir efecto.
4. **Ediciones malas**, cada una puesta en `production`: una versión vacía de `chat-system`; una de `chat-small-talk` sin
   `{topics}`; una de `chat-context` con `{cliente}`; una de más de 20 000 caracteres. En todos los casos el chat responde con la
   última versión válida, y el registro dice el prompt, la versión y el motivo (`blank`, `missing-placeholder`,
   `not-renderable`, `too-long`) sin su texto.
5. **Prompt inexistente**: borrar `chat-empty-context` en Langfuse y reiniciar con `PUBLISH_MISSING=false`: un solo aviso por
   intervalo y el valor por defecto en uso.
6. **Lentitud**: con un proxy que retrase 5 s las respuestas de Langfuse, el tiempo hasta el primer token del chat no cambia.

## S-6. Fijar versiones solo para evaluar (historia 5, FR-017/FR-018)

Con el backend en `http://localhost:8080`:

```bash
curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"¿cuánto tarda el reembolso?"}' \
  -H 'X-Prompt-Versions: chat-system=2' -H 'X-Prompt-Token: local-eval-token'     # versión 2, sin tocar production
curl -i -X POST localhost:8080/api/chat ... -H 'X-Prompt-Versions: chat-system=2'    # sin token → 403
curl -i -X POST localhost:8080/api/chat ... -H 'X-Prompt-Versions: chat-system=2' -H 'X-Prompt-Token: otro'  # → 403
curl -i -X POST localhost:8080/api/chat ... -H 'X-Prompt-Versions: chat-system=x' -H 'X-Prompt-Token: local-eval-token' # → 400
curl -N -X POST localhost:8080/api/chat ... -H 'X-Prompt-Versions: chat-system=99' -H 'X-Prompt-Token: local-eval-token' # evento error
```

Comprobar que, mientras tanto, una pregunta sin cabeceras sigue usando `production`, y que con `OVERRIDE_TOKEN` vacío, o con `PROMPTS_ENABLED=false`, la
primera llamada también da 403.

## S-7. Evaluar y comparar dos versiones (fase B, SC-009, SC-010; requiere la fase A y el comando `quality` de LAB-29)

```bash
cd eval
export SHOPLAB_EVAL_PROMPT_TOKEN=local-eval-token
uv run python -m shoplab_eval quality --prompt chat-system=1 --run-id prompt-a --out results/prompt-a --assistant-model <modelo>
uv run python -m shoplab_eval quality --prompt chat-system=2 --run-id prompt-b --out results/prompt-b --assistant-model <modelo>
uv run python -m shoplab_eval compare results/prompt-a/quality-summary.csv results/prompt-b/quality-summary.csv
```

Esperado: la tabla con las tres métricas, `delta` y «Prompts que difieren: chat-system»; la etiqueta `production` intacta en
Langfuse; las trazas de cada ejecución con su versión. Repetir con `--prompt chat-system=99` → error antes de consultar al
asistente. Repetir `compare` con un resumen medido con otro juez → advertencia de no comparables.

## S-8. Sin regresión al activar la gestión (FR-028, SC-011)

1. Medir la recuperación con `uv run python -m shoplab_eval retrieval` antes (gestión apagada) y después (gestión encendida y
   versión 1 publicada): mismos números.
2. Con LAB-29 disponible, medir la calidad en ambos estados y comparar con `compare`: sin diferencias atribuibles al prompt
   (el texto es idéntico).
3. `git grep` de una frase distintiva del prompt (por ejemplo, «Eres el asistente de la mesa de ayuda») solo encuentra
   `backend/src/main/resources/prompts/` y los documentos de decisión, nunca código Java ni `application.yml` (SC-001).

## S-9. DEV (cuando se despliegue el cambio de `shoplab-infra`)

Tras `make up`, el backend de `dev` publica los cuatro prompts en el Langfuse del clúster, una pregunta aparece como traza con su
versión y mover la etiqueta en la interfaz del clúster cambia la respuesta en ≤ 1 minuto. Si se configura el token, repetir S-6
contra el ingress.
