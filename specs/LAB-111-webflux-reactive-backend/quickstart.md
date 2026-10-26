# Quickstart: Backend reactivo con Spring WebFlux

**Feature**: LAB-111 | Guía de validación de punta a punta. Contratos: [compatibility.md](contracts/compatibility.md) y
[blocking-isolation.md](contracts/blocking-isolation.md). El comportamiento del chat es el de
[LAB-12](../LAB-12-chat-streaming-sse/quickstart.md): aquí solo se repite lo que demuestra que **nada cambió**.

## Requisitos previos

- JDK 21 y el Maven Wrapper de `backend/`.
- Docker con el perfil `core` de `infra/docker-compose.yml` (Ollama con `llama3.2:3b`).
- `curl` (en Windows, `curl.exe`; ver la nota de UTF-8 del quickstart de LAB-12).

## 0. Antes de migrar (rama base, MVC): línea de referencia

Se hace una sola vez, en el commit anterior al cambio de starter:

```bash
cd backend && ./mvnw spring-boot:run &
curl -s http://localhost:8080/actuator/prometheus | sed -E 's/ [0-9eE+.-]+$//' | sort -u \
  > ../specs/LAB-111-webflux-reactive-backend/metrics-baseline.txt
```

Y se guarda el cuerpo SSE crudo de una respuesta con la fixture de Ollama como `chat/golden-stream.txt` (ver T001).

## 1. Levantar el backend migrado

```bash
docker compose -f infra/docker-compose.yml up -d
cd backend && ./mvnw spring-boot:run
```

**Esperado**: en el log de arranque, `Netty started on port 8080` (no `Tomcat started`).

## 2. El contrato no cambió (US1)

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"message":"Explícame en tres frases cómo devolver un pedido"}'
```

**Esperado**: `event:token` / `data:{"content":"..."}` progresivos y `event:done` con `{"fragments":N}`, igual que en
LAB-12. Errores:

```bash
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":"   "}'   # 400 SLHD_CHAT_00001
curl -i -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"message":'          # 400 SLHD_GENE_00002
docker compose -f infra/docker-compose.yml stop ollama
curl -i -X POST http://localhost:8080/api/chat -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" -d '{"message":"Hola"}'                                                # 503 SLHD_CHAT_00002, JSON
docker compose -f infra/docker-compose.yml start ollama
```

## 3. Primer token < 2 s (US1)

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  curl -N -s -o /dev/null -w "%{time_starttransfer}\n" -X POST http://localhost:8080/api/chat \
    -H "Content-Type: application/json" -d '{"message":"Hola"}'
done
```

**Esperado**: al menos 9 de 10 valores por debajo de 2 s con el modelo caliente (SC-002).

## 4. Cancelar cancela al modelo (US2)

```bash
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"message":"Escribe un texto muy largo sobre la historia del comercio electrónico"}'   # Ctrl+C a los 2 s
curl -s http://localhost:8080/actuator/prometheus | grep 'shoplab_chat_response_seconds_count'
```

**Esperado**: `outcome="cancelled"` aumenta en 1, la carga de Ollama baja en menos de 2 s y una petición inmediata
posterior cumple el paso 3.

## 5. Hilos acotados con muchos streams (SC-005)

```bash
for i in $(seq 1 50); do
  curl -N -s -o /dev/null -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
    -d '{"message":"Cuéntame algo largo"}' &
done
sleep 3
curl -s http://localhost:8080/actuator/prometheus | grep -E '^jvm_threads_live_threads'
wait
```

**Esperado**: `jvm_threads_live_threads` no crece de forma proporcional a los 50 streams (muy por debajo de 50 hilos
adicionales); cada `curl` termina con su propio `done`.

## 6. Observabilidad intacta (US4)

```bash
curl -s http://localhost:8080/actuator/prometheus | sed -E 's/ [0-9eE+.-]+$//' | sort -u > /tmp/metrics-now.txt
diff ../specs/LAB-111-webflux-reactive-backend/metrics-baseline.txt /tmp/metrics-now.txt
```

**Esperado**: solo las diferencias listadas en «Diferencias aceptadas» de
[compatibility.md](contracts/compatibility.md). Abrir los dashboards de Grafana de LAB-102 y comprobar que muestran
datos sin editar consultas.

## 7. Suite automatizada con BlockHound (US3)

```bash
cd backend && ./mvnw verify
```

**Esperado**: todo en verde sin Ollama real; `BlockHoundDetectionTests` demuestra que un bloqueo en `parallel` falla y
que el mismo código vía `BlockingCalls` pasa; `ReactiveArchitectureTests` en verde; `ModularityTests` con los 5
módulos; Checkstyle y PMD sin violaciones; cobertura de líneas ≥ 80 %.

Prueba manual de que la guardia funciona (no se commitea): añadir `Thread.sleep(50)` dentro de un `doOnNext` del caso
de uso y ejecutar `ChatStreamingIntegrationTests`; debe fallar con `BlockingOperationError`.

## 8. ADR (US5)

Abrir `docs/adr/0011-web-reactiva-persistencia-jdbc.md` y comprobar: plantilla respetada, fila en `docs/adr/README.md`,
y las tres secciones pedidas (por qué WebFlux, por qué no R2DBC, alternativa MVC con hilos virtuales).
