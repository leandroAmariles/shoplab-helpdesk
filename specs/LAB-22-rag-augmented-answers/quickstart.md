# Quickstart: validar las respuestas aumentadas con los documentos

Guía de validación; el contrato está en [contracts/rag-answers.md](contracts/rag-answers.md) y los tipos en
[data-model.md](data-model.md).

## Requisitos

- Java 21 y Maven; red para descargar `spring-ai-rag` la primera vez.
- Docker (Testcontainers para las pruebas de integración).
- Para la prueba manual: `docker compose -f infra/docker-compose.yml up` (PostgreSQL, Ollama con `llama3.2:3b` y `bge-m3`,
  `ingest`) y la base de conocimiento indexada (LAB-20/21: ingerir `data/kb` y esperar a que los documentos estén
  `INDEXED`).

## 1. Pruebas automáticas (deterministas, sin Ollama)

```bash
mvn test -pl backend -Dskip.srcclr=true --no-transfer-progress
mvn verify -pl backend -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # cobertura >= 80 %
mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress
```

Resultado esperado: verde, incluidos `ModularityTests`, `ModuleArchitectureTests`, `ReactiveArchitectureTests`, las
pruebas de LAB-12 sin modificar su contrato y `RagAnsweringIntegrationTests`:

| Escenario | Esperado |
|---|---|
| Documento indexado + pregunta sobre su contenido | la petición enviada al stub de Ollama contiene el fragmento y el prompt fundamentado |
| Base vacía | la petición contiene la plantilla de contexto vacío; respuesta SSE normal |
| Más candidatos que `top-k` | exactamente `top-k` fragmentos en el prompt |
| Umbral que ningún fragmento alcanza | plantilla de contexto vacío |
| Búsqueda caída | `503` `SLHD_CHAT_00004`; Ollama no recibe petición |
| Mensaje en blanco | `400` `SLHD_CHAT_00001`; no se llama a embeddings ni a la base |

## 2. Prueba manual con Ollama real

```bash
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"message":"¿Cuántos días tengo para devolver un producto?"}'
```

- **SC-001**: lanzar 10 preguntas cuyo dato está en `data/kb` (devoluciones, pagos en cuotas, boleto, privacidad…); anotar
  en esta carpeta (`validation.md`) pregunta, respuesta y si el dato coincide con el documento. Objetivo: ≥ 8 correctas.
- **SC-002**: lanzar 5 preguntas sin respuesta en el corpus («¿Cuál es la capital de Francia?», «¿Venden el modelo X999?»).
  Objetivo: las 5 reconocen que no tienen la información.
- **SC-004**: medir el primer token con el modelo ya cargado (`shoplab_chat_first_token_seconds`), objetivo < 5 s.

## 3. Cambios de configuración

```bash
SHOPLAB_CHAT_RAG_TOP_K=2 mvn -pl backend spring-boot:run     # el prompt lleva 2 fragmentos como máximo
SHOPLAB_CHAT_RAG_TOP_K=0 mvn -pl backend spring-boot:run     # el arranque falla nombrando la propiedad y el rango 1..20
```

## 4. Observabilidad

`GET /actuator/prometheus` debe mostrar `shoplab_chat_context_retrieval_seconds_*{outcome="with_context"|"no_context"|"failed"}`
y `shoplab_chat_context_fragments_*`. Los logs no contienen el texto de la pregunta ni de los fragmentos.
