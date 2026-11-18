# Implementation Plan: Conversación natural acotada (saludos y cortesías)

**Branch**: `feature/LAB-114-bounded-small-talk` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-114-bounded-small-talk/spec.md`

## Summary

`StreamChatUseCase` gana una **vía de charla** que se decide antes de leer el historial y antes de recuperar contexto. Un
clasificador determinista y sin dependencias (`SmallTalkClassifier`) normaliza el mensaje y comprueba que **todo** él se
descompone en frases de un léxico cerrado de cortesías (saludo, agradecimiento, despedida, acuse, «¿qué puedes hacer?», en
español e inglés). Si es así, el caso de uso llama a un puerto nuevo (`SmallTalkModelPort`) que genera una respuesta breve
con un prompt propio de alcance (sin datos de la tienda, sin historial, sin herramientas) y la emite como `token` + `done`,
sin búsqueda, sin `sources` y sin cambiar el contrato SSE. Cualquier otro mensaje —pregunta, mixto, ambiguo— sigue el flujo
de LAB-22/23/24 sin cambios de comportamiento.

La defensa frente a inyección se extiende al flujo existente: el adaptador del gateway delimita como datos el mensaje del
cliente (`<mensaje_del_cliente>…</mensaje_del_cliente>`), las preguntas del historial y los fragmentos, y neutraliza en ellos
cualquier secuencia que imite un delimitador o un token de rol (`PromptData`); el prompt de sistema añade las reglas de no
revelar instrucciones ni configuración. Como la vía de charla solo recibe texto de vocabulario cerrado, el único texto libre
que llega a su prompt es una cortesía reconocida.

Se mide con un contador `shoplab.chat.turns{kind}` (`small_talk`, `with_context`, `no_context`), una marca en la traza de
Langfuse y registros sin texto. En `eval/`, `golden.jsonl` pasa a admitir las categorías `small_talk` e `injection` (12 casos
cada una) y 6 casos mixtos en sus temas; el validador mantiene sus reglas sobre los 90+6 casos de conocimiento y añade las de
los casos de conversación. Una prueba del backend lee ese mismo archivo para comprobar que el clasificador y el dataset no se
contradicen (cero falsos positivos).

Decisiones y alternativas en [research.md](research.md); modelo en [data-model.md](data-model.md); contratos en
[contracts/small-talk.md](contracts/small-talk.md) y [contracts/golden-conversation-cases.md](contracts/golden-conversation-cases.md);
validación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Java 21 (backend, módulo `chat`); Python ≥ 3.12 (`eval/`, gestionado con `uv`)

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Spring AI 2.0.2, Spring Modulith, Micrometer, Reactor (todo ya
presente; **sin dependencias nuevas**). `eval/`: `pytest`, `pytest-cov`, `ruff` ya presentes

**Storage**: ninguno nuevo. El turno de charla se guarda en `chat.conversation_turn` (LAB-24) igual que cualquier turno; sin
migraciones

**Testing**: backend: JUnit 5 + Mockito + `StepVerifier`, WireMock (gateway y embeddings), Testcontainers `@PostgresTest`,
ArchUnit/Modulith y BlockHound existentes; `eval/`: pytest. Pruebas deterministas, sin servidor de modelos real (Constitución
IV); la conducta del modelo real se valida a mano con Ollama

**Target Platform**: contenedores Linux; local con `docker compose`; `dev` por GitOps (sin cambios de infraestructura)

**Project Type**: web-service (módulo `chat` del monolito modular) + herramienta de evaluación (`eval/`)

**Performance Goals**: primer token de una charla < 3 s con el modelo cargado (SC-003): sin recuperación, sin historial y con
un prompt de unos cientos de caracteres; la clasificación es CPU puro de microsegundos y se ejecuta en el hilo reactivo sin
bloquear (sin E/S)

**Constraints**: `application` sin Spring AI ni JDBC; `domain/model` sin lógica; el clasificador no llama a ningún modelo ni
al puerto de recuperación; el léxico es código (cerrado), no configuración; ningún texto de mensajes o respuestas en
registros ni métricas; sin cambios en `POST /api/chat`, en los tipos de evento ni en `ResponseCodeEnum`

**Scale/Scope**: ~8 clases nuevas y ~10 modificadas en `chat`, 3 módulos Python tocados en `eval/`, ~30 casos nuevos en
`golden.jsonl`, 1 ADR y la documentación de configuración

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Resultado |
|---|---|---|
| I. Monolito modular | Todo ocurre dentro de `chat`; `chat` sigue sin tocar tablas ni clases internas de `retrieval`. La vía de charla ni siquiera llama a su API publicada | Cumple |
| II. Hexagonal | Clasificador y caso de uso en `application`; el modelo detrás de un puerto de salida nuevo (`SmallTalkModelPort`) y un adaptador en `infrastructure`; el controlador no cambia; constantes y enums en `domain/model` | Cumple |
| III. Identificadores en inglés, errores tipados | Identificadores en inglés; textos al cliente en español; los fallos del modelo reutilizan `ChatModelException` y los códigos existentes; sin nuevos `ResponseCodeEnum` ni `ResponseEntity` | Cumple |
| IV. Test-first y 80 % | Tareas con pruebas antes o con la implementación: clasificador, `PromptData`, caso de uso, adaptador, integración SSE, inyección (≥ 10 casos), dataset ↔ clasificador, validador del golden. Paquetes `config`, `model` y `exception` sin lógica | Cumple |
| V. RAG guiado por evaluación | Los casos de charla, mixtos e inyección entran en el golden (esta historia). El cambio del prompt de sistema y de la forma del prompt (delimitadores) toca la generación: no hay aún evaluación automática de calidad de respuesta (LAB-29/LAB-30, F2). Hasta entonces: métricas de recuperación (LAB-28) antes y después, y validación manual con Ollama con los conjuntos SC-001/SC-002 de LAB-22 | Cumple con condición (registrada en la tarea de validación y en `validation.md`) |
| VI. Observabilidad y manejo seguro de IA | Contador por tipo de turno, marca en la traza, registros con `requestId` y longitudes, nunca el texto; sin secretos; los casos del golden usan marcadores `@shoplab.example` | Cumple |
| VII. Simplicidad y decisiones documentadas | Clasificador por léxico cerrado en lugar de un segundo modelo; sin esquema nuevo; el ADR-0025 recoge la decisión (estrategia de defensa y de enrutamiento) | Cumple |

Gates: sin violaciones; no hay entradas en Complexity Tracking.

**Re-evaluación tras el diseño (Fase 1)**: se mantiene. El diseño no añade tablas, módulos, dependencias ni códigos de error;
la única condición abierta es la del principio V, que depende de herramientas de otras historias (LAB-29/LAB-30).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-114-bounded-small-talk/
├── plan.md                                  # Este archivo
├── research.md                              # Fase 0: decisiones y alternativas
├── data-model.md                            # Fase 1: tipos, configuración y categorías del golden
├── quickstart.md                            # Fase 1: validación de punta a punta
├── contracts/
│   ├── small-talk.md                        # Clasificación, secuencia SSE, prompts, métricas y configuración
│   └── golden-conversation-cases.md         # Categorías y reglas nuevas del dataset
├── checklists/requirements.md               # Calidad de la spec
└── tasks.md                                 # Fase 2 (/speckit-tasks; no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/chat/
├── application/
│   ├── port/out/
│   │   ├── SmallTalkModelPort.java                 # NUEVO: Flux<String> streamReply(String message)
│   │   ├── ChatMetricsPort.java                    # + recordTurn(TurnKind)
│   │   └── TurnTrace.java                          # + smallTalkAnswer()
│   └── usecase/
│       ├── SmallTalkClassifier.java                # NUEVO: normaliza y segmenta con el léxico; sin dependencias
│       ├── SmallTalk.java                          # NUEVO: handles(message) [activada + clasificador] y reply(message)
│       ├── StreamChatUseCase.java                  # bifurca antes de cargar el historial; registra el tipo de turno
│       ├── ConversationHistory.java                # searchText ignora los turnos de cortesía (si la vía está activa)
│       └── FollowUpQuery.java                      # sin cambios de firma
├── config/
│   ├── SmallTalkProperties.java                    # NUEVO: shoplab.chat.small-talk.{enabled,system-prompt,topics}
│   └── ChatConfig.java                             # registra SmallTalkProperties
├── domain/model/
│   ├── constants/SmallTalkConstants.java           # NUEVO: léxico cerrado, prompt y temas por defecto, límites
│   ├── constants/ChatConstants.java                # + TURNS_METRIC, KIND_TAG, reglas 7-8 del prompt de sistema
│   └── enums/{TurnKind.java (NUEVO), TurnResult.java (+ SMALL_TALK)}
└── infrastructure/out/
    ├── gateway/
    │   ├── GatewayChatAdapter.java                 # implementa también SmallTalkModelPort; usa PromptData
    │   └── PromptData.java                         # NUEVO: delimita y neutraliza texto no confiable
    ├── metrics/MicrometerChatMetrics.java          # + contador shoplab.chat.turns{kind}
    └── tracing/LangfuseChatTrace.java              # + marca de turno de charla (TurnTrace.Turn) y TurnResult.SMALL_TALK
backend/src/main/resources/application.yml          # shoplab.chat.small-talk.*
backend/src/test/java/com/shoplab/helpdesk/chat/
├── application/usecase/                            # SmallTalkClassifierTests, SmallTalkClassifierQuestionTests, SmallTalkTests,
│                                                   #   StreamChatUseCaseSmallTalkTests, StreamChatUseCaseQuestionRoutingTests,
│                                                   #   StreamChatUseCaseTurnKindTests, ConversationHistorySearchTextTests,
│                                                   #   GoldenDatasetClassifierTests y ajustes de constructores
├── config/SmallTalkPropertiesTests.java
├── infrastructure/out/gateway/                     # PromptDataTests, GatewayChatAdapterSmallTalkTests, PromptInjectionTests
├── ChatSmallTalkIntegrationTests.java              # SSE de punta a punta (sin recuperación, con recuperación caída)
├── ChatSmallTalkRoutingIntegrationTests.java       # mixto por RAG, petición ajena → mensaje fijo
├── ChatSmallTalkDisabledIntegrationTests.java      # interruptor desactivado = comportamiento previo
├── ChatSmallTalkMetricsIntegrationTests.java       # shoplab.chat.turns{kind}
└── (varias existentes)                             # "Hola" como ejemplo de pregunta → mensaje neutro (ver research D-12)

eval/
├── golden.jsonl                                    # + 12 small_talk, 12 injection, 6 mixtos (ids G091…)
├── README.md                                       # categorías y reglas nuevas
├── src/shoplab_eval/{constants,golden,validation,summary,retrieval_run}.py
└── tests/                                          # reglas nuevas del validador, dataset real y exclusión en la recuperación

docs/
├── adr/0025-conversacion-de-cortesia-acotada.md    # NUEVO (+ nota en ADR-0018)
└── chat-streaming.md                               # sección y tabla de configuración
specs/LAB-114-bounded-small-talk/requests.http      # Tras implementar (regla del repo: el comportamiento de /api/chat cambia)
```

**Structure Decision**: se respeta la estructura hexagonal ya presente en `chat`; no se crean módulos ni paquetes nuevos
(solo clases en los existentes). El clasificador vive en `application/usecase` porque es lógica de aplicación pura (sin
Spring AI ni E/S) que también necesita `ConversationHistory`; el léxico, al ser datos constantes, vive en
`domain/model/constants`. El delimitado de prompts vive en `infrastructure/out/gateway` porque es parte de cómo el adaptador
construye la petición al modelo. Los cambios de `eval/` mantienen la separación de LAB-27 (reglas en `validation.py`,
números en `constants.py`).

## Complexity Tracking

Sin violaciones de la Constitución que justificar.
