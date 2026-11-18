---

description: "Task list for LAB-27 - Dataset golden de preguntas y respuestas"
---

# Tasks: Dataset golden de preguntas y respuestas

**Input**: Design documents from `/specs/LAB-27-golden-dataset/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`golden-format.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación y `eval/` usará
`--cov-fail-under=80` como `ingest/`. En cada historia con código las pruebas van primero y deben fallar antes de
implementar. Ninguna prueba usa red, base de datos ni modelo de lenguaje (FR-016): trabajan con un corpus de juguete en
`tmp_path`, salvo `test_real_dataset.py`, que lee el `golden.jsonl` y el `data/kb` reales.

**Organization**: Tareas agrupadas por historia de usuario. US1 (casos con respuesta) y US2 (casos sin respuesta) son P1 y
son **datos**; US3 (validación automática) es P2 y es **código**. Aunque US3 sea P2, **conviene hacerla antes de redactar
los datos** (el orden de ejecución recomendado es Setup → Foundational → US3 → US1 → US2 → Polish): el validador es la red
de seguridad de la autoría. Las tareas siguen aun así el orden de prioridad de la spec; las dependencias reales están en
«Dependencies & Execution Order».

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US3)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Proyecto**: `eval/` (proyecto `uv` `shoplab-eval`); **paquete**: `eval/src/shoplab_eval/`; **pruebas**:
  `eval/tests/` (sin `tests/__init__.py`, para no chocar con el `tests/` de `ingest/`); **datos**: `eval/golden.jsonl`
- Python: líneas ≤ 121 caracteres, identificadores en inglés, `ruff` con `select = ["E", "F", "I", "B", "UP"]` como
  `notebooks/` y `data/kb-tools`; los datos, la documentación y los mensajes de error para quien edita el archivo, en
  español
- Nada de `eval/` se importa desde `backend/` ni `ingest/`; `data/kb/` no se modifica (plan, Constraints)
- Sin datos personales reales ni secretos en el dataset (Constitución VI): correos solo `@shoplab.example`, ningún teléfono
- **Identificadores de caso reservados** (plan R6, data-model «Composición objetivo»): `G001`–`G014` payments,
  `G015`–`G028` privacy, `G029`–`G042` returns, `G043`–`G056` shipping, `G057`–`G070` warranty (todos con respuesta);
  `G071`–`G073` payments, `G074`–`G075` privacy, `G076`–`G078` returns, `G079`–`G080` shipping, `G081`–`G082` warranty
  (sin respuesta del tema); `G083`–`G090` `off_topic`. Total 90. Un `id` no cambia nunca una vez escrito

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde, proyecto `uv` creado y CI preparado

- [X] T001 Línea base del corpus: `cd data/kb-tools && uv run pytest` debe pasar en la rama (el corpus es válido y tiene
  los 24 documentos de `data/kb/INDEX.md`); confirmar con `ls data/kb/*.md` que hay 26 archivos (24 documentos más
  `INDEX.md` y `REVIEW.md`). No se modifica `data/kb/`; anotar fallos previos si los hay
- [X] T002 Crear `eval/pyproject.toml` (proyecto `shoplab-eval`, `requires-python >= 3.12`, build `hatchling` con
  `packages = ["src/shoplab_eval"]`): dependencia `pyyaml>=6.0`; grupo `dev` con `pytest>=8.0`, `pytest-cov>=5.0`,
  `ruff>=0.6`. `[tool.pytest.ini_options]`: `testpaths = ["tests"]`, `pythonpath = ["src"]`,
  `addopts = "--cov=shoplab_eval --cov-fail-under=80"`. Sin `omit` de cobertura: `__main__.py` se cubre con pruebas
  (T023). Al ejecutar **solo algunos archivos** de prueba durante el TDD (T008, T010, T021–T023), añadir `--no-cov` para que
  falle por la razón esperada y no por la cobertura (`uv run pytest --no-cov tests/<archivo>`). `[tool.ruff]`: `line-length = 121`,
  `target-version = "py312"`, `src = ["src", "tests"]`, `[tool.ruff.lint] select = ["E", "F", "I", "B", "UP"]`. Ejecutar
  `cd eval && uv sync` para generar `eval/uv.lock`. No tocar `ingest/pyproject.toml` ni `notebooks/pyproject.toml`
- [X] T003 [P] Crear el esqueleto del paquete: `eval/src/shoplab_eval/__init__.py` (vacío por ahora; las exportaciones
  llegan en T029) y `eval/tests/` vacío (sin `__init__.py`); eliminar `eval/.gitkeep` con `git rm eval/.gitkeep` en cuanto
  exista otro archivo versionado en `eval/`
- [X] T004 [P] Añadir `data/kb/**` al filtro `eval` en `.github/workflows/ci.yml` (hoy solo `eval/**`), con un comentario
  igual de breve que los vecinos, p. ej. `# The dataset validation checks every expected document against the corpus.`
  (plan R8). No cambiar la matriz ni el job `python`; confirmar con `git diff .github/workflows/ci.yml` que solo cambió esa
  clave

**Checkpoint**: `cd eval && uv sync --frozen` funciona y el filtro de CI está preparado

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: los tipos, el corpus y la lectura que usan todas las historias. Sin el lector, ni la validación ni la
comprobación de los datos redactados pueden ejecutarse

**⚠️ CRITICAL**: ninguna historia puede empezar hasta terminar esta fase

- [X] T005 [P] Crear `eval/src/shoplab_eval/constants.py`: `TOPICS = ("payments", "privacy", "returns", "shipping",
  "warranty")`, `OFF_TOPIC = "off_topic"`, `CATEGORIES = TOPICS + (OFF_TOPIC,)`, `CONTROL_FILES = ("INDEX.md",
  "REVIEW.md")`, `DEFAULT_GOLDEN_PATH` y `DEFAULT_KB_DIR` resueltos **desde la raíz del repositorio**
  (`Path(__file__).resolve().parents[3]`, no desde el directorio de trabajo) y `GoldenRules` (`@dataclass(frozen=True)`)
  con los valores de [data-model.md](data-model.md) «`GoldenRules`»: `MIN_CASES = 80`, `MAX_CASES = 100`,
  `MIN_ANSWERED_PER_TOPIC = 12`, `MIN_UNANSWERED = 12`, `UNANSWERED_MIN_SHARE = 0.15`, `UNANSWERED_MAX_SHARE = 0.25`,
  `MIN_UNANSWERED_TOPIC = 5`, `MIN_OFF_TOPIC = 5`, `MIN_MULTI_DOCUMENT = 8`, `QUESTION_LENGTH = (10, 300)`,
  `ANSWER_LENGTH = (20, 800)`, `MAX_EXPECTED_DOCUMENTS = 2`; con un comentario que remita a `spec.md` (cambiar un umbral
  es una decisión de la spec)
- [X] T006 [P] Crear `eval/src/shoplab_eval/errors.py`: `Issue` (`@dataclass(frozen=True)`: `code: str`, `case_id: str`,
  `message: str`, `line: int | None = None`) e `InvalidGoldenError(Exception)` que guarda la lista de `Issue` y cuyo mensaje
  las lista **una por línea** con el formato `<code> <case_id>: <message>`, **ordenadas por línea del archivo y luego por
  código** (data-model «`Issue` e `InvalidGoldenError`»), para que dos ejecuciones sobre el mismo archivo den el mismo texto.
  `case_id` es el `id` del caso, `SET` para una regla de conjunto o `LINE n` si la línea no se pudo leer
- [X] T007 Crear `eval/tests/conftest.py` (sin `__init__.py`): fixture `toy_kb(tmp_path)` que escribe un corpus de juguete
  con 5 documentos `payments-01-a.md`, `payments-02-b.md`, `returns-01-a.md`, `shipping-01-a.md`, `warranty-01-a.md`
  (front matter `title`, `topic`, `type`, `language: es` y un cuerpo breve) más `INDEX.md` y `REVIEW.md` que **no** deben
  contarse como documentos; helper `make_case(**overrides)` que devuelve un `dict` de caso válido (`id` `G001`, pregunta de
  más de 10 caracteres, respuesta de más de 20, un documento existente del corpus de juguete y su categoría) y helper
  `write_golden(path, cases)` que escribe un caso por línea en UTF-8 con `ensure_ascii=False` y salto de línea final
- [X] T008 [P] Escribir `eval/tests/test_corpus.py` (debe fallar antes de T009): `load_corpus(kb_dir)` devuelve un
  `CorpusDocument` (`id`, `topic`, `text`) por cada `*.md` **excepto** `INDEX.md` y `REVIEW.md`, ordenados por `id`; el `id`
  es el nombre sin `.md`; `topic` sale del front matter; un documento sin front matter o sin `topic` lanza `ValueError`
  con el nombre del archivo; el PDF del mismo nombre no cuenta; `normalize_text` hace `casefold`, quita puntuación y
  símbolos (`¿ ? ¡ ! , . ; : « » " ' - ( )` y marcas de markdown), colapsa espacios y saltos de línea en uno, **conserva
  tildes y eñes**, y es idempotente
- [X] T009 Crear `eval/src/shoplab_eval/corpus.py`: `CorpusDocument` (`@dataclass(frozen=True)`: `id`, `topic`, `text`
  con el cuerpo ya normalizado), `normalize_text(text: str) -> str` (la normalización de
  [contracts/golden-format.md](contracts/golden-format.md) «Normalización») y `load_corpus(kb_dir: Path = DEFAULT_KB_DIR)
  -> dict[str, CorpusDocument]`; el front matter se lee con `yaml.safe_load` entre las dos primeras líneas `---`. Hasta que
  T008 pase, no seguir (depende de T005)
- [X] T010 [P] Escribir `eval/tests/test_golden_load.py` (debe fallar antes de T011): `parse_golden(path)` devuelve un
  `ParsedGolden(cases, issues)`: un archivo válido da todos los casos y ninguna `Issue`; **las líneas vacías (incluida la
  final) y un archivo sin salto de línea final no cuentan como caso ni dan error**; una línea que no es JSON da `G-JSON`
  con `LINE n` y las demás líneas se siguen leyendo; una línea que no es un objeto, con una clave desconocida, sin una
  clave obligatoria o con un valor de tipo equivocado (`expected_documents` que no es lista de cadenas) da `G-FIELDS` con el
  `id` si se puede leer o `LINE n` si no; los acentos y las eñes llegan intactos; la lectura de un archivo con BOM falla con
  un mensaje claro en lugar de leer un `id` corrupto
- [X] T011 Crear `eval/src/shoplab_eval/golden.py`: `GoldenCase` (`@dataclass(frozen=True)`) con los campos y reglas de
  [data-model.md](data-model.md) «`GoldenCase`»: `id` («`G` + tres dígitos (`G001`…`G999`); único; estable»), `question`
  («español, 10–300 caracteres, autocontenida»), `reference_answer` («20–800 caracteres; verificable con los documentos
  esperados; en un caso sin respuesta, reconoce la falta de información y no contiene dígitos»), `expected_documents`
  (`tuple[str, ...]`, «0, 1 o 2 ids de documento, sin repetir y existentes en el corpus; vacío ⇔ caso sin respuesta»),
  `category` («uno de `payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic`»); propiedades derivadas
  `answerable`, `kind` (`"answered"`, `"unanswered_topic"` u `"off_topic"`) e `is_multi_document`. `ParsedGolden`
  (`cases: list[GoldenCase]`, `issues: list[Issue]`) y `parse_golden(path: Path = DEFAULT_GOLDEN_PATH) -> ParsedGolden`
  que lee en UTF-8, ignora líneas vacías, rechaza claves desconocidas y tipos equivocados (solo la estructura: las demás
  reglas son de US3). Hasta que T010 pase, no seguir (depende de T005, T006)

**Checkpoint**: `cd eval && uv run pytest --no-cov tests/test_corpus.py tests/test_golden_load.py` pasa; el lector funciona con un
archivo de juguete

---

## Phase 3: User Story 1 - Casos que el corpus sí responde (Priority: P1) 🎯 MVP

**Goal**: 70 casos con respuesta en `eval/golden.jsonl` (14 por tema, al menos 9 de dos documentos), cada documento del
corpus esperado al menos una vez.

**Independent Test**: abrir `eval/golden.jsonl`, comprobar que tiene 70 líneas, que `parse_golden` las lee todas sin
`Issue`, y que cinco casos al azar se verifican leyendo solo sus documentos esperados (spec US1).

### Reglas de autoría (valen para T012–T016, T018 y T019)

- Una línea JSON por caso, UTF-8 sin BOM, `ensure_ascii=False`, claves en el orden `id`, `question`, `reference_answer`,
  `expected_documents`, `category` ([contracts/golden-format.md](contracts/golden-format.md)).
- **Antes de redactar un tema, leer sus documentos completos en `data/kb/`.** La respuesta de referencia se redacta solo con
  lo que dicen los documentos esperados; **no se inventa ningún dato** (el corpus es sintético y una respuesta falsa
  contamina todas las métricas, plan R7). Se redacta con palabras propias, no se copian párrafos enteros.
- La pregunta suena a cliente real (coloquial, formal, con un detalle de un pedido ficticio), es autocontenida (sin «¿y en
  cuotas?») y **no repite una frase del documento**: una parte importante usa palabras distintas a las del corpus (FR-013).
  Mezclar preguntas que caen en políticas y en la FAQ del tema.
- Pregunta de 10–300 caracteres, respuesta de 20–800. Ningún dato personal real: si hace falta un número de pedido, uno
  obviamente ficticio (p. ej. `PED-0001`); correos solo `@shoplab.example`.
- Los de dos documentos van con **ambos** ids en `expected_documents` y la respuesta debe necesitar a los dos. La
  `category` es el tema de al menos uno de los dos.
- **Puerta de revisión por tema** (spec, Assumptions: «cada caso lo revisa una persona»; plan R7). Aunque un modelo ayude a
  redactar, un tema **no se da por cerrado** hasta que la persona revisora haya leído **cada** caso del tema contra sus
  documentos esperados y haya confirmado que la respuesta de referencia es cierta y completa; no se empieza el tema
  siguiente sin esa confirmación. Se anota en un borrador de `eval/REVIEW.md` una fila por tema (tema, ids, revisor, fecha)
  que T033 pasa a limpio. La muestra al azar de T033 es una **segunda** comprobación, no sustituye a esta.
- Una respuesta de referencia de un caso **sin respuesta** (T018, T019) no escribe plazos ni importes **ni en cifras ni en
  palabras** («siete días», «una semana», «cien reales»): `G-NOANSWER-DIGITS` solo detecta dígitos.

### Implementation for User Story 1

- [ ] T012 [US1] Redactar en `eval/golden.jsonl` los casos `G001`–`G014`, categoría `payments`, a partir de los 5
  documentos `payments-01-payment-methods`, `payments-02-installments`, `payments-03-boleto-and-vouchers`,
  `payments-04-payment-issues` y `payments-05-faq-payments`: 14 casos con respuesta, **cada uno de los 5 documentos
  esperado al menos una vez** y **2 casos de dos documentos**. Crear el archivo si no existe. Seguir las reglas de autoría
  y cerrar el tema con la **puerta de revisión** (la persona revisora confirma los 14 casos)
- [ ] T013 [US1] Añadir a `eval/golden.jsonl` los casos `G015`–`G028`, categoría `privacy`, de `privacy-01-privacy-policy`,
  `privacy-02-data-collection-and-use`, `privacy-03-data-rights`, `privacy-04-cookies` y `privacy-05-faq-privacy`: 14
  casos, los 5 documentos esperados al menos una vez y **1 caso de dos documentos**; cerrar con la **puerta de revisión**
  (depende de T012: mismo archivo)
- [ ] T014 [US1] Añadir los casos `G029`–`G042`, categoría `returns`, de `returns-01-return-policy`,
  `returns-02-return-process`, `returns-03-refunds`, `returns-04-exchanges-and-cancellations` y `returns-05-faq-returns`:
  14 casos, los 5 documentos esperados al menos una vez y **2 de dos documentos**; cerrar con la **puerta de revisión**
  (depende de T013)
- [ ] T015 [US1] Añadir los casos `G043`–`G056`, categoría `shipping`, de `shipping-01-delivery-times`,
  `shipping-02-shipping-costs`, `shipping-03-order-tracking`, `shipping-04-delivery-issues` y `shipping-05-faq-shipping`:
  14 casos, los 5 documentos esperados al menos una vez y **2 de dos documentos**; cerrar con la **puerta de revisión**
  (depende de T014)
- [ ] T016 [US1] Añadir los casos `G057`–`G070`, categoría `warranty`, de `warranty-01-legal-warranty`,
  `warranty-02-extended-warranty`, `warranty-03-warranty-claims` y `warranty-04-faq-warranty`: 14 casos, los 4 documentos
  esperados al menos una vez y **2 de dos documentos**; cerrar con la **puerta de revisión** (depende de T015)
- [X] T017 [US1] Comprobar la historia con un script desechable fuera del repositorio (no se versiona) que use `parse_golden`
  y `load_corpus`: 70 casos sin ninguna `Issue`; 14 por categoría de tema; `ids` `G001`–`G070` sin huecos ni repetidos; los 24
  documentos esperados al menos una vez; al menos 9 casos de dos documentos; ninguna pregunta repetida. Corregir lo que
  falle (depende de T011 y T016)

**Checkpoint**: 70 casos con respuesta, cargables y con todo el corpus cubierto. US1 se puede demostrar sola leyendo el
archivo

---

## Phase 4: User Story 2 - Preguntas que el corpus no puede responder (Priority: P1)

**Goal**: 20 casos sin respuesta: 12 del tema con el dato no publicado y 8 ajenas a la tienda, con una respuesta de
referencia que reconoce la falta de información sin inventar nada.

**Independent Test**: filtrar los casos con `expected_documents` vacío (20, el 22 % del total con US1), comprobar que
existen de ambos tipos, que ninguno se responde con el corpus (búsqueda en `data/kb`) y que ninguna respuesta de referencia
contiene un dígito (spec US2).

### Implementation for User Story 2

- [ ] T018 [US2] Añadir a `eval/golden.jsonl` los 12 casos **del tema sin respuesta** (`expected_documents: []`, categoría
  del tema): `G071`–`G073` payments, `G074`–`G075` privacy, `G076`–`G078` returns, `G079`–`G080` shipping, `G081`–`G082`
  warranty. Cada pregunta debe ser plausible para un cliente de ese tema pero pedir un dato que **la tienda no publica**
  (un método de pago, un plazo, una condición o un servicio que el corpus no menciona). Ejemplo ya comprobado:
  «¿Puedo pagar mi pedido con criptomonedas?» (no aparece en `data/kb`). Para cada uno, **buscar sus términos clave en
  `data/kb/*.md`** (`grep -ril "<término>" data/kb`, y variantes con y sin tilde) y confirmar que ningún documento
  permite contestarla ni siquiera en parte; si algo la contesta, cambiar la pregunta, no el documento. Apuntar cada
  búsqueda y su resultado en un borrador para `eval/REVIEW.md` (se pasa a limpio en T033). La respuesta de referencia: en
  lenguaje de cliente, reconoce que no hay información disponible, **no contiene ningún dígito** (ni cifra, ni plazo, ni
  importe) **ni plazos o importes escritos en palabras** («siete días», «una semana»), no inventa una política ni un dato
  de contacto y, si se recomienda algo, es un paso genérico («consulta con atención al cliente»). Cerrar con la **puerta de
  revisión** (la persona revisora confirma los 12 casos y sus búsquedas). Depende de T016 (mismo archivo)
- [ ] T019 [US2] Añadir los 8 casos **ajenos a la tienda** `G083`–`G090` (`category: "off_topic"`, `expected_documents:
  []`): preguntas que no tienen que ver con pagos, privacidad, devoluciones, envíos ni garantías de la tienda (una receta
  de cocina, el tiempo de mañana, ayuda con un programa, un resultado deportivo, una pregunta de cultura general, una
  traducción, una recomendación médica, el precio de otra tienda). Variar el registro (alguna educada, alguna abrupta,
  alguna mezclada con una frase sobre la tienda). La respuesta de referencia indica que el asistente solo ayuda con
  consultas de la tienda y **no contiene dígitos ni cantidades en palabras**; no responde a la pregunta ajena. Cerrar con la
  **puerta de revisión** (la persona revisora confirma los 8 casos). Depende de T018
- [X] T020 [US2] Comprobar la historia con un script desechable fuera del repositorio: 20 casos sin respuesta (12 del tema,
  8 `off_topic`) y 90 en total; `ids` `G001`–`G090` sin huecos ni repetidos; el 22,2 % es sin respuesta; ninguna
  `reference_answer` sin respuesta contiene un dígito; todas las `off_topic` tienen lista vacía (depende de T019)

**Checkpoint**: el conjunto tiene 90 casos con los dos tipos. US1 y US2 juntas cubren los dos criterios de aceptación de
Jira. US3 los hace sostenibles

---

## Phase 5: User Story 3 - Validación automática del archivo (Priority: P2)

**Goal**: una comprobación en la suite de pruebas que valida estructura, coherencia con el corpus y reparto, informa todos
los fallos juntos y se ejecuta con un comando que imprime el resumen.

**Independent Test**: introducir a propósito un error por regla (clave borrada, documento inexistente, pregunta duplicada,
conteo fuera de rango) y comprobar que la validación falla nombrando el caso y el código; revertirlo y comprobar que pasa
([quickstart.md](quickstart.md) §3).

### Tests for User Story 3 (escribir primero, deben fallar) ⚠️

- [X] T021 [P] [US3] Escribir `eval/tests/test_validation_case.py`: cada regla **por caso** de
  [contracts/golden-format.md](contracts/golden-format.md) con un caso válido y uno que falla, usando el corpus de
  juguete: `G-ID` (formato `G` + tres dígitos y repetido), `G-QUESTION-LENGTH` (9 y 301 caracteres fallan, 10 y 300 pasan),
  `G-ANSWER-LENGTH` (19 y 801 fallan, 20 y 800 pasan), `G-CATEGORY`, `G-DOC-COUNT` (tres documentos y un documento repetido),
  `G-DOC-MISSING` (se informa el id del documento y del caso), `G-CATEGORY-TOPIC` (categoría que no es el tema de ningún
  documento; `off_topic` con documentos; un caso de dos documentos de temas distintos pasa si coincide con uno),
  `G-NOANSWER-DIGITS` (un `7` en la respuesta de un caso sin respuesta falla; el mismo `7` en un caso con respuesta
  pasa), `G-QUESTION-VERBATIM` (una frase del documento, con otra capitalización y puntuación, falla) y `G-PII` (un correo
  `x@gmail.com` falla, `x@shoplab.example` pasa, ocho dígitos seguidos fallan, siete pasan). **Varios errores en un mismo
  caso se informan juntos**, y un caso con la estructura rota no impide evaluar los demás
- [X] T022 [P] [US3] Escribir `eval/tests/test_validation_set.py`: cada regla **de conjunto**: `G-COUNT` (79 y 101 casos
  fallan, 80 y 100 pasan), `G-PER-TOPIC` (11 con respuesta en un tema falla, 12 pasa), `G-UNANSWERED` (11 sin respuesta falla;
  12 pero por debajo del 15 % o por encima del 25 % falla), `G-UNANSWERED-KINDS` (4 del tema o 4 `off_topic` falla),
  `G-MULTI-DOC` (7 falla, 8 pasa), `G-COVERAGE` (un documento sin caso se lista por id) y `G-DUPLICATE` (dos preguntas
  iguales salvo mayúsculas, espacios o puntuación se listan con ambos ids). Un conjunto mínimo válido se construye con un
  helper que parametriza los umbrales de `GoldenRules`. **Varios fallos de conjunto y de caso a la vez salen todos juntos
  en un solo `InvalidGoldenError`** (US3 escenario 5), ordenados de forma estable
- [X] T023 [P] [US3] Escribir `eval/tests/test_summary_cli.py`: `summarize` devuelve el `GoldenSummary` de
  [data-model.md](data-model.md) (total, conteo por categoría, conteo por tipo, casos de dos documentos, documentos sin caso y el
  porcentaje informativo de preguntas con poco solapamiento léxico: «palabras de contenido» son las de **4 o más letras
  tras normalizar**; se cuentan las de la pregunta que aparecen en sus documentos esperados y se cuentan como «poco
  solapamiento» las preguntas con respuesta en que aparecen **menos de la mitad**; el resumen informa ese porcentaje frente
  al 30 % de FR-013 sin fallar); el comando se prueba **llamando a `main(argv)` en proceso** (sin subproceso): con un archivo
  válido imprime el resumen y devuelve `0`; con uno inválido imprime un fallo por línea y devuelve `1`;
  **dos ejecuciones sobre el mismo archivo imprimen exactamente el mismo texto** (US3 escenario 6); `--golden` y `--kb`
  cambian las rutas; sin argumentos usa las rutas por defecto resueltas desde la raíz del repositorio

### Implementation for User Story 3

- [X] T024 [US3] Crear `eval/src/shoplab_eval/validation.py` con las reglas **por caso** (T021): cada una devuelve
  `Issue` con su código del contrato, el `id` del caso y un mensaje en español accionable que cita el valor ofensor (p. ej.
  el id del documento inexistente). Constraints a respetar al pie de la letra: `id` «`G` + tres dígitos (`G001`…`G999`);
  único»; pregunta «10–300 caracteres» tras recortar espacios; respuesta «20–800 caracteres»; `expected_documents` «0, 1 o 2
  ids de documento, sin repetir y existentes en el corpus»; `category` «uno de `payments`, `privacy`, `returns`,
  `shipping`, `warranty`, `off_topic`». `G-NOANSWER-DIGITS` solo mira casos con lista vacía; `G-PII` rechaza correos que
  no terminan en `@shoplab.example` y secuencias de 8 o más dígitos; `G-QUESTION-VERBATIM` compara
  `normalize_text(question)` con el texto normalizado de cada documento (`in`). Un caso con la estructura rota se omite en
  las reglas que dependen de la parte dañada pero **no** en el resto. Hasta que T021 pase, no seguir (depende de T005, T006,
  T009, T011)
- [X] T025 [US3] Añadir a `eval/src/shoplab_eval/validation.py` las reglas **de conjunto** (T022) y la función
  `validate_cases(cases, issues_from_parse, corpus, rules=GoldenRules()) -> list[Issue]` que las evalúa todas, devuelve la
  lista **ordenada por línea y luego por código** y no se detiene en el primer fallo. Lee los umbrales solo de `GoldenRules`
  (nada de números sueltos en el código). `G-DUPLICATE` y `G-COVERAGE` listan los ids implicados. Hasta que T022 pase, no
  seguir (depende de T024; mismo archivo)
- [X] T026 [US3] Crear `eval/src/shoplab_eval/summary.py`: `GoldenSummary` (`@dataclass(frozen=True)`) y `summarize(cases,
  corpus) -> GoldenSummary` más `format_summary(summary) -> str` (una línea por dato, orden estable). El indicador léxico
  (palabras de contenido = 4 o más letras tras normalizar; poco solapamiento = menos de la mitad en los documentos
  esperados) se informa **frente al 30 % de FR-013 pero nunca provoca fallo** (plan R5 y contrato «Lo que no se valida
  automáticamente»). Hasta que T023 pase para
  `summarize`, no seguir (depende de T011, T009)
- [X] T027 [US3] Completar `eval/src/shoplab_eval/golden.py` con `load_golden(path=DEFAULT_GOLDEN_PATH, kb_dir=DEFAULT_KB_DIR)
  -> list[GoldenCase]` (lee, valida con `validate_cases` y lanza `InvalidGoldenError` con **todos** los fallos) y
  `validate_golden(path=..., kb_dir=...) -> GoldenSummary` (igual pero devuelve el resumen), como en el contrato «Interfaz
  de lectura» (depende de T025, T026)
- [X] T028 [US3] Crear `eval/src/shoplab_eval/__main__.py`: `python -m shoplab_eval validate [--golden <ruta>] [--kb <ruta>]`
  con `argparse`, con la lógica en `main(argv: list[str] | None = None) -> int` (para probarla sin subproceso) y
  `if __name__ == "__main__": sys.exit(main())`; en éxito imprime `format_summary` y devuelve `0`; en `InvalidGoldenError`
  imprime un fallo por línea y devuelve `1`; sin subcomando, muestra la ayuda y devuelve `2`. Solo biblioteca estándar
  (depende de T027)
- [X] T029 [US3] Exportar en `eval/src/shoplab_eval/__init__.py`: `load_golden`, `validate_golden`, `GoldenCase`,
  `InvalidGoldenError` (la interfaz del contrato, para LAB-28 a LAB-30). Ejecutar `cd eval && uv run ruff check . &&
  uv run pytest` y comprobar que `test_validation_case.py`, `test_validation_set.py` y `test_summary_cli.py` pasan con
  cobertura ≥ 80 % (depende de T028)

**Checkpoint**: cada regla se prueba con un caso válido y uno inválido; el comando funciona con el corpus de juguete. Aún no
se ha aplicado al dataset real

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: aplicar la validación al dataset real, registrar la revisión manual, documentar y comprobar de principio a fin

- [X] T030 Escribir `eval/tests/test_real_dataset.py`: (a) `load_golden()` sobre el `eval/golden.jsonl` y el `data/kb`
  reales no lanza error (SC-001) y devuelve entre 80 y 100 casos; (b) los 24 documentos del corpus están esperados al menos
  una vez; (c) **determinismo**: dos llamadas a `validate_golden()` y a `format_summary` dan resultados iguales (FR-016,
  US3 escenario 6); (d) un documento del corpus que desaparece lo detecta: se copia `data/kb` a `tmp_path`, se borra un
  documento esperado por algún caso y `load_golden(kb_dir=copia)` falla con `G-DOC-MISSING` por **cada** caso que lo espera
  (US3 escenario 4). Depende de T020 y T029
- [X] T031 Ejecutar `cd eval && uv run python -m shoplab_eval validate` sobre el dataset real y corregir en
  `eval/golden.jsonl` todo lo que falle (frases copiadas, dígitos en respuestas sin respuesta, reparto, duplicados) hasta
  obtener el resumen válido y código de salida `0`. **Comprobar además que el indicador léxico del resumen es ≥ 30 %**
  (FR-013; no hace fallar la prueba): si queda por debajo, reformular preguntas del dataset (no tocar la regla) y anotar el
  valor final para T033. No relajar ningún umbral ni regla para que pase: si una regla parece injusta, es una decisión de la
  spec (depende de T030)
- [X] T032 [P] Escribir `eval/README.md` (FR-017, SC-007): qué es el dataset y para quién; formato de cada caso con las tres
  líneas de ejemplo del contrato; las seis categorías y qué es un caso sin respuesta; cómo añadir un caso (siguiente `id`
  libre, redactar con los documentos delante, ejecutar `uv run python -m shoplab_eval validate`); cómo cambiar un caso
  (**sin cambiar el `id`**); qué hacer cuando un documento del corpus cambia, se renombra o se elimina (buscar su id en
  `golden.jsonl`, revisar los casos que lo esperan, anotarlo en el PR); cómo interpretar cada código `G-*` en una línea;
  la regla de revisión manual y `eval/REVIEW.md`; y que cambiar un umbral exige actualizar `spec.md` y `GoldenRules` a la
  vez. Sin datos personales; en español
- [ ] T033 Crear `eval/REVIEW.md` (plan R7, SC-005, **requiere a la persona revisora**: no se completa sin ella): encabezado
  con revisor y fecha; (1) una fila por tema (`payments`, `privacy`, `returns`, `shipping`, `warranty`, sin respuesta del
  tema y `off_topic`) con los ids revisados, revisor y fecha, pasada a limpio desde la **puerta de revisión** de T012–T019;
  (2) tabla con los **15 casos con respuesta elegidos al azar con semilla fija**, que se **registra** en el archivo
  (`random.Random(27).sample(...)` sobre los ids `G001`–`G070`, ver quickstart §4): id, documentos leídos, resultado
  correcto/incorrecto y observación; (3) la sección de los 12 casos sin respuesta del tema con las búsquedas hechas en T018
  (término, comando, resultado «sin coincidencias»); (4) la confirmación de que ninguna de las 20 respuestas sin respuesta
  escribe plazos en palabras; y (5) el valor final del indicador léxico de T031 frente al 30 %. Si un caso de la muestra
  falla, revisar todos los de su tema y anotarlo. Resultado esperado: 15 de 15 correctos
- [ ] T034 Ejecutar los escenarios de [quickstart.md](quickstart.md): §1 (`uv sync --frozen`, `ruff`, `pytest`), §2 (validar el
  dataset y leer el resumen; la segunda ejecución idéntica), §3 (un error por regla sobre una **copia** del archivo: cada
  uno falla con su código y el caso, y los errores juntos salen todos), §4 (la revisión manual de T033), §5 (añadir un caso
  siguiendo solo el README en menos de 10 minutos y deshacerlo). Anotar en `specs/LAB-27-golden-dataset/validation.md` el
  resultado de cada uno (depende de T031, T032, T033)
- [X] T035 Cierre de código: `cd eval && uv run ruff check . && uv run pytest` con cobertura ≥ 80 % y sin avisos; revisar
  que ninguna línea supera 121 caracteres, que los identificadores están en inglés y que no hay umbrales sueltos fuera de
  `GoldenRules` (`grep -rn "80\|100\|12" eval/src` para detectar números mágicos); y ejecutar `cd data/kb-tools && uv run
  pytest` para confirmar que el corpus no cambió (depende de T034)
- [X] T036 Limpieza antes de `git add`: `git status --short`, eliminar archivos temporales (scripts desechables de T017 y
  T020, `*.orig`, `.coverage`, `__pycache__`) y confirmar que `eval/.gitkeep` ya no está (T003). Revisar `git diff --staged`:
  solo `eval/`, `.github/workflows/ci.yml` (una clave) y `specs/LAB-27-golden-dataset/` (depende de T035)
- [ ] T037 Abrir el PR con título `feat(eval): [LAB-27] dataset golden de preguntas y respuestas` y comprobar en CI que el job
  `python` ejecuta el componente `eval`; para confirmar el filtro de T004, comprobar en un PR de prueba que solo toca
  `data/kb/**` que también aparece `eval` en la matriz (quickstart §6). **Después de fusionar, validar el primer tag
  `vX.Y.Z` antes de cerrar LAB-27 en Jira**. En la descripción del PR, la casilla de `.github/pull_request_template.md`
  «La evaluación RAG no empeora (desde F2)» se marca como **no aplicable** con esta explicación: esta historia **crea** el
  dataset golden que la constitución (principio V) exige; no cambia prompt, chunking, embeddings ni recuperación, y el
  cálculo de métricas y la puerta en los PR llegan en LAB-28 a LAB-30 (depende de T036)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias. T003 y T004 son paralelas a T002 una vez creada la carpeta `eval/`
- **Foundational (Phase 2)**: depende de Setup; **bloquea todas las historias**. T005 y T006 son paralelas; T007 va tras
  T003; T008 y T010 (pruebas) preceden a T009 y T011
- **US3 (Phase 5)**: solo depende de Foundational. **Se recomienda hacerla antes que US1 y US2**: el validador guía la
  redacción y evita reescribir 90 casos. Es P2 en la spec, no un bloqueo para el producto
- **US1 (Phase 3)**: depende de Foundational (necesita `parse_golden` en T017). Los datos pueden redactarse mientras US3 se
  implementa (archivos distintos)
- **US2 (Phase 4)**: depende de US1 para el archivo (T018 va tras T016); sus verificaciones usan el mismo lector
- **Polish (Phase 6)**: depende de US1, US2 y US3 completas

### User Story Dependencies

- **US1 (P1)**: independiente de US2 y US3 en lo funcional (datos con lector básico de Foundational)
- **US2 (P1)**: independiente de US3; comparte el archivo con US1, por lo que se hace después de ella
- **US3 (P2)**: independiente de los datos (se prueba con un corpus de juguete); la prueba contra el dataset real está en
  Polish (T030) porque falla hasta que existan los 90 casos

### Within Each User Story

- Las pruebas (T008, T010, T021–T023) se escriben y **fallan antes** de implementar
- Tipos → corpus → lectura → reglas por caso → reglas de conjunto → resumen → comando → exportaciones
- Datos: tema por tema, y antes de redactar, leer los documentos del tema

### Parallel Opportunities

- T003 y T004 en paralelo con T002 (archivos distintos)
- T005 y T006; T008 y T010 (pruebas de archivos distintos)
- T021, T022 y T023 (tres archivos de prueba)
- T032 (README) en paralelo con T030 y T031
- Con dos personas: una hace US3 (código) y otra redacta US1 y US2 (datos), porque no comparten archivos. Los casos de
  distintos temas no se marcan [P] porque comparten `eval/golden.jsonl`; si se redactan en borradores separados, se fusionan
  al final respetando los ids reservados

---

## Parallel Example: User Story 3

```bash
# Las tres pruebas de la historia, juntas:
Task: "Escribir eval/tests/test_validation_case.py (T021)"
Task: "Escribir eval/tests/test_validation_set.py (T022)"
Task: "Escribir eval/tests/test_summary_cli.py (T023)"
```

---

## Implementation Strategy

### MVP First (US1 + US2: los datos)

1. Phase 1 y 2: proyecto, tipos, corpus y lector.
2. Phase 3 (US1): 70 casos con respuesta, 14 por tema.
3. Phase 4 (US2): 20 casos sin respuesta.
4. **STOP y VALIDAR**: con un script desechable comprobar los dos criterios de aceptación de Jira. El MVP son los datos; sin
   la validación (US3) existen, pero no hay garantía de que sigan siendo válidos.

### Orden recomendado (el que evita retrabajo)

1. Setup → Foundational → **US3 completa (validador con corpus de juguete)** → US1 → US2 → Polish.
2. Redactar cada tema y ejecutar `uv run python -m shoplab_eval validate` tras cada uno (las reglas de conjunto fallarán
   hasta tener los 90 casos; las de caso sirven desde el primero).

### Incremental Delivery

1. Setup + Foundational → lector listo.
2. US3 → el validador está probado → red de seguridad.
3. US1 → 70 casos válidos. US2 → 90 casos y todos los criterios de Jira.
4. Polish → revisión manual registrada, README, quickstart y CI.

---

## Notes

- [P] = archivos distintos, sin dependencias pendientes; los datos de `golden.jsonl` son secuenciales por compartir archivo
- La **revisión humana** de las respuestas de referencia (la puerta de revisión de cada tema y T033) no es automatizable ni
  delegable en un modelo (plan R7); la implementación puede dejar el borrador, pero la confirmación por tema y el
  resultado «15 de 15» los firma la persona
- Si en la muestra de T033 falla un caso, **no basta con corregirlo**: se revisan todos los de su tema
- No relajar umbrales ni reglas para que pase la validación; cambiarlos es una decisión de `spec.md`
- Commit tras cada tarea o grupo lógico; el título del PR (T037) es el que lee release-please
- `eval/golden.jsonl`, `eval/REVIEW.md` y los casos sin datos personales: Constitución VI

---

## Estado al implementar (2026-11-16)

**Hecho**: Setup, Foundational y US3 completos (código, 95 pruebas, cobertura 98,7 %); `eval/golden.jsonl` con los 90 casos
redactados y válidos; `eval/README.md`; resultados en [validation.md](validation.md).

**Abierto, y por qué**:

- **T012–T016, T018, T019** (redacción por tema): los casos están redactados y pasan la validación, pero **no se marcan como
  hechos** porque la «puerta de revisión por tema» exige que la persona revisora lea cada caso contra sus documentos. Hasta
  entonces son un borrador.
- **T033** (`eval/REVIEW.md`): existe como borrador con la muestra de 15 casos (semilla 27) y las búsquedas ya hechas;
  **requiere la firma de la persona revisora**.
- **T034**: §1–§3 hechos; §4 depende de T033 y §5 de alguien ajeno al repositorio.
- **T037**: PR y verificación de CI; no se abre hasta que lo pidas. Tras fusionar, validar el primer tag `vX.Y.Z` antes de
  cerrar LAB-27 en Jira.
