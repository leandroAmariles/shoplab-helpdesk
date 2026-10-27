---

description: "Task list for LAB-18 - Corpus de políticas y FAQs de la tienda"
---

# Tasks: Corpus de políticas y FAQs de la tienda

**Input**: Design documents from `/specs/LAB-18-kb-policies-faqs-corpus/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`corpus-format.md`, `corpus-facts.md`,
`kb-tools-cli.md`), quickstart.md

**Tests**: Incluidos para la herramienta `kb-tools` (principio IV de la constitución: pruebas antes o junto con la
implementación; en cada historia las pruebas van primero y deben fallar antes de implementar). El contenido del corpus
se verifica con el propio validador y con la revisión manual.

**Organization**: Tareas agrupadas por historia de usuario. Las tres primeras son P1 y se entregan en orden porque
dependen del contenido: US1 (documentos) → US2 (PDF, necesita el Markdown) → US3 (revisión y coherencia, necesita los
documentos) → US4 (índice).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Corpus**: `data/kb/` (solo contenido: `.md`, `.pdf`, `INDEX.md`, `REVIEW.md`)
- **Herramienta**: `data/kb-tools/` (código en `src/kb_tools/`, pruebas en `tests/`)
- **Valores canónicos**: `specs/LAB-18-kb-policies-faqs-corpus/contracts/corpus-facts.md` (los documentos copian sus
  valores literalmente); formato: `contracts/corpus-format.md`
- No hay endpoints HTTP en esta historia: no se genera `requests.http`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: proyecto Python de la herramienta y CI

- [X] T001 Crear el proyecto `data/kb-tools/pyproject.toml` (Python 3.12; dependencias `fpdf2`, `markdown-it-py`, `pypdf`, `pyyaml`, `typer`; grupo dev `pytest`, `ruff` con `line-length = 121`; script `kb-tools = "kb_tools.cli:app"`; hatchling con `packages = ["src/kb_tools"]`; `pytest` con `pythonpath = ["src"]`, igual que `data/olist/pyproject.toml`) y los paquetes vacíos `data/kb-tools/src/kb_tools/__init__.py` y `data/kb-tools/tests/__init__.py`
- [X] T002 Generar `data/kb-tools/uv.lock` con `uv lock` y comprobar que `uv sync --frozen && uv run ruff check .` pasa en `data/kb-tools/` (la CI usa `uv sync --frozen`)
- [X] T003 [P] Añadir `data/kb-tools` al job `python` de `.github/workflows/ci.yml`: nueva salida `kb` del filtro de rutas (`'data/kb-tools/**'` y `'data/kb/**'`) y alta de `"data/kb-tools"` en la matriz `python_matrix`, igual que `data/olist`; no tocar `images.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: piezas comunes de la herramienta que usan todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T004 [P] Implementar `data/kb-tools/src/kb_tools/constants.py` con `@dataclass`/constantes en inglés: `TOPICS = ("shipping", "returns", "warranty", "payments", "privacy")`, `DOC_TYPES = ("policy", "faq")`, `LANGUAGE = "es"`, `MIN_DOCS = 20`, `MAX_DOCS = 30`, `MIN_PER_TOPIC = 3`, `ALLOWED_EMAIL_DOMAIN = "shoplab.example"`, `FILENAME_PATTERN = r"^(shipping|returns|warranty|payments|privacy)-\d{2}-[a-z0-9-]+$"`, `CONTROL_FILES = ("INDEX.md", "REVIEW.md")` y `PDF_DOCUMENT_IDS` con los 10 ids de `data-model.md` (shipping-01-delivery-times, shipping-02-shipping-costs, returns-01-return-policy, returns-03-refunds, warranty-01-legal-warranty, warranty-02-extended-warranty, payments-01-payment-methods, payments-02-installments, privacy-01-privacy-policy, privacy-03-data-rights)
- [X] T005 [P] Implementar `data/kb-tools/src/kb_tools/document.py`: `Document` (`id`, `title`, `topic`, `type`, `language`, `scope`, `body`, `path`, `has_pdf`) y `load_corpus(kb_dir)` que lee los `.md` (excluye `CONTROL_FILES`), separa el front matter YAML del cuerpo y devuelve los documentos junto con los errores de lectura (front matter ausente, YAML inválido) en lugar de lanzar excepciones
- [X] T006 [P] Implementar `data/kb-tools/tests/conftest.py` con un constructor `make_corpus(tmp_path, ...)` que escribe un corpus mínimo válido (3 documentos por tema, front matter correcto, `INDEX.md` y `REVIEW.md` coherentes) y permite alterarlo (quitar documento, cambiar tema, añadir PDF) para las pruebas negativas
- [X] T007 Implementar `data/kb-tools/src/kb_tools/validate.py` (esqueleto): `Issue(code, path, message)`, `Options(min_docs, max_docs, min_per_topic, require_reviewed)`, `validate(kb_dir, options) -> list[Issue]` que por ahora solo propaga los errores de lectura de `load_corpus`, y `format_issue()` con el formato `KB006 data/kb/INDEX.md: mensaje accionable`
- [X] T008 Implementar `data/kb-tools/src/kb_tools/cli.py` (typer): comando `validate` con `--kb-dir` (por defecto `../kb`), `--require-reviewed`, `--min-docs`, `--max-docs`, `--min-per-topic` según `contracts/kb-tools-cli.md`; imprime cada `Issue` y el resumen `N documentos, M PDF, E errores`; código de salida 0 sin errores y 1 con al menos uno

**Checkpoint**: Foundation ready - las historias pueden empezar

---

## Phase 3: User Story 1 - Base de conocimiento publicada con cobertura completa (Priority: P1) 🎯 MVP

**Goal**: 24 documentos Markdown en `data/kb/` que cubren los cinco temas, autosuficientes y con valores concretos.

**Independent Test**: `uv run kb-tools validate` en `data/kb-tools/` no reporta `KB001`, `KB002`, `KB004`, `KB005` ni
`KB010`; hay 24 documentos y cada tema tiene ≥ 3; cada documento se lee de principio a fin sin ayuda de otro.

### Tests for User Story 1 ⚠️

> Escribir primero y comprobar que **fallan** antes de implementar.

- [X] T009 [US1] Escribir `data/kb-tools/tests/test_validate_content.py`: corpus válido → sin errores; 19 y 31 documentos → `KB001`; un tema con 2 documentos → `KB002`; nombre `Returns_1.md` o `topic` del front matter distinto del prefijo → `KB004`; front matter sin `scope`, `type` fuera de `DOC_TYPES` o `title` duplicado → `KB005`; carácter fuera de latin-1 (comillas tipográficas, guion largo, emoji) en el cuerpo → `KB010`

### Implementation for User Story 1

- [X] T010 [US1] Implementar en `data/kb-tools/src/kb_tools/validate.py` las reglas `KB001` (recuento por `id`, no por archivo), `KB002`, `KB004`, `KB005` y `KB010` con los mensajes del contrato `kb-tools-cli.md`; pasan los tests de T009
- [X] T011 [P] [US1] Redactar `data/kb/shipping-01-delivery-times.md` (policy): plazos de despacho (hasta 2 días hábiles), estándar (5 a 10 días hábiles) y exprés (2 a 4 días hábiles), cuándo empieza a contar y relación con los estados `approved`, `invoiced`, `processing`, `shipped`, `delivered`; valores de `corpus-facts.md` § Envíos
- [X] T012 [P] [US1] Redactar `data/kb/shipping-02-shipping-costs.md` (policy): R$ 19,90 estándar, R$ 39,90 exprés, envío estándar gratis desde R$ 200,00, cómo se muestra el costo antes de pagar; tabla de costos
- [X] T013 [P] [US1] Redactar `data/kb/shipping-03-order-tracking.md` (policy): código de seguimiento dentro de 24 horas tras el despacho por correo, cómo seguir un pedido, qué significa cada estado de pedido (`created`…`canceled`, tabla de `corpus-facts.md` § Estados de pedido)
- [X] T014 [P] [US1] Redactar `data/kb/shipping-04-delivery-issues.md` (policy): pedido retrasado (investigación tras 10 días hábiles de retraso), extraviado (reenvío o reembolso total, resolución en 10 días hábiles), dañado o incorrecto (avisar dentro de 7 días corridos desde la entrega)
- [X] T015 [P] [US1] Redactar `data/kb/shipping-05-faq-shipping.md` (faq): 6–8 preguntas frecuentes de envíos con los mismos valores que shipping-01 a 04 (tiempos, costo, gratis, seguimiento, dañado, retrasado)
- [X] T016 [P] [US1] Redactar `data/kb/returns-01-return-policy.md` (policy): derecho de arrepentimiento de 7 días corridos (devolución gratis, reembolso total con flete), devolución por cambio de opinión hasta 30 días (del día 8 al 30 el flete de R$ 19,90 lo paga el cliente), defectuoso o distinto gratis dentro de 30 días, productos no devolvibles, estado del producto exigido
- [X] T017 [P] [US1] Redactar `data/kb/returns-02-return-process.md` (policy): pasos para devolver (solicitud por formulario o correo de soporte, etiqueta, embalaje, envío), inspección en hasta 3 días hábiles, resultados posibles
- [X] T018 [P] [US1] Redactar `data/kb/returns-03-refunds.md` (policy): reembolso hasta 10 días hábiles tras aprobar la inspección, al medio de pago original, boleto por transferencia bancaria, reembolso del vale, qué incluye el importe
- [X] T019 [P] [US1] Redactar `data/kb/returns-04-exchanges-and-cancellations.md` (policy): cambio dentro de 30 días sujeto a stock (si no hay, reembolso), cancelación sin costo en `created`/`approved`/`invoiced`/`processing`, pedido `shipped` (rechazar entrega o devolver), pedido `unavailable` (cancelación y reembolso automáticos)
- [X] T020 [P] [US1] Redactar `data/kb/returns-05-faq-returns.md` (faq): 6–8 preguntas frecuentes (plazo, costo, productos no devolvibles, cuándo llega el reembolso, cancelar, cambio) con los valores de returns-01 a 04
- [X] T021 [P] [US1] Redactar `data/kb/warranty-01-legal-warranty.md` (policy): garantía legal de 90 días corridos desde la entrega, qué cubre, exclusiones (mal uso, caídas, líquidos, reparaciones de terceros, desgaste normal), resolución (reparación en hasta 30 días corridos; si no, cambio o reembolso)
- [X] T022 [P] [US1] Redactar `data/kb/warranty-02-extended-warranty.md` (policy): garantía extendida opcional +12 meses tras la legal, costo 10 % del precio, solo productos desde R$ 300,00, contratación solo al comprar hasta el pago, mismas exclusiones
- [X] T023 [P] [US1] Redactar `data/kb/warranty-03-warranty-claims.md` (policy): cómo reclamar (formulario de contacto con número de pedido y descripción), diagnóstico gratuito, plazos de resolución, qué pasa si no se repara
- [X] T024 [P] [US1] Redactar `data/kb/warranty-04-faq-warranty.md` (faq): 5–7 preguntas frecuentes (duración, qué cubre, extendida, cómo reclamar, costo del diagnóstico, qué excluye)
- [X] T025 [P] [US1] Redactar `data/kb/payments-01-payment-methods.md` (policy): métodos aceptados `credit_card`, `debit_card`, `boleto`, `voucher` (nombres en lenguaje natural más el valor entre comillas de código), aprobación de tarjeta inmediata o hasta 30 minutos, débito en pago único, ShopLab no almacena número completo ni código de seguridad
- [X] T026 [P] [US1] Redactar `data/kb/payments-02-installments.md` (policy): hasta 12 cuotas con tarjeta de crédito, sin interés hasta 3 cuotas, cuota mínima R$ 30,00, ejemplo con importes ficticios, relación con el reembolso
- [X] T027 [P] [US1] Redactar `data/kb/payments-03-boleto-and-vouchers.md` (policy): boleto vence a los 3 días corridos, se aprueba hasta 2 días hábiles tras el pago, sin pago al vencer el pedido pasa a `canceled`; vale con vigencia de 12 meses, combinable, no canjeable por dinero
- [X] T028 [P] [US1] Redactar `data/kb/payments-04-payment-issues.md` (policy): pago rechazado (hasta 3 intentos en 24 horas), cobro duplicado (reembolso en 10 días hábiles tras confirmarlo), pago no reconocido, seguridad de los datos de tarjeta
- [X] T029 [P] [US1] Redactar `data/kb/payments-05-faq-payments.md` (faq): 6–8 preguntas frecuentes (métodos, cuotas, boleto, vale, rechazo, cobro duplicado, seguridad)
- [X] T030 [P] [US1] Redactar `data/kb/privacy-01-privacy-policy.md` (policy): datos que se recogen, finalidades, nunca se venden a terceros, conservación (datos fiscales 5 años; cuenta hasta que se pida eliminarla), canal `privacidad@shoplab.example`
- [X] T031 [P] [US1] Redactar `data/kb/privacy-02-data-collection-and-use.md` (policy): detalle por categoría de dato (identificación, entrega, historial, pago tokenizado, valoraciones), uso de las conversaciones con el asistente, datos de ejemplo del sistema anonimizados
- [X] T032 [P] [US1] Redactar `data/kb/privacy-03-data-rights.md` (policy): derechos de acceso, corrección, portabilidad, oposición y eliminación; cómo ejercerlos por `privacidad@shoplab.example`; respuesta en 15 días corridos; datos fiscales que se conservan 5 años aunque se pida eliminar
- [X] T033 [P] [US1] Redactar `data/kb/privacy-04-cookies.md` (policy): cookies esenciales (siempre activas), de analítica y de marketing (requieren consentimiento), cómo cambiar la preferencia
- [X] T034 [P] [US1] Redactar `data/kb/privacy-05-faq-privacy.md` (faq): 5–7 preguntas frecuentes (qué datos, si se venden, cómo eliminar, plazo de respuesta, cookies, asistente)
- [X] T035 [US1] Ejecutar `uv run kb-tools validate` en `data/kb-tools/` y confirmar que no aparecen `KB001`, `KB002`, `KB004`, `KB005` ni `KB010` (los errores de índice, revisión y PDF se esperan hasta US2–US4); corregir nombres, front matter o caracteres según el mensaje

**Checkpoint**: US1 completa - 24 documentos en 5 temas; MVP de contenido listo para US2–US4

---

## Phase 4: User Story 2 - Contenido en Markdown y en PDF (Priority: P1)

**Goal**: 10 PDF generados desde el Markdown, con texto seleccionable y contenido equivalente.

**Independent Test**: `uv run kb-tools build-pdf` genera 10 PDF y una segunda ejecución informa `unchanged`;
`build-pdf --check` termina con código 0; `validate` no reporta `KB003` ni `KB008`.

### Tests for User Story 2 ⚠️

- [X] T036 [P] [US2] Escribir `data/kb-tools/tests/test_pdf.py`: el PDF generado desde un Markdown de ejemplo se abre con `pypdf`, contiene texto extraíble y ninguna imagen; dos generaciones consecutivas producen los mismos bytes; el texto normalizado del PDF coincide con el del Markdown (títulos, listas y tablas incluidas)
- [X] T037 [P] [US2] Escribir `data/kb-tools/tests/test_validate_pdf.py`: tema sin ningún PDF → `KB003`; PDF cuyo texto difiere de su Markdown, PDF vacío o archivo no PDF → `KB008`; corpus con todos los PDF al día → sin errores

### Implementation for User Story 2

- [X] T038 [US2] Implementar `data/kb-tools/src/kb_tools/pdf.py`: `render_pdf(document) -> bytes` con `markdown-it-py` y `fpdf2` (fuentes estándar, títulos, párrafos, listas, tablas simples, énfasis), normalización de comillas y guiones tipográficos a ASCII, fecha de creación fija e identificador determinista para salida reproducible, sin imágenes; y `extract_text(pdf_bytes)` con `pypdf` para comparar
- [X] T039 [US2] Añadir a `data/kb-tools/src/kb_tools/validate.py` las reglas `KB003` (tema sin PDF) y `KB008` (PDF ilegible, sin texto o con texto distinto al del Markdown, comparando texto normalizado sin espacios ni marcas de formato)
- [X] T040 [US2] Añadir el comando `build-pdf` a `data/kb-tools/src/kb_tools/cli.py` con `--kb-dir`, `--only ID` (repetible) y `--check` según `contracts/kb-tools-cli.md`; por defecto genera los documentos de `PDF_DOCUMENT_IDS`; imprime `ok`/`unchanged` por documento y el total
- [X] T041 [US2] Generar los 10 PDF con `uv run kb-tools build-pdf` en `data/kb-tools/` (salen en `data/kb/<id>.pdf`) y comprobar que una segunda ejecución informa `unchanged` y que `build-pdf --check` termina con código 0
- [X] T042 [US2] Abrir al menos `data/kb/returns-01-return-policy.pdf` y `data/kb/payments-01-payment-methods.pdf` y comprobar visualmente que las tablas, listas y acentos se ven bien y que el texto se puede seleccionar y copiar; corregir `pdf.py` si no

**Checkpoint**: US1 y US2 funcionan - corpus en ambos formatos y equivalentes

---

## Phase 5: User Story 3 - Contenido revisado a mano y coherente con los datos de la tienda (Priority: P1)

**Goal**: registro de revisión, ausencia de datos personales y coherencia entre documentos y con Olist.

**Independent Test**: `uv run kb-tools validate --require-reviewed` termina con código 0 solo cuando todos los
documentos figuran `reviewed` con revisor y fecha; ningún documento contradice a `corpus-facts.md`; no hay `KB009`.

### Tests for User Story 3 ⚠️

- [X] T043 [P] [US3] Escribir `data/kb-tools/tests/test_validate_review.py`: `REVIEW.md` con un documento de menos o de más → `KB007`; fila `reviewed` sin revisor o sin fecha ISO → `KB007`; con `require_reviewed` y una fila `pending` → `KB011`; sin `require_reviewed` esa misma fila no da error
- [X] T044 [P] [US3] Escribir `data/kb-tools/tests/test_validate_pii.py`: correo `ana@gmail.com` → `KB009`; `soporte@shoplab.example` y `privacidad@shoplab.example` permitidos; teléfonos `+55 11 91234-5678` y `(11) 3456-7890` → `KB009`; `R$ 1.234.567,00` y `PED-0001` no disparan; un CPF `123.456.789-09` → `KB009`

### Implementation for User Story 3

- [X] T045 [US3] Añadir a `data/kb-tools/src/kb_tools/validate.py` la regla `KB009` con los patrones de `contracts/kb-tools-cli.md` (correo fuera de `ALLOWED_EMAIL_DOMAIN`, secuencia de 9 o más dígitos con separadores opcionales no precedida por `R$`, CPF `\d{3}\.\d{3}\.\d{3}-\d{2}`), con el número de línea en el mensaje
- [X] T046 [US3] Añadir a `data/kb-tools/src/kb_tools/validate.py` la lectura de `data/kb/REVIEW.md` y las reglas `KB007` (conjunto de ids distinto del directorio; `reviewed` sin revisor o sin fecha `YYYY-MM-DD`) y `KB011` (solo con `require_reviewed`: fila `pending`)
- [X] T047 [US3] Crear `data/kb/REVIEW.md` con una fila por cada uno de los 24 documentos (`id`, `reviewer` vacío, `date` vacío, `status = pending`) en el formato de `contracts/corpus-format.md`, ordenadas por id
- [X] T048 [US3] Pasada de coherencia: releer los 24 documentos contra `contracts/corpus-facts.md` y listar cualquier plazo, importe o condición distinto (también redacciones como «un mes» en vez de «30 días corridos»); corregir los documentos, no la tabla, salvo que la tabla esté mal
- [X] T049 [US3] Verificar los nombres de métodos de pago y estados citados contra el dato real: ejecutar `SELECT DISTINCT payment_type FROM orders.order_payments` y `SELECT DISTINCT order_status FROM orders.orders` en la base local con el dataset de LAB-17 cargado, o, si no está cargado, contra los CSV de `data/raw/`; ajustar `payments-01`, `payments-03`, `shipping-03`, `returns-04` y la tabla de estados de `corpus-facts.md` si algún valor difiere
- [X] T050 [US3] **[Manual - administrador]** Leer cada documento, marcar su fila de `data/kb/REVIEW.md` como `reviewed` con revisor y fecha, y corregir lo que corresponda. No lo hace el agente: el criterio de la historia es «revisados a mano». Sin esta tarea el CI (`--require-reviewed`) falla

**Checkpoint**: US1–US3 funcionan - corpus coherente, sin datos personales y con revisión registrada

---

## Phase 6: User Story 4 - Corpus fácil de mantener y de localizar por tema (Priority: P2)

**Goal**: índice que refleja exactamente el directorio y guía para añadir documentos.

**Independent Test**: `validate` no reporta `KB006`; el índice lista los 24 documentos con tema, título y formatos;
añadir un documento de prueba que respeta la convención solo exige tocar `INDEX.md` y `REVIEW.md`.

### Tests for User Story 4 ⚠️

- [X] T051 [P] [US4] Escribir `data/kb-tools/tests/test_validate_index.py`: índice con un documento de menos, con uno de más o con `formats` que no refleja un PDF existente → `KB006`; título del índice distinto del `title` del front matter → `KB006`; índice coherente → sin errores

### Implementation for User Story 4

- [X] T052 [US4] Añadir a `data/kb-tools/src/kb_tools/validate.py` la regla `KB006` (ids, `title`, `topic`, `type` y `formats` del `INDEX.md` iguales a los de los archivos, con mensaje del tipo `falta el documento 'returns-03-refunds' (añádelo a la tabla)`)
- [X] T053 [US4] Crear `data/kb/INDEX.md` con la tabla de los 24 documentos (`id`, `title`, `topic`, `type`, `formats`) en el formato de `contracts/corpus-format.md`, ordenada por id, tomando `title` y `type` del front matter y `formats` de los PDF existentes
- [X] T054 [P] [US4] Escribir `docs/base-conocimiento.md`: propósito del corpus, tabla de temas y cantidad de documentos, convención de nombres, formato del documento (resumen del contrato), cómo añadir una política (archivo, `INDEX.md`, `REVIEW.md`, `build-pdf`, `validate`), cómo regenerar los PDF, y la regla «el Markdown prevalece sobre el PDF»; en español, enlazando a `data/kb-tools/README.md`
- [X] T055 [P] [US4] Escribir `data/kb-tools/README.md`: requisitos, comandos `build-pdf` y `validate` con sus opciones y la tabla de códigos `KB001`–`KB011`, y cómo ejecutar `pytest` y `ruff`

**Checkpoint**: las cuatro historias funcionan de forma independiente

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T056 [P] Añadir `data/kb-tools/tests/test_real_corpus.py`: ejecuta `validate(Path("../kb"), Options(require_reviewed=True))` sobre el corpus real y espera cero errores (falla mientras haya documentos en `pending`: es el control de entrega de D-9)
- [X] T057 [P] Añadir a `README.md` una línea breve con enlace a `docs/base-conocimiento.md` en la sección de datos
- [X] T058 Ejecutar en `data/kb-tools/`: `uv sync --frozen`, `uv run ruff check .`, `uv run pytest` y `uv run kb-tools validate --require-reviewed`; todo en verde (requiere T050)
- [X] T059 Recorrer `quickstart.md` de punta a punta (pasos 1–6) y marcar cada verificación manual de la tabla del paso 4
- [X] T060 Checklist previo al commit (`.ai/rules/development-guidelines.md` § 8): `git status --short` sin `*.orig`, `*.bak`, `__pycache__`, `.venv`, `.pytest_cache`, `.ruff_cache` ni `uv` caché; `git diff --staged` solo con cambios de esta historia (los archivos ya modificados de `.ai/`, `.claude/`, `.gitignore` y `CLAUDE.md` no pertenecen a LAB-18); comprobar que `.gitignore` cubre `data/kb-tools/.venv/`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; bloquea todas las historias. T007 depende de T004–T005; T008 de T007.
- **US1 (Phase 3)**: depende de Foundational. T010 depende de T009; T011–T034 son independientes entre sí; T035 depende
  de T010 y de los 24 documentos.
- **US2 (Phase 4)**: depende de US1 (necesita el Markdown). T039 depende de T038; T040 de T038; T041 de T040 y de que
  el contenido de US1 esté estable (un cambio posterior obliga a regenerar).
- **US3 (Phase 5)**: depende de US1 (y de US2 para validar los PDF). T048 y T049 dependen de los documentos; T050
  depende de T047, T048 y T049 y es manual.
- **US4 (Phase 6)**: depende de US1 y US2 (los `formats` del índice). T053 depende de T041.
- **Polish (Phase 7)**: T056 y T058 dependen de T050; T060 es lo último.

### User Story Dependencies

- **US1** es el MVP: sin documentos no hay nada que convertir, revisar ni indexar.
- **US2, US3, US4** parten del contenido de US1, pero tocan archivos distintos (`pdf.py`, `REVIEW.md`/regla de PII,
  `INDEX.md`) y se pueden probar por separado con el corpus mínimo de `conftest.py`.
- Cualquier cambio de un valor en `corpus-facts.md` o de un documento tras su revisión vuelve las filas afectadas a
  `pending` (T050 se repite).

### Parallel Opportunities

- T003 en paralelo con T001–T002; T004, T005 y T006 en paralelo.
- T011–T034 (24 documentos) en paralelo: cada uno es un archivo distinto; se sugiere por tema (5 grupos) para que el
  mismo redactor mantenga la coherencia interna del tema.
- T036/T037, T043/T044, T051 y T054/T055 son pruebas o documentos en archivos distintos.

#### Ejemplo de paralelismo en US1

```text
Grupo envíos:        T011  T012  T013  T014  T015
Grupo devoluciones:  T016  T017  T018  T019  T020
Grupo garantías:     T021  T022  T023  T024
Grupo pagos:         T025  T026  T027  T028  T029
Grupo privacidad:    T030  T031  T032  T033  T034
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Completar Setup y Foundational (T001–T008).
2. US1: pruebas y reglas de contenido (T009–T010), luego los 24 documentos (T011–T034) y T035.
3. **Parar y validar**: los 24 documentos cubren los cinco temas. Es el contenido que necesitan F1-02 en adelante.

### Entrega incremental

1. US1 → corpus en Markdown (MVP).
2. US2 → PDF reproducibles y equivalentes.
3. US3 → revisión, coherencia y ausencia de datos personales (T050 es manual).
4. US4 → índice y guía de mantenimiento.
5. Polish → prueba sobre el corpus real, quickstart y limpieza.

### Notas

- Los documentos los redacta el agente a partir de `corpus-facts.md`; la revisión manual (T050) no se delega.
- Mantener las líneas de código Python en ≤ 121 caracteres; identificadores y nombres de archivo en inglés.
- Commits atómicos por historia, p. ej. `feat(kb): [LAB-18] add shipping and returns documents`.
