# Research: Dataset golden de preguntas y respuestas

Decisiones técnicas que la especificación deja abiertas. No quedan marcadores `NEEDS CLARIFICATION`.

## R1 — Dónde viven los datos y el código

- **Decisión**: un proyecto uv en `eval/` con diseño `src/` (paquete `shoplab_eval`), el dataset en `eval/golden.jsonl` y
  la configuración como en `ingest/` y `notebooks/` (`requires-python >= 3.12`, `ruff` a 121 columnas, `pytest` con
  `--cov-fail-under=80`).
- **Razón**: la constitución asigna `eval/` al «golden dataset and metrics», el criterio de aceptación fija la ruta del
  archivo y el filtro `eval` de `ci.yml` y la matriz de Python ya lo prevén (basta que exista `eval/pyproject.toml` para
  que el job deje de saltarlo, ver R8). El diseño `src/` deja que LAB-28 a LAB-30 añadan módulos sin tocar el lector.
- **Alternativas**: solo el `.jsonl` con un script suelto (sin pruebas ni reutilización); ponerlo en `notebooks/` (esa
  carpeta es de experimentos y el código de producción no la importa); en `ingest/` (es un servicio de producción y el
  dataset no lo es).

## R2 — Formato de cada caso

- **Decisión**: JSON Lines en UTF-8 sin BOM, un objeto por línea con las claves en inglés `id`, `question`,
  `reference_answer`, `expected_documents` (lista de ids de documento) y `category`. El texto de los valores, en español.
  Sin comentarios ni cabecera (FR-015). Se rechazan claves desconocidas, como hace LAB-26 con su TOML.
- **Razón**: el formato lo impone el criterio de aceptación; es legible línea a línea con cualquier lector estándar, un
  caso nuevo es una línea añadida (diff mínimo) y no exige cargar el archivo entero. La constitución pide identificadores
  en inglés y datos en español.
- **Alternativas**: un array JSON (un solo cambio rompe todo el archivo y el diff es peor); TOML como LAB-26 (cómodo para
  20 casos, pero no es lo que pide la historia y no se lee por líneas); CSV (las listas y las comas dentro del texto
  complican la lectura).

## R3 — Cómo se representa «sin respuesta» y la categoría

- **Decisión**: un caso es sin respuesta si y solo si `expected_documents` es una lista vacía; no se añade un campo
  `answerable`. La `category` es uno de los cinco temas del corpus (`payments`, `privacy`, `returns`, `shipping`,
  `warranty`) o `off_topic` para preguntas ajenas a la tienda. Un caso del tema sin respuesta conserva la categoría de su
  tema; `off_topic` exige lista vacía.
- **Razón**: un campo aparte puede contradecir la lista (`answerable = true` con lista vacía) y obligaría a una regla más.
  Derivarlo de la lista deja una sola fuente de verdad. Usar el tema como categoría permite medir por separado cada
  tema, incluidas sus preguntas sin respuesta (spec, US2 escenario 5).
- **Alternativas**: `category` con valores en español (rompe la convención de la constitución para identificadores y no
  coincide con el `topic` del corpus); un valor `no_answer` como categoría (perdería el tema al que pertenece la
  pregunta).

## R4 — Identificación del documento esperado

- **Decisión**: el id es el nombre del archivo Markdown sin extensión (`payments-03-boleto-and-vouchers`), el mismo id
  que usa `data/kb/INDEX.md` y las preguntas de LAB-26. Los controles `INDEX.md` y `REVIEW.md` no son documentos. La
  columna `retrieval.document.source` guarda el nombre del archivo con extensión (`LAB-19`, `LAB-25`), así que quien
  compare con el resultado de la recuperación (LAB-28) añade `.md`.
- **Razón**: es estable, legible, no depende del formato (el PDF comparte nombre; el Markdown es la fuente de verdad) y
  ya es la convención del repositorio. Atar el dataset a un id de base de datos lo haría depender de una ingesta.
- **Alternativas**: el nombre con extensión (ambiguo si hubiera `.md` y `.pdf`); el título del documento (cambia sin
  que cambie el contenido); el id numérico de `retrieval.document` (depende de cada base de datos).

## R5 — Reglas de validación y cuáles son automáticas

- **Decisión**: la validación evalúa todo y devuelve todos los fallos juntos (`InvalidGoldenError`), cada uno con código,
  `id` del caso (o `SET` si es del conjunto) y mensaje en español. Las reglas completas con sus códigos están en
  [contracts/golden-format.md](contracts/golden-format.md); resumen:
  - **Por caso**: claves obligatorias y desconocidas, tipos, id con formato `G` + tres dígitos y único, pregunta de 10 a 300
    caracteres, respuesta de referencia de 20 a 800, categoría válida, documentos esperados existentes, sin repetir y
    como máximo dos, categoría coherente con el tema de los documentos, `off_topic` sin documentos, respuesta de un caso
    sin respuesta **sin ninguna cifra** (FR-011: sin plazos ni importes inventados), sin correos ajenos a
    `@shoplab.example` ni secuencias de ocho o más dígitos (Constitución VI).
  - **De conjunto**: 80–100 casos; al menos 12 con respuesta por tema; sin respuesta entre el 15 % y el 25 % y no menos
    de 12, de ellos al menos 5 del tema y 5 `off_topic`; al menos 8 con dos documentos; cada documento del corpus
    esperado al menos una vez; preguntas sin duplicados tras normalizar mayúsculas, espacios y puntuación (FR-012).
  - **Contra el corpus**: una pregunta no puede ser una frase copiada tal cual de un documento (se compara normalizada);
    un documento esperado que no existe falla indicando los casos afectados (US3 escenario 4).
- **Lo que NO se automatiza** y queda en revisión manual registrada (R7): que la respuesta de referencia sea cierta, que
  la pregunta sin respuesta del tema no se pueda contestar con ningún documento, y que una parte significativa de las
  preguntas use palabras distintas al documento (FR-013). Para esto último el resumen del comando imprime, **a título
  informativo y sin fallar**, el porcentaje de preguntas con respuesta cuyas palabras de contenido (palabras de 4 o más
  letras tras normalizar) aparecen menos de la mitad en sus documentos esperados. FR-013 fija el umbral en **al menos el
  30 %**; se verifica al validar el dataset real (T031) y se registra en `eval/REVIEW.md`, pero no hace fallar la prueba,
  porque no hay datos todavía para saber si el 30 % es el valor correcto: si el dataset real queda por debajo, se
  reformulan preguntas, y si el umbral resulta injusto se cambia en la spec, no en el código.
- **Alternativas**: validar con un modelo de lenguaje que la respuesta sale del documento (rompe FR-016, no es
  determinista); un umbral de solapamiento léxico como puerta (no calibrable hasta tener los casos); un esquema JSON
  Schema externo (no puede expresar las reglas de conjunto ni el cruce con el corpus, y añade una dependencia).

## R6 — Composición del conjunto

- **Decisión**: 90 casos. 70 con respuesta, 14 por tema (cada tema tiene 4 o 5 documentos, lo que permite que todos
  aparezcan como esperados), con al menos 8 de dos documentos repartidos entre los temas. 20 sin respuesta: 12 del tema
  (3 de `payments`, 2 de `privacy`, 3 de `returns`, 2 de `shipping`, 2 de `warranty`) y 8 `off_topic`, es decir, el 22 %.
- **Razón**: está dentro de todos los rangos de la spec con margen (el 15 % de 80 son 12, el 25 % de 100 son 25) y el
  total 90 deja espacio para 10 casos más sin romper el máximo, p. ej. los que LAB-33 promueva desde el feedback.
  Dentro de un tema, la mayoría de los casos con respuesta se reparten entre las FAQs y las políticas, porque el
  cliente real pregunta en el lenguaje de una FAQ pero la respuesta detallada está en la política.
- **Alternativas**: 80 casos (sin margen para crecer); 100 (sin margen, y más coste de revisión manual para un primer
  dataset); proporciones iguales entre tipos (menos realista: el cliente pregunta más lo que sí hay que lo que no).

## R7 — Cómo se redactan y se revisan los casos

- **Decisión**: se redactan contra el corpus abierto, tema a tema, con ayuda de un modelo si se desea; **cada caso lo
  revisa una persona** contra sus documentos esperados antes de entrar. Las 20 preguntas de LAB-26
  (`notebooks/data/questions.toml`) se pueden tomar como punto de partida copiándolas a mano; el dataset no las importa
  ni depende de ellas. Para cada caso sin respuesta del tema, la revisión busca el dato en `data/kb` (búsqueda de
  texto de los términos clave) y lo registra. La revisión se deja en `eval/REVIEW.md`: revisor, fecha, una fila por tema
  que confirma que la persona revisó **todos** sus casos, la lista de los 15 casos de la muestra aleatoria con la semilla
  usada y su resultado (SC-005) y la lista de búsquedas hechas para los casos sin respuesta del tema. La muestra de 15 es la
  segunda comprobación, independiente de la primera revisión caso a caso.
- **Razón**: el corpus es sintético y un modelo puede inventar una política con aspecto verosímil; la revisión humana es
  la única defensa contra una respuesta de referencia falsa, que contaminaría todas las métricas. Registrarla junto a los
  datos sigue el patrón de `data/kb/REVIEW.md`.
- **Alternativas**: generar el dataset de forma automática a partir de los documentos (preguntas demasiado cercanas al
  texto, sin los casos sin respuesta ni los de dos documentos); revisión solo en el PR (no queda versionada).

## R8 — Integración en CI

- **Decisión**: el job `python` de `ci.yml` ya recorre una matriz que incluye el componente `eval` cuando cambia `eval/**`
  y se salta con un aviso si `eval/pyproject.toml` no existe. Al crear el proyecto, el job pasa a ejecutar
  `uv sync --frozen`, `uv run ruff check .` y `uv run pytest`, que es justo la validación del dataset (R5) más las
  pruebas del paquete. El único cambio de CI es añadir `data/kb/**` al filtro `eval`, con un comentario como el de los
  filtros vecinos.
- **Razón**: el corpus puede cambiar sin tocar `eval/`; sin el filtro, un PR que borra un documento esperado pasaría
  en verde. La matriz de selección ya existe, no hace falta un job nuevo.
- **Alternativas**: un job nuevo `eval-dataset` (duplica la lógica de detección de proyecto y de instalación); validar solo
  en local (rompe la regla de que la comprobación forma parte de la suite del proyecto, FR-014).

## R9 — No hace falta ADR

- **Decisión**: no se crea un ADR. La ubicación y el formato vienen fijados por la historia y la constitución, no hay
  una decisión de base de datos, mensajería, extracción de módulo ni estrategia de recuperación (Constitución VII).
  Si LAB-31 decide persistir los casos en el esquema `eval`, esa decisión sí merecerá su ADR y reutilizará este lector.

## Riesgos y cómo se tratan

- **Una respuesta de referencia falsa contamina todas las métricas**: es el riesgo principal; lo cubre la revisión
  humana registrada (R7) y la muestra del 100 % de SC-005. Si en la muestra falla un caso, se revisan todos los de su
  tema.
- **El corpus cambia y el caso queda obsoleto sin romper la estructura**: la validación solo detecta ids que desaparecen
  (R5), no un cambio de contenido; la regla de la documentación es que quien cambia un documento revisa los casos que lo
  esperan (`grep` del id en `golden.jsonl`) y lo anota en su PR.
- **Preguntas demasiado fáciles (copia del documento)**: la regla de frase literal y el indicador informativo del
  resumen (R5) las hacen visibles; la revisión decide.
- **Sesgo del autor único**: las preguntas las escribe una sola persona y suenan parecido; se mitiga pidiendo variedad de
  registro en la guía (coloquial, formal, con detalle de un pedido) y dejando que LAB-33 aporte preguntas de clientes.
