# Validación: Métricas de retrieval (LAB-28)

Verificación de la implementación el 2026-11-17 (pruebas automáticas) y el 2026-11-18 (stack local con Docker).

## Verificado automáticamente

| Qué | Comando | Resultado |
|---|---|---|
| `eval/`: pruebas y cobertura | `cd eval && uv run pytest` | 180 pruebas en verde; cobertura 98,83 % (mínimo 80 %); `retrieval_metrics.py`, `retrieval_report.py` y `retrieval_run.py` al 100 %, `search_client.py` al 96 % |
| `eval/`: estilo | `uv run ruff check .` | Sin avisos |
| `eval/`: dataset | `uv run python -m shoplab_eval validate` | Sin cambios: 90 casos, 70 con respuesta y 20 sin respuesta |
| Backend: build completo | `mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true` (con Docker) | **797 pruebas, 0 fallos y 0 errores; «All coverage checks have been met»; BUILD SUCCESS** |
| Backend: arquitectura | `mvn test -Dtest=ModularityTests` | 4 pruebas en verde; `docs/modulos/` no cambia |
| Backend: estilo | `mvn checkstyle:check` | 0 violaciones |
| Backend: cobertura de lo nuevo | informe de JaCoCo | `KnowledgeSearchController` y `SearchChunksUseCase` al 100 % de instrucciones y ramas (los DTO y el mapper están excluidos por configuración) |

## Medición real contra el stack local (T038)

Entorno: backend de esta rama (`spring-boot:run`) contra el gateway LiteLLM, Ollama (`bge-m3`) e ingest del `docker compose`
de `infra/`, con una **PostgreSQL con pgvector desechable** (misma imagen y scripts de inicio, puerto 5433) porque el volumen
del stack tenía una contraseña distinta de la que el backend usa por defecto. Los 24 documentos de `data/kb` (solo los `.md`)
subidos por `POST /api/documents` hasta quedar los 24 en `INDEXED`. Comando: `cd eval && uv run python -m shoplab_eval retrieval`.

| Ámbito | Casos | hit@1 | hit@3 | hit@5 | recall@1 | recall@3 | recall@5 | MRR |
|---|---|---|---|---|---|---|---|---|
| all | 70 | 0,4286 | 0,8857 | 0,9286 | 0,3357 | 0,8500 | 0,9214 | 0,6613 |
| payments | 14 | 0,2143 | 0,7143 | 0,8571 | 0,1429 | 0,6786 | 0,8571 | 0,5208 |
| privacy | 14 | 0,4286 | 0,8571 | 0,8571 | 0,3571 | 0,8214 | 0,8571 | 0,6399 |
| returns | 14 | 0,3571 | 0,9286 | 0,9286 | 0,2857 | 0,9286 | 0,9286 | 0,6280 |
| shipping | 14 | 0,6429 | 0,9286 | 1,0000 | 0,5357 | 0,8571 | 0,9643 | 0,7798 |
| warranty | 14 | 0,5000 | 1,0000 | 1,0000 | 0,3571 | 0,9643 | 1,0000 | 0,7381 |

Modelo `bge-m3`, profundidad 50, **70 casos medidos y 20 excluidos**; ninguna pregunta se quedó sin acierto en la lista completa.
Tiempo total: **5,4 s** (objetivo: menos de 1 minuto). Es una línea de referencia de esta versión, no un umbral: los umbrales
son de LAB-30.

Lectura: el documento correcto casi siempre está entre los 3 primeros (hit@3 = 0,89), pero pocas veces en primer lugar
(hit@1 = 0,43). `payments` es el tema más flojo. Parte de esa diferencia viene de que varios documentos repiten el mismo dato
(política y su FAQ) y la métrica exige el documento esperado concreto; recall@1 es menor porque 15 de los casos esperan dos
documentos.

| Comprobación | Resultado |
|---|---|
| Determinismo (quickstart §5): dos ejecuciones, `diff -r` | **Idénticas** |
| Backend caído (§6) | Código `1`, mensaje con `http://localhost:8080` y cómo levantarlo, sin CSV |
| Índice incompleto (§6): un documento esperado que existe en el corpus pero nadie indexó | Código `1`, lista `payments-99-sin-indexar: no está en la base de conocimiento`, sin búsquedas ni CSV |
| Dataset inválido (§6): una pregunta duplicada | Código `1`, `G-DUPLICATE SET: las preguntas de estos casos son iguales: G001, G002`, sin CSV |
| `--k 0` y `--k 3,x` (§6) | Código `2` con el valor rechazado y el rango aceptado, sin tocar el backend ni crear carpetas |
| Ollama parado (§6) | Código `1`, `Falló la búsqueda del caso G001: Tiempo agotado (60 s)…`, sin CSV. Con Ollama parado la búsqueda no contestó con un error rápido (el síntoma fue el tiempo agotado de 60 s, sin investigar el motivo en el gateway) y el mensaje cubre las dos causas |
| Cambio de la indexación (§7, SC-006): trozos de 200 caracteres en vez de 1000 (244 fragmentos) y reindexado | Las métricas cambian y el detalle localiza dónde: **18 casos empeoran, 18 mejoran y 34 quedan igual** (p. ej. G004 pasa del puesto 1 al 2, G016 del 8 al 15). En conjunto no empeora (hit@5 0,9571, MRR 0,6660) pero `returns` baja (hit@3 0,9286 → 0,7143, MRR 0,6280 → 0,5728): la medición detecta el cambio aunque no sea una regresión global |

## Revisión de los CSV (T039)

Recalculo **independiente** (un script aparte que solo lee las columnas `expected_documents` y `retrieved_documents` y aplica
las definiciones de [data-model.md](data-model.md)) sobre las dos mediciones reales: las **140 filas de detalle** y las **12
filas de resumen** coinciden con los CSV (0 discrepancias), `first_hit_rank` incluido; y en cada medición `cases +
excluded_cases` de `all` es 90 (SC-002 y SC-003). Es una verificación por código, no una lectura humana: la revisión de un
caso a ojo contra el documento queda como mejora opcional para quien firme la historia.

## Pendiente

- **Peticiones de `requests.http`** (IntelliJ HTTP Client / REST Client): no se ejecutaron. El endpoint se comprobó con el
  índice vacío (`{"model":"bge-m3","results":[]}`, consulta en blanco → 422) y de punta a punta a través del comando; los
  límites (`limit` 0, 1, 50, 51) los cubren `SearchChunksUseCaseTests` y `KnowledgeSearchControllerTests`.
- **PR y validación del primer tag `vX.Y.Z`** (T041): no se abre hasta que se pida.

## Observaciones para otras historias

- La PostgreSQL del `docker compose` de esta máquina rechaza la contraseña por defecto del backend
  (`change-me-local-only`): el volumen `pgdata` se creó con otra. No se tocó; quien arranque el backend contra ese stack debe
  exportar `POSTGRES_PASSWORD`.
- `mvn -o` ya no funciona sin conexión desde LAB-84 (falta en caché el starter de OpenAI).
