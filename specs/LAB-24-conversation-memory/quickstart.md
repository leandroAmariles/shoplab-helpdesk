# Quickstart: validar la memoria de la conversación

Guía de validación; el contrato está en [contracts/chat-request.md](contracts/chat-request.md) y los tipos en
[data-model.md](data-model.md).

## Requisitos

- Java 21 y Maven; Node ^22.22.3 o ^24.15.0; Docker (Testcontainers).
- Para la prueba manual: `docker compose -f infra/docker-compose.yml up` (PostgreSQL, Ollama con `llama3.2:3b` y `bge-m3`,
  `ingest`) y `data/kb` indexada.

## 1. Pruebas automáticas

```bash
cd backend && ./mvnw test -Dskip.srcclr=true --no-transfer-progress          # BlockHound activo en toda la suite
./mvnw checkstyle:check -Dskip.srcclr=true --no-transfer-progress
./mvnw verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # cobertura JaCoCo ≥ 80 %

cd frontend && npm test -- --watch=false && npm run lint
```

Deben pasar sin cambios las pruebas de contrato de LAB-12, LAB-22 y LAB-23 (SC-007).

## 2. Seguimiento desde la terminal

```bash
ID=$(uuidgen | tr 'A-Z' 'a-z')
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d "{\"message\":\"Cuánto tarda el envío estándar\",\"conversationId\":\"$ID\"}"
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d "{\"message\":\"¿y si es internacional?\",\"conversationId\":\"$ID\"}"
```

Esperado: la segunda respuesta habla de envíos internacionales y sus `sources` son del documento de envíos (SC-001/002).

## 3. Aislamiento, validación y sin memoria

```bash
# Otro identificador, mismo seguimiento: no entiende el tema anterior
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d "{\"message\":\"¿y si es internacional?\",\"conversationId\":\"$(uuidgen | tr 'A-Z' 'a-z')\"}"
# Identificador inválido: 400 SLHD_CHAT_00005
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"message":"hola","conversationId":"no-es-un-uuid"}'
# Sin identificador: igual que antes de LAB-24
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":"hola"}'
```

## 4. Persistencia tras reinicio

Reiniciar el backend (`docker compose restart backend`) y repetir el segundo `curl` del apartado 2 con el mismo `ID`: el
seguimiento sigue entendiéndose (SC-004). Comprobar la tabla:

```sql
SELECT conversation_id, left(question, 40), created_at FROM chat.conversation_turn ORDER BY id DESC LIMIT 5;
```

## 5. Pantalla de chat

`npm start` en `frontend/`, abrir `http://localhost:4200/chat`: hacer la pregunta y el seguimiento; pulsar «Nueva
conversación» y repetir el seguimiento (ya no se entiende por el tema anterior). Recargar crea una conversación nueva.

## 6. Observabilidad

`curl -s localhost:8080/actuator/prometheus | grep shoplab_chat_history` y `shoplab_chat_memory_failures`: sin etiquetas
con identificadores; los registros no contienen texto de mensajes (SC-008).

## 7. Registro de resultados

Anotar en `validation.md` (como en LAB-22/LAB-23): los 10 pares pregunta + seguimiento de SC-001 con la respuesta
obtenida, el resultado de BlockHound y las cifras de cobertura.
