# Quickstart: validar las trazas de LLM con Langfuse (LAB-64)

Guía de validación de extremo a extremo; no contiene código de implementación. Contratos:
[contracts/trace-schema.md](contracts/trace-schema.md) y [contracts/configuration.md](contracts/configuration.md).

**Requisitos**: Docker, Java 21 y Maven, el perfil `core` arriba con documentos indexados (LAB-22), unos 6 GB de RAM libres
para el perfil `llmops` (cifra a confirmar en la verificación S-4). Comandos desde la raíz de `shoplab-helpdesk`.

## 1. Langfuse en local (US1)

```bash
docker compose -f infra/docker-compose.yml --profile llmops config -q            # el compose es válido
docker compose -f infra/docker-compose.yml up -d --wait                          # solo core: sin langfuse-*
docker compose -f infra/docker-compose.yml ps --services | grep -c langfuse      # esperado: 0
docker compose -f infra/docker-compose.yml --profile llmops up -d --wait         # + Langfuse
docker compose -f infra/docker-compose.yml --profile llmops ps                   # los seis langfuse-* en healthy
```

Abrir `http://localhost:3001` e iniciar sesión con `admin@shoplab.local` / `change-me-local-only`.
**Esperado**: ya existen la organización y el proyecto; *Settings → API Keys* muestra la clave pública `pk-lf-shoplab-local`.
Reiniciar (`docker compose … --profile llmops restart`) y comprobar que el proyecto y las trazas siguen ahí.
Con `--profile observability` también activo, Grafana (3000) y Langfuse (3001) conviven.

## 2. Una traza por turno (US2)

```bash
export SHOPLAB_CHAT_TRACING_ENABLED=true
export SHOPLAB_CHAT_TRACING_PUBLIC_KEY=pk-lf-shoplab-local
export SHOPLAB_CHAT_TRACING_SECRET_KEY=sk-lf-shoplab-local
(cd backend && mvn spring-boot:run)        # en otra terminal

curl -N -H 'Content-Type: application/json' -H 'X-Request-Id: demo-0001' \
  -d '{"message":"¿Cuál es la política de devoluciones?"}' http://localhost:8080/api/chat
```

En Langfuse (*Tracing*), abrir la traza `chat-turn`. **Esperado** (FR-009 a FR-017):
- Entrada = la pregunta; salida = la respuesta; metadato `request_id=demo-0001`.
- Paso `retrieval` con los fragmentos entregados (fuente, título, sección, puntuación) en el orden de las citas.
- Paso `generation` con el prompt completo (sistema, historial, contexto), el modelo, tokens de entrada/salida/total y
  coste `0` (modelo local), y el tiempo hasta el primer token.
- Duración total y de cada paso visibles.

Comprobar la identidad estable (FR-020):

```bash
TRACE_ID=$(printf 'demo-0001' | sha256sum | cut -c1-32)
curl -s -u pk-lf-shoplab-local:sk-lf-shoplab-local \
  "http://localhost:3001/api/public/v2/observations?traceId=$TRACE_ID&fields=core,basic,io,usage,model,time,metadata" | head -c 600
```

**Esperado**: la API (Langfuse v4 solo ofrece `v2/observations`; `GET /api/public/traces` no está disponible en modo
`events_only`) devuelve las observaciones con ese `traceId`. Con memoria, dos preguntas con el mismo `conversationId` aparecen en una
misma sesión (*Sessions*).

## 3. El chat no depende de las trazas (US4)

```bash
docker compose -f infra/docker-compose.yml --profile llmops stop langfuse-web langfuse-worker
curl -N -H 'Content-Type: application/json' -d '{"message":"¿Cuál es la política de devoluciones?"}' http://localhost:8080/api/chat
```

**Esperado**: respuesta completa, sin error y a la velocidad habitual; en el log del backend, a lo sumo un aviso
«no se pudo enviar la traza» **sin** la pregunta ni la respuesta. Volver a levantar Langfuse y repetir: la traza nueva
aparece sin reiniciar el backend. Con `SHOPLAB_CHAT_TRACING_ENABLED` sin definir, el backend no abre ninguna conexión hacia el
puerto 3001.

## 4. Turnos especiales (US5)

- Pregunta sin documentos relevantes (`no-context-mode: fixed-message`): traza con `result=no_context`, `retrieval` vacío y
  **sin** paso `generation`.
- Cortar el `curl` a mitad de la respuesta: traza con `result=cancelled` y el texto parcial.
- Detener Ollama y preguntar: traza con `result=error`, el código y `failed_stage=generation`.

## 5. Pruebas automáticas

```bash
mvn -f backend/pom.xml test -Dskip.srcclr=true --no-transfer-progress        # incluye trazas, arquitectura y BlockHound
mvn -f backend/pom.xml verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # cobertura ≥ 80 %
mvn -f backend/pom.xml checkstyle:check -Dskip.srcclr=true --no-transfer-progress
```

Las dependencias nuevas de OpenTelemetry requieren una primera resolución con red (el repositorio local no las tiene; la
guía de la constitución usa `-o` solo cuando ya están descargadas).

## 6. Langfuse en el clúster DEV (US3)

En `shoplab-infra`, con el clúster levantado (`make up`) y el secreto `langfuse` cargado:

```bash
kubectl -n argocd get application shoplab-dev                       # Synced / Healthy
kubectl -n shoplab-dev get pods -l app.kubernetes.io/part-of=shoplab | grep langfuse      # todos Running y Ready
kubectl -n shoplab-dev port-forward svc/langfuse-web 3001:3000 &
```

Hacer una pregunta al chat de DEV (`http://shoplab.localtest.me`) y abrir `http://localhost:3001`. **Esperado**: la traza del
turno aparece en menos de 1 minuto con el entorno `dev`, sin editar configuración a mano. Tras `destroy` y `apply`,
Langfuse vuelve con proyecto y claves (las trazas anteriores no).

## Criterios de salida

| Comprobación | Spec |
|---|---|
| Sin perfil, mismos contenedores que antes | SC-005 |
| Primera traza en menos de 5 minutos desde `up` | SC-001 |
| 20 preguntas → 20 trazas completas | SC-002 |
| Langfuse caído → 100 % de respuestas correctas; primer token < 5 % más lento con Langfuse activo | SC-004 |
| Traza de DEV en menos de 1 minuto; recreación del clúster sin pasos manuales | SC-006, SC-007 |
| Sin secretos ni `latest` añadidos | SC-008 |
