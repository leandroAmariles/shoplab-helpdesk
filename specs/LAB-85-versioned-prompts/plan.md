# Implementation Plan: Prompts versionados en Langfuse

**Branch**: `feature/LAB-85-versioned-prompts` | **Date**: 2026-11-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-85-versioned-prompts/spec.md`

## Summary

El módulo `chat` del backend deja de llevar el texto de sus cuatro prompts (sistema, cortesía, plantilla con contexto y
plantilla sin contexto) en código y configuración: los pide a **Langfuse** (API REST de prompts, etiqueta `production`) y los
guarda en una **caché en memoria que se refresca en segundo plano**. El camino de cada pregunta **solo lee memoria**, así que
Langfuse lento o caído no puede retrasar ni romper un turno. El valor por defecto de cada prompt pasa a archivos de recursos
(`backend/src/main/resources/prompts/*.txt`), que son además la fuente de la publicación inicial.

Cinco decisiones sostienen el plan (detalle y alternativas en [research.md](research.md)):

1. **API REST de Langfuse con `WebClient`, sin SDK nuevo** (R1). El backend ya es WebFlux y ya habla con Langfuse por
   OTLP con las mismas claves.
2. **Lectura de memoria en el turno; la red solo en el arranque y en un refresco periódico** (R2, R3). Se conserva siempre la
   última versión válida, aunque haya vencido (FR-008). Cada turno toma una instantánea al empezar (FR-012).
3. **Validación antes de sustituir** (R4): una edición mala en Langfuse se descarta con aviso y el chat sigue con la última
   versión válida o el valor por defecto.
4. **Trazabilidad en dos niveles** (R7): el prompt principal del turno se enlaza con los atributos nativos de Langfuse
   (`langfuse.observation.prompt.name/version`) y los cuatro se registran como metadatos filtrables. Con el valor por defecto
   no hay enlace y la traza lo marca.
5. **Fijar versiones solo para evaluar** (R8): la petición del chat acepta una cabecera `X-Prompt-Versions` únicamente si
   la instancia tiene configurado un token de evaluación y la petición lo presenta; en cualquier otro caso la rechaza con 403
   (nunca la ignora en silencio, para que una evaluación mal configurada no mida con los prompts equivocados).

El trabajo se divide en dos fases con una dependencia entre ellas:

- **Fase A (historias 1 a 4, `backend/` + infra)**: independiente de LAB-29. Es la entrega principal.
- **Fase B (historia 5, `eval/`)**: añade `--prompt` al comando `quality` de LAB-29 y un subcomando `compare`. LAB-29 ya está
  fusionada en `main` (PR #132), así que no hay bloqueo; va después de la fase A porque necesita la cabecera del backend.

La publicación inicial en local y en DEV la hace el propio backend al arrancar con `publish-missing=true`: crea solo los
prompts que no existen (R9).

## Technical Context

**Language/Version**: Java 21, Spring Boot 4.x, Spring AI 2.0 en `backend/` (módulo `chat`); Python 3.12 en `eval/` (fase B).
YAML de kustomize en `../shoplab-infra` para DEV

**Primary Dependencies**: ninguna nueva en el backend (`spring-boot-starter-webflux` ya presente; `WebClient` para la API
REST; WireMock ya está en las pruebas). En `eval/`, solo biblioteca estándar y lo que LAB-29 ya trae

**Storage**: ninguno propio. Langfuse guarda las versiones de los prompts; el backend las guarda solo en memoria

**Testing**: JUnit 5 + Mockito + WireMock para el cliente de Langfuse y la caché con reloj y ejecutor falsos; pruebas del
adaptador del gateway con prompts inyectados; `pytest` con servidor HTTP local en `eval/`. Ninguna prueba usa un Langfuse ni un
modelo reales. JaCoCo ≥ 80 % (los paquetes `config`, `model` y `exception` siguen excluidos, así que la lógica va fuera de
ellos)

**Target Platform**: backend en el host con Langfuse en el perfil `llmops` de compose (local) y backend en el clúster `dev` con
el Langfuse de `shoplab-infra` (DEV, que el equipo usa como entorno local)

**Project Type**: web-service (monolito modular) + herramienta de línea de comandos + configuración de despliegue

**Performance Goals**: 0 llamadas de red en el camino del turno (SC-006: primer token < 5 % por encima de usar los valores
por defecto); refresco de los cuatro prompts en paralelo, ≤ 1 petición por prompt y por intervalo

**Constraints**: ninguna falla de Langfuse llega al cliente del chat; el texto de los prompts no sale en avisos ni en métricas;
claves y token solo del entorno y fuera de `toString`; líneas ≤ 121; identificadores en inglés y documentación en español;
sin cambiar el texto de ningún prompt (FR-028)

**Scale/Scope**: 4 prompts; 1 petición de refresco por prompt cada 60 s por instancia (por omisión); versiones de evaluación
en una caché acotada

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: todo queda en el módulo `chat`; no hay tablas ni dependencias nuevas entre módulos. La Modulith ya verifica que `chat` no use internos de otros. |
| II. Hexagonal | Cumple: el caso de uso y el adaptador del gateway dependen de un puerto de salida `PromptCatalogPort`; el cliente de Langfuse, la caché y la lectura de recursos son infraestructura. El controlador solo construye el comando (traduce la cabecera a `PromptOverrides`) y llama al puerto de entrada. Los enums y constantes viven en `domain/model`. |
| III. Inglés y errores tipados | Cumple: `PromptOverrideException` (versión inexistente o inválida) y `PromptOverrideForbiddenException` (cabecera sin permiso) tienen código en `ResponseCodeEnum` y se mapean solo en `GlobalExceptionHandler`. Los fallos de refresco **no** son excepciones hacia el cliente: se registran y se cae al reemplazo. |
| IV. Pruebas con cobertura | Cumple: cada regla (validación, reemplazo, vigencia, instantánea, cabecera) tiene prueba determinista con WireMock y relojes falsos. La lógica está fuera de `config/` y `model/` para contar en la cobertura. |
| V. RAG guiado por evaluación | Cumple con condición: esta historia **no cambia el texto** de ningún prompt (FR-028), así que no degrada métricas por sí misma; se comprueba con las métricas de LAB-28 antes y después (S-8). Entrega la herramienta que la constitución exige para el *siguiente* cambio de prompt (fase B). LAB-29 ya está fusionada: la comprobación de calidad antes y después se hace con `quality` (S-8). |
| VI. Observabilidad y datos seguros | Cumple: la traza gana el nombre y la versión de cada prompt; el aviso por prompt inválido nombra prompt y versión, nunca el texto; claves y token vienen del entorno y `PromptProperties.toString` no los imprime; la cabecera de evaluación exige token. Se añade una métrica de refrescos por resultado. |
| VII. Simplicidad y ADR | Cumple con ADR: Langfuse como fuente, caché con refresco en segundo plano y cabecera de evaluación son decisiones de arquitectura → **ADR-0027** (LAB-29 reservó el 0026 y no llegó a crearlo; este ADR usa el 0027). Se descartan SDK propio, caché con consulta por turno, un servicio de prompts aparte y editar los prompts desde ShopLab. |

**Gate**: PASA. Desviaciones declaradas en Complexity Tracking (cambio de configuración incompatible, superficie nueva en
`/api/chat` protegida por token, cambios en `shoplab-infra`).

**Re-evaluación tras el diseño**: PASA. El diseño no añade servicios, tablas ni dependencias. La superficie nueva
(`X-Prompt-Versions`) está apagada por omisión y sin token configurado el endpoint responde 403 a quien la envíe.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-85-versioned-prompts/
├── plan.md                      # Este archivo
├── research.md                  # Fase 0: decisiones y alternativas (R1 a R13)
├── data-model.md                # Fase 1: tipos, estados y reglas de validación
├── quickstart.md                # Fase 1: verificación de extremo a extremo
├── contracts/
│   ├── langfuse-prompts-api.md  # Lo que el backend consume y publica en Langfuse
│   ├── prompt-override.md       # Cabecera de evaluación de POST /api/chat
│   ├── trace-prompts.md         # Atributos añadidos a la traza (delta de LAB-64)
│   ├── prompts-config.md        # Propiedades y variables de entorno nuevas y retiradas
│   └── eval-compare.md          # `quality --prompt`, `compare` y columna nueva del CSV (fase B)
├── checklists/requirements.md
├── tasks.md                     # Lo genera /speckit-tasks
└── validation.md                # Resultados de la validación manual (se crea en T002)
```

### Source Code (repository root)

```text
backend/src/main/
├── java/com/shoplab/helpdesk/chat/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── PromptReference.java            # nombre + versión + origen (Langfuse o valor por defecto)
│   │   │   ├── ResolvedPrompt.java             # texto + referencia
│   │   │   ├── PromptOverrides.java            # versiones fijadas para una petición (vacío por omisión)
│   │   │   ├── StreamChatCommand.java          # + promptOverrides
│   │   │   ├── enums/PromptName.java           # los 4 nombres, sus marcadores obligatorios y su recurso por defecto
│   │   │   ├── enums/PromptSource.java         # LANGFUSE | DEFAULT
│   │   │   └── constants/PromptConstants.java  # nombre de la etiqueta, claves de contexto, cabeceras, topes
│   │   └── exception/
│   │       ├── PromptOverrideException.java
│   │       └── PromptOverrideForbiddenException.java
│   ├── application/
│   │   ├── port/out/PromptCatalogPort.java     # current(name) sin bloqueo; pinned(name, version) reactivo
│   │   ├── port/out/TurnTrace.java             # + promptsUsed(...) (método por defecto: no hace nada)
│   │   └── usecase/
│   │       ├── PromptValidator.java            # reglas de FR-004, puras
│   │       └── PromptRenderer.java             # sustituye {clave}; lo comparten el validador y el adaptador, sin Spring
│   ├── config/
│   │   ├── PromptProperties.java               # shoplab.chat.prompts.*
│   │   ├── PromptConfig.java                   # elige catálogo: Langfuse con caché, o solo valores por defecto
│   │   ├── ChatProperties.java                 # - systemPrompt
│   │   ├── SmallTalkProperties.java            # - systemPrompt
│   │   └── RagProperties.java                  # - contextTemplate, emptyContextTemplate (y su validación)
│   └── infrastructure/
│       ├── support/                            # WarnThrottle (movido de tracing) y KeyedWarnThrottle
│       ├── in/rest/
│       │   ├── ChatController.java             # lee la cabecera y la traduce a PromptOverrides
│       │   └── util/PromptOverrideHeader.java  # valida token y formato; sin lógica de negocio
│       └── out/
│           ├── prompts/
│           │   ├── LangfusePromptClient.java   # GET/POST de la API REST de Langfuse (WebClient)
│           │   ├── CachedPromptCatalog.java    # memoria + refresco + reemplazo; implementa PromptCatalogPort
│           │   ├── DefaultPromptCatalog.java   # solo valores por defecto (gestión apagada)
│           │   ├── PromptDefaults.java         # lee prompts/*.txt del classpath
│           │   ├── PromptRefresher.java        # carga inicial acotada y refresco periódico
│           │           ├── PromptPublisher.java        # publish-missing: crea los que no existen
│           └── RetiredPromptVariablesCheck.java # aviso de migración por las variables retiradas
│           ├── gateway/GatewayChatAdapter.java # pide los prompts al catálogo; sin texto de prompt en el código
│           └── tracing/LangfuseChatTrace.java  # + promptsUsed: enlace nativo y metadatos
└── resources/
    ├── application.yml                         # - system-prompt, - small-talk.system-prompt; + prompts.*
    └── prompts/
        ├── chat-system.txt
        ├── chat-small-talk.txt
        ├── chat-context.txt
        └── chat-empty-context.txt

backend/src/test/java/com/shoplab/helpdesk/chat/
├── infrastructure/out/prompts/                 # cliente (WireMock), caché, refresco, publicador, valores por defecto
├── infrastructure/out/gateway/                 # adaptador con prompts del catálogo; instantánea por turno
├── infrastructure/out/tracing/                 # enlace nativo y metadatos
├── infrastructure/in/rest/                     # cabecera: sin token, token malo, formato, versión inexistente
└── application/usecase/PromptValidatorTests.java
   (se actualizan: ChatPropertiesTests, RagPropertiesTests, SmallTalkPropertiesTests, RagConfigurationBindingTests,
    GatewayChatAdapterTests, GatewayChatAdapterTraceTests, PromptDelimitingCustomTemplatesTests, PromptInjectionTests)

eval/                                           # Fase B (LAB-29 ya está en main)
├── src/shoplab_eval/
│   ├── prompt_selection.py                     # puro: analiza `--prompt NAME=VERSION|LABEL`, resuelve y valida
│   ├── prompt_client.py                        # Langfuse REST: etiqueta → versión; comprueba que existe
│   ├── chat_client.py                          # LAB-29: + cabeceras de versión y token
│   ├── quality_report.py                       # LAB-29: + columna `prompt_versions` en el resumen
│   ├── compare.py                              # puro: diferencias por métrica y advertencias de comparabilidad
│   └── __main__.py                             # LAB-29: + `--prompt` en `quality` y subcomando `compare`
└── tests/                                      # una prueba por regla; sin red salvo servidor local

docs/
├── adr/0027-prompts-versionados-en-langfuse.md
├── chat-streaming.md                           # sustituye lo dicho del prompt de sistema; guía de editar, probar, promover y revertir (FR-026)
└── adr/README.md                               # índice

infra/                                          # compose: nada nuevo (el backend corre en el host)
../shoplab-infra/k8s/overlays/dev/              # variables del backend: prompts habilitados, URL, publish-missing, token de evaluación
```

**Structure Decision**: se queda en el módulo `chat` (no se crea un módulo `prompts`: solo `chat` los usa y la Constitución
pide no extraer antes de necesitarlo). Los puertos y la validación van en `application`, y todo lo que habla con Langfuse, el
classpath o el reloj va en `infrastructure/out/prompts`. En `eval/` se añaden módulos puros (`prompt_selection`, `compare`) con
la red tras `prompt_client`, siguiendo la separación que LAB-29 ya usa.

## Complexity Tracking

| Desviación | Por qué hace falta | Alternativa más simple descartada porque |
|---|---|---|
| Se retiran las propiedades `system-prompt`, `small-talk.system-prompt`, `rag.context-template` y `rag.empty-context-template` y sus variables `SHOPLAB_CHAT_*_PROMPT` (cambio de configuración incompatible) | FR-002 exige que el código no tenga prompts y una sola copia del texto; dejar las propiedades daría dos fuentes con precedencia ambigua | Conservarlas como «valor por defecto sobrescribible»: obliga a mantener el texto en `application.yml` y a decidir quién gana frente a Langfuse |
| Cabecera `X-Prompt-Versions` con token en `POST /api/chat` | FR-017/FR-018: fijar versiones por ejecución sin mover `production` ni afectar a otros usuarios | Mover la etiqueta durante la evaluación (afecta a todos); una instancia aparte por versión (reinicio y despliegue por cada comparación) |
| Cambios en `../shoplab-infra` (variables del backend en `dev` y el secreto del token) | Los cambios de infraestructura de la historia no se difieren; DEV es el entorno local del equipo | Dejar DEV con los valores por defecto: no cumpliría «funciona en DEV» ni permitiría evaluar allí |
| Fase B depende del comando `quality` de LAB-29 y de la cabecera del backend (fase A) | La evaluación contra dos versiones necesita ambos | Reimplementar la medición aquí: duplica LAB-29 |
