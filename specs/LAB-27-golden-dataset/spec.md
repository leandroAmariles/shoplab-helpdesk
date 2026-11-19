# Feature Specification: Dataset golden de preguntas y respuestas

**Feature Branch**: `feature/LAB-27-golden-dataset`

**Created**: 2026-11-16

**Status**: Draft

**Input**: User description: "LAB-27 (F2-01) Dataset golden de preguntas y respuestas. Como estudiante, quiero un dataset
golden de preguntas con respuesta esperada. Criterios: 80–100 casos en `eval/golden.jsonl`: pregunta, respuesta de
referencia, documentos esperados, categoría; incluye preguntas sin respuesta." Depende de LAB-18 (corpus de políticas y
FAQs) y LAB-22 (respuestas aumentadas con los documentos), ya fusionadas en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un conjunto de referencia con preguntas que el corpus sí responde (Priority: P1)

Como estudiante, quiero un conjunto de entre 80 y 100 casos en `eval/golden.jsonl`, cada uno con una pregunta de cliente,
la respuesta de referencia, los documentos de la base de conocimiento donde está la respuesta y una categoría, para tener
una vara de medir fija con la que juzgar al asistente en lugar de impresiones sueltas.

Cada caso se lee de forma independiente: una pregunta escrita como la escribiría un cliente de la tienda, la respuesta
correcta redactada a partir de los documentos (no copiada de ellos de forma literal en bloque), y los identificadores de
los documentos del corpus (`data/kb`) que contienen esa respuesta. La categoría agrupa los casos por tema para poder
medir por separado pagos, privacidad, devoluciones, envíos y garantías. Una parte de los casos necesita juntar
información de dos documentos.

**Why this priority**: Es el criterio de aceptación principal y la base de toda la fase F2: las métricas de recuperación
(LAB-28), de calidad de respuesta (LAB-29) y la evaluación en CI (LAB-30) leen este archivo. Sin él no hay nada que
medir (Constitución V, «Evaluation-Driven RAG»).

**Independent Test**: Abrir `eval/golden.jsonl`, comprobar que tiene entre 80 y 100 líneas, que cada una es un caso
completo y que, tomando cinco casos al azar, la respuesta de referencia se puede verificar leyendo solo los documentos
esperados.

**Acceptance Scenarios**:

1. **Given** el repositorio actualizado, **When** se abre `eval/golden.jsonl`, **Then** contiene entre 80 y 100 casos, uno
   por línea, cada uno con pregunta, respuesta de referencia, documentos esperados y categoría.
2. **Given** un caso con respuesta en el corpus, **When** se leen sus documentos esperados, **Then** cada identificador
   corresponde a un documento existente en `data/kb` y la respuesta de referencia se sostiene solo con lo que dicen esos
   documentos.
3. **Given** el conjunto completo, **When** se cuenta por categoría, **Then** cada uno de los cinco temas del corpus
   (pagos, privacidad, devoluciones, envíos y garantías) tiene un mínimo de casos con respuesta, de modo que ninguno
   queda sin medir.
4. **Given** el conjunto completo, **When** se revisan los documentos esperados, **Then** cada documento del corpus
   aparece como esperado en al menos un caso y existe un grupo de casos cuya respuesta exige combinar dos documentos.
5. **Given** dos casos cualesquiera, **When** se comparan, **Then** tienen identificadores distintos y no hay dos
   preguntas con el mismo texto.
6. **Given** un caso, **When** su pregunta se compara con el texto de los documentos, **Then** no es una copia de una
   frase del documento: una parte importante de las preguntas usa palabras distintas a las del corpus, como lo haría un
   cliente real.

---

### User Story 2 - Preguntas que el corpus no puede responder (Priority: P1)

Como estudiante, quiero que el conjunto incluya preguntas sin respuesta en el corpus, junto con la respuesta correcta
esperada en ese caso, para comprobar que el asistente reconoce que no tiene información en lugar de inventarla.

Hay dos tipos. Las preguntas **del tema pero sin respuesta en los documentos** (p. ej. «¿Puedo pagar con criptomonedas?»,
dato que la tienda no publica) ponen a prueba que el asistente no completa con conocimiento general. Las preguntas
**ajenas a la tienda** (p. ej. una receta de cocina) ponen a prueba que no se sale de su ámbito. En ambos casos no hay
documentos esperados y la respuesta de referencia dice, en lenguaje de cliente, que el asistente no cuenta con esa
información.

**Why this priority**: Es el segundo criterio de aceptación explícito. LAB-22 ya exige que el asistente reconozca la
ausencia de información (FR-006 y FR-007 de esa historia); sin casos sin respuesta, esa garantía nunca se mediría, y un
asistente que inventa quedaría puntuado igual que uno honesto.

**Independent Test**: Filtrar los casos sin documentos esperados y comprobar que existen de ambos tipos, que ninguna de
sus preguntas se responde con el corpus (búsqueda manual en `data/kb`) y que la respuesta de referencia de todas ellas
reconoce la falta de información sin aportar un dato inventado.

**Acceptance Scenarios**:

1. **Given** el conjunto completo, **When** se filtran los casos sin documentos esperados, **Then** hay al menos 12
   casos y representan entre el 15 % y el 25 % del total.
2. **Given** los casos sin respuesta, **When** se clasifican, **Then** hay al menos 5 del tema pero sin dato publicado y
   al menos 5 ajenos a la tienda.
3. **Given** un caso sin respuesta del tema, **When** se busca su dato en todo `data/kb`, **Then** no aparece en ningún
   documento, ni siquiera de forma parcial que permitiría una respuesta correcta.
4. **Given** un caso sin respuesta, **When** se lee su respuesta de referencia, **Then** reconoce que no hay información
   disponible y no contiene ninguna cifra, plazo ni política inventada.
5. **Given** un caso sin respuesta, **When** se lee su categoría, **Then** indica el tema al que pertenece la pregunta, o
   una categoría propia para las ajenas a la tienda, de modo que se pueda medir por separado.

---

### User Story 3 - Un archivo que se valida solo y avisa cuando se desactualiza (Priority: P2)

Como estudiante, quiero que una comprobación automática verifique la forma y la coherencia del archivo, para que un error
de edición o un cambio en el corpus no deje a las métricas leyendo datos rotos sin que nadie lo note.

La comprobación revisa que cada línea es un caso completo y válido, que los identificadores de documentos existen en el
corpus, que se cumplen los conteos y el reparto que fija esta especificación y que no hay duplicados. Informa todos los
problemas de una vez, indicando el caso afectado, y falla de forma visible si algo no cuadra.

**Why this priority**: Un dataset de referencia solo sirve si es de fiar. Las historias siguientes de F2 dependen de él y
el corpus puede cambiar; sin esta guardia, el archivo se pudriría en silencio. Es P2 porque el archivo ya es usable sin
ella, pero no es sostenible.

**Independent Test**: Introducir a propósito un error (borrar una clave, referir un documento que no existe, duplicar una
pregunta) y comprobar que la comprobación falla nombrando el caso y el motivo; revertirlo y comprobar que pasa.

**Acceptance Scenarios**:

1. **Given** el archivo vigente, **When** se ejecuta la comprobación, **Then** pasa y resume los casos por categoría y por
   tipo (con y sin respuesta).
2. **Given** un caso al que le falta una clave obligatoria o con una clave desconocida, **When** se ejecuta, **Then**
   falla indicando el identificador del caso y la clave.
3. **Given** un caso que cita un documento que no existe en `data/kb`, **When** se ejecuta, **Then** falla indicando el
   caso y el documento.
4. **Given** un documento del corpus que se elimina o renombra mientras algún caso lo espera, **When** se ejecuta,
   **Then** falla señalando los casos afectados en lugar de dejarlos apuntando a nada.
5. **Given** varios errores a la vez, **When** se ejecuta, **Then** se informan todos juntos y no solo el primero.
6. **Given** que se ejecuta dos veces sin cambios, **When** se comparan los resultados, **Then** son idénticos.

---

### Edge Cases

- **Pregunta que se responde con dos documentos**: los dos van como esperados; ninguno puede faltar ni sobrar. La
  respuesta de referencia debe necesitar a ambos.
- **Mismo contenido en Markdown y PDF**: el documento esperado se identifica por su id (nombre sin extensión), no por
  formato; la fuente de verdad es el Markdown (`docs/base-conocimiento.md`).
- **Pregunta con respuesta solo parcial en el corpus** (p. ej. el corpus da el plazo pero no un caso particular):
  se trata como sin respuesta si no permite contestar lo que se pregunta; no se admite como caso con respuesta dudosa.
- **Pregunta ambigua o muy corta** («devolución»): no entra en el conjunto; cada pregunta debe tener una respuesta única y
  verificable.
- **Seguimiento que depende de un mensaje anterior** («¿y en cuotas?»): fuera de alcance; cada caso es una pregunta
  autocontenida. La memoria conversacional se mide, si hace falta, en otra historia.
- **Cambio del corpus**: si un documento cambia de modo que la respuesta de referencia deja de ser verdad, el caso hay
  que revisarlo; la comprobación detecta la referencia rota, y el repaso de contenido es una tarea de quien cambia el
  documento.
- **Datos personales**: ninguna pregunta contiene datos reales de clientes (nombres, correos, documentos, números de
  pedido reales); si hace falta un identificador se usan valores obviamente ficticios (Constitución VI).
- **Tildes, eñes y signos de interrogación**: se conservan tal cual; la lectura del archivo no los altera.
- **Línea vacía o final de archivo sin salto de línea**: no cuenta como caso ni rompe la lectura.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El conjunto MUST estar en `eval/golden.jsonl`, con un caso por línea y un total de entre 80 y 100 casos.
- **FR-002**: Cada caso MUST tener una pregunta, una respuesta de referencia, una lista de documentos esperados y una
  categoría, además de un identificador estable y único que no cambia aunque el caso se reordene.
- **FR-003**: Las preguntas MUST estar en español y redactadas como las escribiría un cliente de la tienda, de forma
  autocontenida (sin depender de un mensaje anterior) y sin datos personales reales.
- **FR-004**: La respuesta de referencia MUST ser correcta según el corpus, verificable leyendo solo los documentos
  esperados, y MUST NOT incluir información que no esté en ellos.
- **FR-005**: Cada documento esperado MUST ser el id de un documento existente en `data/kb`; la identificación no depende
  del formato (Markdown o PDF).
- **FR-006**: La categoría MUST ser uno de los cinco temas del corpus (pagos, privacidad, devoluciones, envíos,
  garantías) o una categoría propia para las preguntas ajenas a la tienda.
- **FR-007**: Cada tema del corpus MUST tener al menos 12 casos con respuesta, y cada documento del corpus MUST aparecer
  como esperado en al menos un caso.
- **FR-008**: El conjunto MUST incluir al menos 8 casos cuya respuesta exige combinar información de dos documentos, con
  ambos como documentos esperados.
- **FR-009**: El conjunto MUST incluir preguntas sin respuesta en el corpus, entre el 15 % y el 25 % del total y no menos
  de 12, con lista vacía de documentos esperados.
- **FR-010**: Entre las preguntas sin respuesta MUST haber al menos 5 del tema de la tienda cuyo dato no está publicado y
  al menos 5 ajenas a la tienda.
- **FR-011**: La respuesta de referencia de un caso sin respuesta MUST reconocer que no hay información disponible, en
  lenguaje de cliente, sin aportar ninguna cifra, plazo ni política inventada.
- **FR-012**: Las preguntas MUST NOT repetirse (ni textualmente ni con diferencias triviales de mayúsculas, espacios o
  puntuación) y los identificadores MUST ser únicos.
- **FR-013**: Al menos el 30 % de las preguntas con respuesta MUST estar redactado con palabras distintas a las del
  documento esperado (menos de la mitad de sus palabras de contenido aparecen en él), para que medir la recuperación no se
  reduzca a coincidencia literal de texto.
- **FR-014**: Una comprobación automática, incluida en la suite de pruebas del proyecto, MUST validar la estructura de cada
  caso, la existencia de los documentos esperados, los conteos, el reparto por categoría y por tipo, y la ausencia de
  duplicados, informando todos los fallos juntos con el identificador del caso afectado.
- **FR-015**: El archivo MUST poder leerse línea a línea con cualquier lector estándar de JSON Lines, en UTF-8, sin
  conservar nada más que los casos (sin comentarios ni líneas de cabecera), para que lo reutilicen las métricas de
  recuperación (LAB-28), de calidad de respuesta (LAB-29) y la evaluación en CI (LAB-30).
- **FR-016**: La lectura y la validación MUST ser deterministas: el mismo archivo da siempre el mismo resultado, sin red,
  sin base de datos y sin modelo de lenguaje.
- **FR-017**: La documentación del repositorio MUST explicar el formato de cada caso, cómo añadir o cambiar uno y la regla
  de revisión cuando cambia el corpus, de forma que otra persona pueda ampliar el conjunto sin leer el código de la
  validación.

### Key Entities *(include if feature involves data)*

- **Caso golden**: una pregunta de referencia con su respuesta esperada. Atributos: identificador estable y único,
  pregunta, respuesta de referencia, documentos esperados (lista de ids; vacía si no hay respuesta en el corpus) y
  categoría. Es la unidad que miden las historias siguientes de F2.
- **Documento esperado**: id de un documento de la base de conocimiento (`data/kb`, nombre del archivo sin extensión)
  donde está la respuesta. Un caso puede tener uno o dos; ninguno si no hay respuesta.
- **Categoría**: agrupación por tema para poder medir por separado: pagos, privacidad, devoluciones, envíos, garantías, o
  la propia de las preguntas ajenas a la tienda.
- **Caso sin respuesta**: caso con lista de documentos esperados vacía, cuya respuesta de referencia reconoce la falta de
  información. Puede ser del tema (dato no publicado) o ajeno a la tienda.
- **Corpus**: los documentos de políticas y FAQs de `data/kb` (LAB-18) contra los que se redactan y verifican los casos.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `eval/golden.jsonl` contiene entre 80 y 100 casos válidos, y la comprobación automática pasa sobre él.
- **SC-002**: Los cinco temas del corpus tienen al menos 12 casos con respuesta cada uno, y los 24 documentos del corpus
  son esperados en al menos un caso: no queda ningún tema ni documento sin medir.
- **SC-003**: Entre el 15 % y el 25 % de los casos (no menos de 12) son preguntas sin respuesta, con al menos 5 del tema y
  al menos 5 ajenas a la tienda.
- **SC-004**: Al menos 8 casos exigen combinar dos documentos, de modo que medir la recuperación no premie recuperar solo
  uno.
- **SC-005**: De una muestra al azar de 15 casos, una persona que lee solo los documentos esperados confirma que la
  respuesta de referencia es correcta y completa en el 100 % de ellos (revisión manual registrada).
- **SC-006**: Cualquier error introducido a propósito (clave faltante, documento inexistente, pregunta duplicada,
  conteo fuera de rango) hace fallar la comprobación en el 100 % de los intentos, con el caso afectado identificado.
- **SC-007**: Una persona que no conoce el repositorio añade un caso nuevo válido siguiendo solo la documentación en menos
  de 10 minutos.

## Assumptions

- El corpus de LAB-18 (24 documentos en cinco temas, `data/kb`) está fusionado en `main` y se usa tal cual; esta historia
  no lo modifica. Si un caso sin respuesta del tema choca con el contenido real del corpus, se cambia la pregunta, no el
  documento.
- Las preguntas, respuestas de referencia y categorías se escriben en español, igual que el corpus y el asistente.
- El conjunto se redacta a mano o con ayuda de un modelo, pero cada caso lo revisa una persona contra los documentos
  esperados antes de entrar (el corpus es sintético y las respuestas no pueden inventarse). Los medios para redactarlo no
  forman parte del producto.
- Las 20 preguntas del notebook de LAB-26 (`notebooks/data/questions.toml`) pueden servir como punto de partida, pero el
  dataset golden es un archivo propio e independiente: no cambia el de LAB-26 ni depende de él.
- Cada caso es una sola pregunta autocontenida; la conversación con memoria (LAB-24) no se mide aquí.
- **Fuera de alcance**: ejecutar el asistente o el recuperador contra el dataset, calcular hit rate, MRR, recall,
  faithfulness o cualquier otra métrica (LAB-28 y LAB-29), la evaluación en CI y la comparación con la línea base (LAB-30),
  la persistencia de ejecuciones (LAB-31), la pantalla `admin-eval` (LAB-32) y promover casos desde el feedback 👎
  (LAB-33). Esta historia produce y valida solo los datos de referencia.
- El archivo es la fuente única del dataset; no se duplica en base de datos ni en otro formato. Las historias siguientes
  lo leerán, no lo regenerarán.
- La Constitución V pide que, desde F2, los cambios de prompt, chunking, embeddings o retrieval lleven un resultado de
  evaluación; este dataset es la pieza que lo hace posible, pero el cálculo y la puerta en los PR llegan en LAB-28 a LAB-30.
