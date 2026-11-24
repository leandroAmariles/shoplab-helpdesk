# Contratos Spring Cloud Contract: `chat → retrieval` y `eval → chat`

Este documento especifica los contratos YAML DSL, las clases base del productor (`WEBTESTCLIENT`), la configuración del plugin Maven y el patrón de pruebas del consumidor con Stub Runner.

---

## 1. Contratos del Productor `retrieval` (`backend/src/test/resources/contracts/retrieval/`)

### `shouldReturnKnowledgeSearchResults.yml`

```yaml
name: should_return_knowledge_search_results
description: Returns ranked knowledge base chunks for a valid search query
request:
  method: GET
  urlPath: /api/knowledge/search
  queryParameters:
    query: politicas de devolucion
    limit: 5
response:
  status: 200
  headers:
    Content-Type: application/json
  body:
    model: bge-m3
    results:
      - source: returns-policy.md
        title: Política de devoluciones
        section: Plazos
        position: 0
        score: 0.91
        text: El plazo estándar de devolución es de 30 días naturales desde la entrega.
  matchers:
    body:
      - path: $.model
        type: by_regex
        value: ".+"
      - path: $.results
        type: by_type
        minOccurrence: 1
      - path: $.results[0].score
        type: by_regex
        value: "[0-9]+(\\.[0-9]+)?"
```

### `shouldReturnEmptyKnowledgeSearchResults.yml`

```yaml
name: should_return_empty_knowledge_search_results
description: Returns empty results list when no chunks match the query
request:
  method: GET
  urlPath: /api/knowledge/search
  queryParameters:
    query: consulta sin coincidencias
    limit: 5
response:
  status: 200
  headers:
    Content-Type: application/json
  body:
    model: bge-m3
    results: []
```

### `shouldRejectBlankQueryWith422.yml`

```yaml
name: should_reject_blank_query_with_422
description: Rejects missing or blank query with typed error SLHD_RETR_00009 (422 Unprocessable Content)
request:
  method: GET
  urlPath: /api/knowledge/search
  queryParameters:
    query: "   "
response:
  status: 422
  headers:
    Content-Type: application/json
  body:
    code: SLHD_RETR_00009
    message: The search query must not be blank
```

---

## 2. Contratos del Productor `chat` (`backend/src/test/resources/contracts/chat/`)

### `shouldReturnAssistantSettings.yml`

```yaml
name: should_return_assistant_settings
description: Returns current retrieval parameters and served prompt versions (including nullable version for DEFAULT)
request:
  method: GET
  urlPath: /api/assistant/settings
response:
  status: 200
  headers:
    Content-Type: application/json
  body:
    topK: 5
    similarityThreshold: 0.45
    maxContextCharacters: 6000
    prompts:
      - name: chat-system
        version: 3
        source: LANGFUSE
      - name: chat-small-talk
        version: null
        source: DEFAULT
  matchers:
    body:
      - path: $.topK
        type: by_regex
        value: "[1-9][0-9]*"
      - path: $.similarityThreshold
        type: by_regex
        value: "[0-9]+(\\.[0-9]+)?"
      - path: $.maxContextCharacters
        type: by_regex
        value: "[1-9][0-9]*"
      - path: $.prompts
        type: by_type
        minOccurrence: 2
```

---

## 3. Configuración Maven del Productor (`backend/pom.xml`)

- **BOM**: `org.springframework.cloud:spring-cloud-dependencies:2025.1.3` (compatible con Spring Boot `4.1.2`).
- **Dependencias de test**:
  - `org.springframework.cloud:spring-cloud-starter-contract-verifier` (`test`)
  - `org.springframework.cloud:spring-cloud-starter-contract-stub-runner` (`test`)
  - `io.rest-assured:spring-web-test-client` (gestionado por Spring Cloud Contract para `WEBTESTCLIENT`)
- **Plugin `spring-cloud-contract-maven-plugin`**:
  - `<testMode>WEBTESTCLIENT</testMode>`
  - `<contractsDirectory>${project.basedir}/src/test/resources/contracts</contractsDirectory>`
  - `<baseClassMappings>`:
    - `.*retrieval.*` → `com.shoplab.helpdesk.retrieval.infrastructure.in.rest.RetrievalContractBase`
    - `.*chat.*` → `com.shoplab.helpdesk.chat.infrastructure.in.rest.ChatContractBase`
- **Exclusión en Checkstyle y PMD**: excluir los archivos generados bajo `target/generated-test-sources/contracts` para no fallar `mvn verify` por código generado por el plugin.

---

## 4. Patrón de Clases Base del Productor (`WEBTESTCLIENT`)

- **`RetrievalContractBase`**:
  - Instancia `KnowledgeSearchController` con un `SearchChunksServicePort` mockeado con Mockito para devolver las respuestas según la query (`"consulta sin coincidencias"` → `[]`, `"   "` → `Mono.error(new InvalidSearchQueryException(...))` o caso real del use case que lanza `HelpdeskException(ResponseCodeEnum.SEARCH_QUERY_INVALID)`, otras → chunk de ejemplo).
  - Registra `GlobalExceptionHandler` mediante `WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build()` y lo pasa a `RestAssuredWebTestClient.webTestClient(...)`.
- **`ChatContractBase`**:
  - Instancia `AssistantSettingsController` con `GetAssistantSettingsServicePort` mockeado devolviendo `topK=5`, `similarityThreshold=0.45`, `maxContextCharacters=6000` y los prompts `chat-system` (`LANGFUSE`, `3`) y `chat-small-talk` (`DEFAULT`, `null`).
  - Configura `RestAssuredWebTestClient.webTestClient(...)` con `GlobalExceptionHandler`.

---

## 5. Patrón de Pruebas del Consumidor (`StubRunner`)

- **`ChatRetrievalContractConsumerTest`** (`chat` consumiendo stubs de `retrieval`):
  - Levanta Stub Runner en puerto dinámico (`StubRunnerExtension` o `@AutoConfigureStubRunner` con `ids = "com.shoplab:helpdesk-backend:+:stubs:0"`, `stubsMode = LOCAL` por defecto o `REMOTE` apuntando a GitHub Packages).
  - Crea `HttpKnowledgeSearchClient` apuntando a la URL del stub y verifica con `StepVerifier` los 3 escenarios (`200` con hits, `200` vacío, `422` mapeado a `KnowledgeRetrievalException`).
- **`EvalChatContractConsumerTest`** (`eval` consumiendo stubs de `chat`):
  - Levanta Stub Runner con el mismo identificador de stubs.
  - Crea `HttpAssistantSettingsClient` apuntando a la URL del stub y verifica con `StepVerifier` la deserialización completa de `AssistantSettingsSnapshot`, incluyendo `version = null` para `source = DEFAULT`.
