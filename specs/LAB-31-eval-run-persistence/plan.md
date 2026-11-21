# Implementation Plan: Persistencia de las ejecuciones de evaluación

**Branch**: `feature/LAB-31-eval-run-persistence` | **Date**: 2026-11-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-31-eval-run-persistence/spec.md`

## Summary

Guardar cada ejecución de las mediciones de LAB-28 (recuperación) y LAB-29 (calidad de respuesta) en un esquema `eval` de
PostgreSQL con tres tablas (`eval_run`, `eval_case`, `eval_result`), junto con la versión de cada prompt, el modelo del
asistente, el juez y la configuración de recuperación con que se midió.

Enfoque (detalle y alternativas en [research.md](research.md)):

- **Un módulo nuevo `eval` del backend** es el dueño del esquema (ADR-0015); la herramienta Python de `eval/` entrega cada
  ejecución **entera en una sola petición** `POST /api/eval/runs` y **no tiene credenciales de base de datos** (precedente:
  ADR-0017 con `ingest`, ADR-0024 con la búsqueda). Se guarda en **una transacción**: o está toda o no existe.
- El guardado está **apagado por defecto** y protegido por un token (`X-Eval-Token`), copiando el patrón de LAB-85; en la
  herramienta es opcional: se guarda si hay token, `--save` lo exige, `--no-save` lo evita.
- El módulo `chat` publica en su `api` una **instantánea de solo lectura** (prompts servidos con su versión numérica y
  parámetros de recuperación). La herramienta la lee antes y después de medir y **falla si cambió**, para no guardar una
  ejecución mezclada.
- Las puntuaciones van en un `jsonb` por resultado (vacío ≠ cero); la revisión de un caso se identifica por un hash que
  calcula el backend, de modo que editar el dataset nunca reescribe lo medido antes.
- El historial se **consulta con SQL documentado**; la pantalla y cualquier API de lectura son LAB-32.

**Fuera de este plan** (indicación del usuario): LAB-30. No se planifica CI ni dónde guarda una ejecución de CI efímero.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 4.x, Spring Modulith) para el módulo `eval`; Python ≥ 3.12 para `eval/`
(solo biblioteca estándar para el cliente HTTP, como `search_client.py`: sin dependencias nuevas).

**Primary Dependencies**: Spring WebFlux (controlador reactivo), `JdbcTemplate` + `TransactionTemplate` aislados con
`BlockingCalls` (ADR-0011), Flyway por módulo (`ModuleSchemaMigrator`), Jackson para el cuerpo. Ninguna en Python.

**Storage**: PostgreSQL 16, esquema `eval` (`V1__baseline.sql`, `V2__evaluation_history.sql`); `jsonb` para parámetros y
puntuaciones. Sin claves foráneas hacia otros esquemas.

**Testing**: JUnit 5 + Testcontainers PostgreSQL (`@PostgresTest`) para migraciones y adaptador; `WebTestClient` para el
controlador; ArchUnit y Modulith ya existentes; pytest con el backend simulado (`fake_backend.py`) en `eval/`. Sin red ni
modelo de lenguaje (Constitución IV).

**Target Platform**: el backend ya desplegado (compose local y clúster de desarrollo); la herramienta, la máquina de quien mide.

**Project Type**: monolito modular (backend) + herramienta de línea de comandos (`eval/`).

**Performance Goals**: guardar una ejecución (≈ 100 resultados, ≈ 100 KB: el dataset completo pesa 43 KB) en menos de 1 s; el guardado añade < 1 min al total
(SC-001).

**Constraints**: ejecución entera o ninguna; sin texto de respuestas ni fragmentos; sin credenciales de base en `eval/`;
sin cambiar el asistente ni cómo se calculan las métricas (FR-018); cobertura ≥ 80 % del backend; Checkstyle/PMD limpios;
líneas ≤ 121.

**Scale/Scope**: una o pocas ejecuciones por día; ≈ 100 filas de resultado cada una. Sin retención ni archivado.

## Constitution Check

*GATE: pasa antes de la investigación y se revalida tras el diseño.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | ✅ Módulo nuevo con su esquema y sin claves foráneas ni `JOIN` entre esquemas. Depende solo de `chat.api`; no hay ciclo (`chat` no conoce `eval`). Los límites se verifican con los tests de Modulith existentes. |
| II. Hexagonal | ✅ `domain` / `application` (puertos y casos de uso) / `infrastructure` (REST, JDBC, adaptador de `chat.api`) / `config`. El controlador solo construye el comando y llama al puerto de entrada. |
| III. Identificadores en inglés, errores tipados | ✅ Cinco excepciones tipadas y cinco códigos `SLHD_EVAL_0000N` en `ResponseCodeEnum`, mapeados solo en `GlobalExceptionHandler`. Documentación en español. |
| IV. Test-first, cobertura ≥ 80 % | ✅ Plan de pruebas abajo; controlador, casos de uso y adaptador con PostgreSQL real; cliente Python con backend simulado. |
| V. Evaluation-driven RAG | ✅ No cambia el asistente ni las métricas; **hace posible** el historial de resultados que el principio exige. No altera la regla de LAB-30. |
| VI. Observabilidad y manejo seguro | ✅ El token viene del entorno y no se registra; el esquema no guarda texto de respuestas ni de fragmentos; solo datos sintéticos. Registro estructurado con `run_id`, tipo y número de casos (sin contenido). |
| VII. Simplicidad y decisiones documentadas | ✅ con una justificación en *Complexity Tracking* (módulo nuevo). Decisión registrada como **ADR-0028**. |

**Resultado del gate**: sin violaciones injustificadas. **Revalidado tras el diseño** (Fase 1): sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-31-eval-run-persistence/
├── spec.md
├── plan.md              # este archivo
├── research.md          # Fase 0: decisiones y alternativas (R1-R11)
├── data-model.md        # Fase 1: las tres tablas, validaciones y consultas
├── quickstart.md        # Fase 1: validación de punta a punta (V1-V17)
├── contracts/
│   └── eval-runs-api.md # Fase 1: los tres endpoints, errores, propiedades y opciones de la herramienta
├── checklists/requirements.md
└── tasks.md             # Fase 2 (/speckit-tasks; no lo crea este comando)
```

### Source Code (repository root)

```text
backend/src/main/
├── java/com/shoplab/helpdesk/
│   ├── eval/                                       # MÓDULO NUEVO
│   │   ├── package-info.java                       # @ApplicationModule(displayName = "Eval")
│   │   ├── api/package-info.java
│   │   ├── domain/
│   │   │   ├── model/                              # EvalRun, EvalCase, EvalResult, PromptVersion, AssistantSettings
│   │   │   │   ├── constants/EvalConstants.java    # @UtilityClass: patrones, tope de tamaño, nombres de métrica
│   │   │   │   └── enums/                          # RunKind, CaseKind, PromptSource
│   │   │   └── exception/                          # PersistenceDisabled, RunInvalid, RunAlreadyExists,
│   │   │                                           # RunNotFound, StoreUnavailable
│   │   ├── application/
│   │   │   ├── port/in/                            # SaveEvalRunServicePort, FindEvalRunServicePort,
│   │   │   │                                       # ReadAssistantSettingsServicePort
│   │   │   ├── port/out/                           # EvalRunStorePort, AssistantSettingsPort
│   │   │   └── usecase/                            # SaveEvalRunUseCase, FindEvalRunUseCase,
│   │   │                                           # ReadAssistantSettingsUseCase, EvalRunValidator
│   │   ├── infrastructure/
│   │   │   ├── in/rest/                            # EvalRunController, EvalSettingsController, dto/, util/mapper/
│   │   │   └── out/
│   │   │       ├── persistence/JdbcEvalRunStoreAdapter.java   # transacción única, BlockingCalls
│   │   │       └── chat/ChatAssistantSettingsAdapter.java     # consume chat.api
│   │   └── config/                                 # EvalSchemaConfig (ModuleSchema("eval")), EvalProperties
│   ├── chat/
│   │   ├── api/AssistantSettingsApi.java, AssistantSettings.java   # NUEVO: instantánea de solo lectura
│   │   └── infrastructure/in/api/AssistantSettingsApiAdapter.java  # NUEVO: lee PromptCatalogPort.current() y RagProperties
│   └── shared/                                     # módulo abierto
│       ├── exception/ResponseCodeEnum.java         # + 5 códigos SLHD_EVAL_*
│       ├── exception/PublicDetail.java             # NUEVO: la excepción que puede mostrar un detalle seguro
│       └── infrastructure/in/rest/                 # ErrorResponse + `detail` opcional (@JsonInclude NON_NULL);
│                                                   # GlobalExceptionHandler lo rellena si la excepción es PublicDetail
└── resources/
    ├── application.yml                             # + shoplab.eval.persistence.token
    └── db/migration/eval/
        ├── V1__baseline.sql
        └── V2__evaluation_history.sql

backend/src/test/java/com/shoplab/helpdesk/
├── eval/                                           # tests del módulo (ver plan de pruebas)
└── schema/ModuleSchemasIntegrationTests.java       # + "eval" en MODULES; IngestHasNoDatabaseCredentialsTests
                                                    #   gana su equivalente para eval/

eval/
├── queries/                   # NUEVO: las consultas SQL del historial (FR-016), una por archivo; una prueba del backend
│                              #        las ejecuta contra PostgreSQL con datos de ejemplo para que no se pudran
├── src/shoplab_eval/
│   ├── store_client.py        # NUEVO: cliente HTTP (settings, run_exists, save); errores → MeasurementError
│   ├── store_payload.py       # NUEVO: construcción pura de la carga (calidad y recuperación), huella del dataset,
│   │                          #        commit, deriva de la instantánea
│   ├── store_session.py       # NUEVO: el orden de operaciones (comprobar → medir → releer → escribir → guardar)
│   ├── constants.py           # + rutas, cabecera, variables de entorno
│   └── __main__.py            # + --save/--no-save y --run-id en retrieval; orden de operaciones (R8)
├── tests/                     # test_store_payload.py, test_store_client.py, test_store_cli.py (+ extender fake_backend)
└── README.md                  # + qué se guarda, cómo activarlo y las consultas del historial (FR-016, FR-020)

docs/
├── adr/0028-persistencia-de-ejecuciones-de-evaluacion.md   # NUEVO + fila en docs/adr/README.md
├── modulos/                                                 # regenerado por ModularityTests (módulo eval)
└── modulos.md                                               # + fila `eval` y sección «Datos de eval»

../shoplab-infra/ (repositorio hermano; ver «Cableado de infraestructura»)
├── terraform/envs/dev/secrets.auto.tfvars.example           # + entrada `eval` (JSON con EVAL_PERSISTENCE_TOKEN)
├── scripts/ensure-secrets.sh                                # + genera la entrada `eval` si falta (solo añade)
├── scripts/tests/ensure-secrets.test.sh                     # + casos de la entrada `eval`
├── k8s/base/backend/externalsecret-eval.yaml                # NUEVO: ExternalSecret `backend-eval`
├── k8s/base/backend/kustomization.yaml                      # + el recurso nuevo
├── k8s/overlays/dev/kustomization.yaml                      # + parche de la clave de `backend-eval` (ENVIRONMENT → dev)
├── k8s/base/backend/deployment.yaml                         # + envFrom `backend-eval` (optional: true)
├── scripts/quality.sh                                       # + lee el token del Secret y lo exporta a la herramienta
└── docs/gateway-de-modelos.md                               # + sección «Historial de evaluación (LAB-31)»
```

**Structure Decision**: se sigue la estructura por módulo ya establecida (`docs/modulos.md`): módulo = esquema = carpeta de
migraciones. `eval/` (Python) solo gana dos módulos pequeños y la integración en el CLI; no importa ni depende de Java.
Los archivos de la herramienta se mantienen separados de los de medición para que el cálculo de métricas (LAB-28/29) no cambie
(FR-018).

## Plan de pruebas (resumen)

| Capa | Qué se prueba | Spec |
|---|---|---|
| Migración / esquema | Las tres tablas existen en `eval`, historial propio, `UNIQUE` y `CHECK` (0–1, formatos), sin claves foráneas cruzadas | FR-001 |
| Caso de uso (unit) | Validación (métricas permitidas por tipo de caso, duplicados, rangos), identificador repetido, token | FR-003, FR-013 |
| Adaptador JDBC (PostgreSQL real) | Guardado atómico: un fallo a mitad no deja filas; revisiones de caso reutilizadas y nuevas; dos guardados concurrentes con el mismo `run_id` (uno gana, 409) | FR-004, FR-012 |
| Controlador (`WebTestClient`) | 201, 400, 403, 404, 409, 422, 503; rechazo de claves desconocidas; el token no aparece en nada | contrato |
| Equivalencia con los CSV | Una carga construida desde el resultado de una medición reproduce las medias por ámbito y el número de casos | FR-015, SC-002 |
| Sin texto | Ninguna columna acepta ni guarda texto de respuesta o fragmento | FR-009, SC-008 |
| Arquitectura | Reglas de capas, límites de Modulith, aislamiento de esquema; `eval/` sin credenciales de base | ADR-0015 |
| Python | Carga (vacío ≠ cero, conversación excluida, `pinned`), deriva, orden: preflight antes de medir, CSV antes de guardar, `--save` sin token = 2, sin token = como antes | FR-010 a FR-014 |
| Manual | Escenarios V1-V17 de [quickstart.md](quickstart.md), incluidos los que necesitan Ollama, Langfuse y el clúster | SC-001 a SC-009 |

## Cableado de infraestructura (repositorio `shoplab-infra`)

El entorno de desarrollo **es** el entorno local del equipo (`make up` de `shoplab-infra`: clúster Floci, secretos en
Secrets Manager, Postgres y Ollama en el Docker del host). Este repositorio **no tiene servicio `backend` en su compose**
(solo `postgres`, `ollama`, `litellm`, `ingest` y observabilidad), así que aquí no hay nada que cablear: el backend corre
desde el IDE/Maven o en el clúster. El token llega al backend del clúster así, siguiendo el patrón de `backend-langfuse` y
`backend-litellm`:

| Pieza | Cambio | Por qué |
|---|---|---|
| `terraform/envs/dev/secrets.auto.tfvars.example` | Entrada nueva `eval = "{\"EVAL_PERSISTENCE_TOKEN\":\"CHANGE_ME\"}"` | Un JSON por secreto, como `langfuse` y `litellm`; Terraform lo sube a `/shoplab/dev/eval` sin tocar el módulo (`for_each` sobre las claves de `secrets`) |
| `terraform/modules/secrets/variables.tf` (`required_secrets`) | **Sin cambio** | El guardado es opcional (FR-010): un entorno sin `eval` debe seguir arrancando |
| `scripts/ensure-secrets.sh` | Entrada `eval` con la clave `EVAL_PERSISTENCE_TOKEN` (64 hex); solo añade, nunca imprime el valor. Con `TF_VAR_secrets` avisa si falta, **no falla** | `make up` ya lo ejecuta: sin pasos manuales (V17) |
| `scripts/tests/ensure-secrets.test.sh` | Casos: añade la entrada si falta, no cambia un valor existente, no imprime el valor, archivo ya completo no cambia | Es lo que ya hacen para `langfuse` y `litellm` |
| `k8s/base/backend/externalsecret-eval.yaml` | `ExternalSecret` `backend-eval`: `SHOPLAB_EVAL_PERSISTENCE_TOKEN` ← `/shoplab/ENVIRONMENT/eval`, propiedad `EVAL_PERSISTENCE_TOKEN` | Mismo formato que `externalsecret-langfuse.yaml`; el overlay sustituye `ENVIRONMENT` |
| `k8s/base/backend/kustomization.yaml` | + `externalsecret-eval.yaml` | |
| `k8s/overlays/dev/kustomization.yaml` | Parche `replace /spec/data/0/remoteRef/key` → `/shoplab/dev/eval` para `backend-eval` | El overlay sustituye `ENVIRONMENT` con un parche **por cada** `ExternalSecret` (los de Langfuse y LiteLLM lo hacen igual); sin él la clave queda `/shoplab/ENVIRONMENT/eval`. Se descubrió al implementar |
| `k8s/base/backend/deployment.yaml` | + `envFrom: secretRef backend-eval` con **`optional: true`** | Si el secreto no existe, el backend arranca igual con el guardado apagado (403) en vez de quedarse en `CreateContainerConfigError` por una función opcional |
| `scripts/quality.sh` | Lee `SHOPLAB_EVAL_PERSISTENCE_TOKEN` del Secret `backend-eval` (como ya lee `LITELLM_KEY_EVAL`) y exporta `SHOPLAB_EVAL_TOKEN` + `SHOPLAB_EVAL_ORIGIN=dev`; si no existe, avisa y mide sin guardar | `make quality` guarda solo, y `ARGS="--no-save"` lo evita |
| `docs/gateway-de-modelos.md` | Sección «Historial de evaluación (LAB-31)»: qué secreto, qué `ExternalSecret`, cómo consultar | Documentación de quien opera el entorno |

Consideraciones:

- **El módulo `eval` solo llega a desarrollo con un release.** El despliegue continuo fija etiquetas `vX.Y.Z` (ADR-0012), así
  que la verificación de V17 en el clúster espera al primer tag que incluya la historia; hasta entonces, V1–V16 se validan con
  el backend corriendo desde el IDE contra el Postgres del Docker del host.
- **La rama de infraestructura es independiente.** El árbol de trabajo de `shoplab-infra` está hoy en
  `fix/LAB-29-dev-langfuse-tunnel-ollama-check` con `scripts/up.sh` modificado sin confirmar: esos cambios no son de esta
  historia y no se mezclan. Los cambios de esta historia van en una rama nueva desde `origin/main` de ese repositorio y en su
  propio PR (que se fusiona **antes** de que el backend con el módulo `eval` se despliegue, para que el secreto exista).
- **Sin credenciales de base de datos nuevas**: el backend usa la suya. El único secreto nuevo es el token.
- **Verificaciones del repositorio de infra**: `make check` (kustomize, políticas, `test-ci-scripts`) debe pasar, y `make up`
  debe dejar el Secret `backend-eval` creado con el valor generado.

## Riesgos y decisiones abiertas

- **ADR-0028 y numeración**: el último ADR en `main` es el 0027 (el 0026 no está); se comprueba el número libre al escribirlo.
- **Orden de fusión entre repositorios**: el PR de `shoplab-infra` debe fusionarse antes del release que despliega el módulo
  `eval`; si no, el backend arranca con el guardado apagado (por `optional: true`) y no falla, pero no se podrá guardar hasta
  que el secreto exista.
- **Deriva de configuración**: solo se detecta lo que `chat` publica (prompts y parámetros de recuperación). Un cambio de modelo
  del asistente o del juez en el gateway durante la medición no se ve desde el backend; el modelo del asistente lo declara quien
  mide (`--assistant-model`), como hoy.
- **El commit registrado es el de `eval/`**, no el del backend medido; se documenta.
- **`prompts` en recuperación**: la medición de recuperación no usa prompts, así que guarda `[]`; las seis métricas de una vista
  conjunta (LAB-30/32) tendrán que agrupar una ejecución de cada tipo por commit y origen.

## Complexity Tracking

| Desviación | Por qué hace falta | Alternativa más simple rechazada porque |
|---|---|---|
| Un **módulo nuevo** del backend (`eval`) con puertos, casos de uso y adaptadores para lo que parece «escribir tres tablas» | ADR-0015: un dueño por dato, y `eval/` no puede tener credenciales de base de datos (ADR-0017/0024) | Escribir desde Python con credenciales propias: más corto, pero añade un secreto y una dependencia, duplica validaciones y rompe la convención que el resto de módulos y sus pruebas vigilan |
| Una **segunda lectura** de la instantánea al terminar la medición (detección de deriva) | LAB-85 existe para editar prompts con el chat vivo; sin ella el historial puede atribuir a una versión lo que respondió otra | Guardar solo la instantánea inicial: más simple, pero deja entrar ejecuciones mezcladas que contaminan toda comparación posterior |
