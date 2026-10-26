# Quickstart: Respuesta del chat en streaming (SSE)

**Feature**: LAB-12 | Guía de validación de punta a punta. Contrato: [contracts/chat-stream.md](contracts/chat-stream.md).

## Requisitos previos

- JDK 21 y el Maven Wrapper de `backend/`.
- Docker con el perfil `core` de `infra/docker-compose.yml` (Ollama con `llama3.2:3b`; ver `README.md`).
- `curl` (en Windows, `curl.exe`).

## 1. Levantar Ollama y el backend

```bash
docker compose -f infra/docker-compose.yml up -d        # espera a que ollama esté healthy
cd backend && ./mvnw spring-boot:run
```

`ollama` pasa a `healthy` cuando el modelo de chat ya está descargado. Calienta el modelo una vez para medir el
criterio de latencia con el modelo cargado:

```bash
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":"Hola"}' > /dev/null
```

## 2. Camino feliz: respuesta token a token (US1)

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"message":"Explícame en tres frases cómo devolver un pedido"}'
```

**Esperado**: líneas `event: token` / `data: {"content":"..."}` apareciendo progresivamente y, al final,
`event: done` con `{"fragments":N}`; la conexión se cierra sola.

> **Windows**: `curl.exe` codifica los argumentos `-d` en la página de códigos local (cp1252), no en UTF-8, y el
> servidor rechaza con `400 SLHD_GENE_00002` un cuerpo con tildes o eñes en esa codificación. Escribe el JSON en un
> archivo UTF-8 y envíalo con `--data-binary @mensaje.json`, o usa solo caracteres ASCII en el mensaje.

## 3. Primer token en menos de 2 s (US2)

```bash
curl -N -s -o /dev/null -w "primer byte: %{time_starttransfer}s\n" -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -d '{"message":"Hola"}'
```

**Esperado**: `time_starttransfer` < 2 s con el modelo caliente (repetir 10 veces; SC-001 pide el 95 %). Contraste con
la métrica del servidor:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep shoplab_chat_first_token_seconds
```

## 4. Errores (US3)

```bash
# Mensaje en blanco -> 400 SLHD_CHAT_00001, sin llamar al modelo
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":"   "}'

# JSON mal formado -> 400 SLHD_GENE_00002
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":'

# Modelo caído -> 503 SLHD_CHAT_00002
docker compose -f infra/docker-compose.yml stop ollama
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":"Hola"}'
docker compose -f infra/docker-compose.yml start ollama
```

**Esperado**: cuerpos `application/json` con `code` y `message`; el 503 llega aunque la petición pida
`Accept: text/event-stream`.

## 5. Cancelación (US4)

Lanza una respuesta larga y pulsa `Ctrl+C` a mitad:

```bash
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"message":"Escribe un texto muy largo sobre la historia del comercio electrónico"}'
```

**Esperado**: la generación se detiene (la CPU/GPU de Ollama baja) y
`shoplab_chat_response_seconds_count{outcome="cancelled"}` aumenta en `/actuator/prometheus`. Una petición nueva
inmediata sigue cumpliendo el paso 3.

## 6. Suite automatizada (sin Ollama real)

```bash
cd backend && ./mvnw verify
```

**Esperado**: todas las pruebas en verde con WireMock simulando Ollama, `ModularityTests` con los 5 módulos
(`chat`, `retrieval`, `tools`, `tickets`, `shared`), Checkstyle y PMD sin violaciones y cobertura ≥ 80 %.
