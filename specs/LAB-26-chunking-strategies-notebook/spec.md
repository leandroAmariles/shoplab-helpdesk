# Feature Specification: Notebook comparativo de estrategias de chunking

**Feature Branch**: `feature/LAB-26-chunking-strategies-notebook`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "LAB-26 (F1-09) Notebook comparativo de estrategias de chunking. Como estudiante, quiero un
notebook que compare estrategias de chunking. Criterios: compara tamaño fijo, por encabezados y semántico; tabla de
resultados con 20 preguntas de prueba." Depende de LAB-18 (corpus de políticas y FAQs) y LAB-20 (parseo y chunking de
documentos), ya fusionadas en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Trocear el corpus con tres estrategias y ver en qué se diferencian (Priority: P1)

Como estudiante, quiero trocear el mismo corpus con tres estrategias (tamaño fijo, por encabezados y semántica) y ver
cómo queda cada una, para entender de forma concreta qué cambia en los fragmentos según cómo se corta el texto.

El notebook toma los documentos Markdown de la base de conocimiento (`data/kb`), los trocea con cada estrategia bajo las
mismas condiciones y muestra, por estrategia, cuántos chunks produce y cómo se distribuye su tamaño. Incluye ejemplos
lado a lado del mismo pasaje cortado de tres maneras.

**Why this priority**: Es la base de la comparación: sin las tres estrategias aplicadas al mismo corpus no hay nada que
medir. Por sí sola ya enseña la diferencia entre cortar por tamaño, por estructura y por significado.

**Independent Test**: Ejecutar solo la parte de troceado del notebook y comprobar que muestra, para cada una de las tres
estrategias, el número de chunks, el tamaño mínimo, medio y máximo, y un mismo pasaje de un documento cortado por cada una.

**Acceptance Scenarios**:

1. **Given** el corpus de `data/kb`, **When** se aplica la estrategia de tamaño fijo, **Then** se obtienen chunks de un
   tamaño máximo común, que pueden cortar una frase, una tabla o una sección por la mitad, con el solape configurado.
2. **Given** el mismo corpus, **When** se aplica la estrategia por encabezados, **Then** ningún chunk mezcla dos
   secciones y cada uno conserva la ruta de encabezados de su sección, igual que la ingesta de producción.
3. **Given** el mismo corpus, **When** se aplica la estrategia semántica, **Then** los cortes caen donde el significado
   de frases consecutivas cambia, y los chunks resultantes respetan el mismo tamaño máximo que las otras dos.
4. **Given** las tres estrategias aplicadas, **When** se revisa el resumen, **Then** se ve por estrategia el número de
   chunks y el tamaño mínimo, medio y máximo, en una tabla comparable.
5. **Given** un mismo pasaje del corpus, **When** se muestra cortado por cada estrategia, **Then** el estudiante puede ver
   dónde corta cada una y qué queda junto o separado.
6. **Given** que se ejecuta dos veces con los mismos parámetros, **When** se comparan los chunks de las estrategias de
   tamaño fijo y por encabezados, **Then** son idénticos.

---

### User Story 2 - Medir con 20 preguntas de prueba y leer una tabla de resultados (Priority: P1)

Como estudiante, quiero lanzar 20 preguntas de prueba contra los chunks de cada estrategia y ver una tabla de
resultados, para saber con datos y no por intuición cuál recupera mejor la información correcta.

Cada pregunta, en español y respondible con el corpus, lleva el documento donde está la respuesta y el pasaje de
referencia que la contiene. Para cada estrategia, el notebook recupera los chunks más cercanos a la pregunta y comprueba
si alguno es correcto. El resultado es una tabla con una fila por pregunta y el desempeño de cada estrategia, más un
resumen con las métricas agregadas.

**Why this priority**: Es el segundo criterio de aceptación explícito y la razón de ser del notebook: sin medición, la
comparación sería opinión (Constitución V, «Evaluation-Driven RAG»).

**Independent Test**: Con las 20 preguntas y los chunks de las tres estrategias, ejecutar la medición y comprobar que la
tabla tiene 20 filas, una columna de resultado por estrategia y un resumen con las métricas por estrategia.

**Acceptance Scenarios**:

1. **Given** las 20 preguntas de prueba, **When** se leen, **Then** cubren los cinco temas del corpus (pagos,
   privacidad, devoluciones, envíos y garantías) con cuatro preguntas por tema, y cada una indica documento esperado y
   pasaje de referencia.
2. **Given** una pregunta y una estrategia, **When** se recuperan los `k` chunks más cercanos, **Then** se registra la
   posición del primer chunk correcto (de 1 a `k`) o «no encontrado» si ninguno lo es.
3. **Given** un chunk recuperado, **When** se decide si es correcto, **Then** lo es solo si procede del documento
   esperado **y** contiene el pasaje de referencia; un chunk del documento correcto que no incluye el pasaje no cuenta.
4. **Given** la medición terminada, **When** se muestra la tabla, **Then** tiene 20 filas (una por pregunta) con la
   posición obtenida por cada estrategia y una fila final de resumen con la tasa de acierto y el rango recíproco medio
   por estrategia.
5. **Given** una pregunta que ninguna estrategia resuelve, **When** se muestra la tabla, **Then** aparece como fallo en
   todas, no se oculta ni se descarta.
6. **Given** las tres estrategias, **When** se miden, **Then** todas usan el mismo modelo de embeddings, el mismo `k` y las
   mismas 20 preguntas, de modo que la única diferencia es cómo se cortó el texto.

---

### User Story 3 - Sacar conclusiones y conocer los límites de la comparación (Priority: P2)

Como estudiante, quiero que el notebook interprete los resultados, para saber qué estrategia funcionó mejor en este
corpus, por qué y hasta dónde se pueden generalizar los resultados.

**Why this priority**: Los números sin lectura enseñan poco, pero la tabla (historia 2) ya tiene valor propio; la
interpretación la completa.

**Independent Test**: Leer la sección de conclusiones del notebook ya ejecutado y comprobar que nombra la estrategia con
mejores métricas, relaciona al menos dos preguntas de la tabla con una causa concreta de acierto o fallo, y enumera los
límites de la comparación.

**Acceptance Scenarios**:

1. **Given** los resultados, **When** se lee la conclusión, **Then** indica qué estrategia obtuvo mejores métricas
   agregadas y con qué diferencia respecto a las otras.
2. **Given** preguntas en las que las estrategias difieren, **When** se leen los ejemplos, **Then** al menos dos casos
   muestran el chunk recuperado (o el que faltó) y explican por qué una estrategia acertó y otra falló (p. ej. una tabla
   cortada por la mitad).
3. **Given** la conclusión, **When** se lee, **Then** declara los límites: 20 preguntas es una muestra pequeña, el corpus
   es sintético y en español, y la estrategia semántica depende de sus parámetros.
4. **Given** la conclusión, **When** se lee, **Then** deja claro que no cambia el chunking de producción: cualquier cambio
   de estrategia es otra historia y exige la evaluación automática de F2.

---

### User Story 4 - Ejecutarlo desde cero y leerlo sin ejecutarlo (Priority: P2)

Como estudiante, quiero poder ejecutar el notebook en mi máquina siguiendo unas instrucciones cortas, y poder leer sus
resultados sin ejecutarlo, para repetir el experimento o simplemente estudiarlo.

**Why this priority**: Un notebook que solo funciona en la máquina de quien lo escribió no sirve para aprender; pero el
contenido (historias 1 a 3) tiene valor aunque la reproducibilidad se mejore después.

**Independent Test**: Desde un clon limpio, seguir las instrucciones del README del notebook, ejecutar todas las celdas
de arriba abajo y comprobar que se obtiene la misma tabla de resultados que la versión guardada en el repositorio.

**Acceptance Scenarios**:

1. **Given** un clon limpio del repositorio con los servicios locales necesarios, **When** se siguen las instrucciones,
   **Then** el notebook se ejecuta de arriba abajo sin editar código ni rutas.
2. **Given** el notebook guardado en el repositorio, **When** se abre sin ejecutarlo, **Then** ya muestra las tablas,
   gráficos y conclusiones de la última ejecución.
3. **Given** el modelo de embeddings no disponible (el servicio local de modelos apagado o el modelo sin descargar),
   **When** se ejecuta el notebook, **Then** falla antes de procesar nada, con un mensaje que indica qué falta y cómo
   arrancarlo.
4. **Given** dos ejecuciones con los mismos parámetros, **When** se comparan las métricas, **Then** coinciden (o difieren
   dentro de una tolerancia que el notebook declara si los embeddings no fueran bit a bit estables).
5. **Given** el notebook, **When** se revisan sus parámetros, **Then** todos (tamaño máximo, solape, `k`, umbral de la
   estrategia semántica, modelo) están declarados en una sola celda al inicio con una explicación breve cada uno.

---

### Edge Cases

- **Modelo de embeddings no disponible**: el notebook lo detecta al inicio y se detiene con un mensaje accionable; no
  muestra resultados parciales como si fueran válidos.
- **Pasaje de referencia que ya no está en el documento** (el corpus cambió): una validación previa recorre las 20
  preguntas y, si alguna no encuentra su pasaje en el documento esperado, se detiene y lista cuáles; no se mide con
  preguntas rotas.
- **Respuesta cortada por un límite de chunk**: si el pasaje de referencia queda partido entre dos chunks de tamaño fijo,
  ninguno lo contiene y cuenta como fallo de esa estrategia; es un resultado legítimo que el notebook explica, no un error.
- **Documento sin encabezados internos**: la estrategia por encabezados cae a trocear por tamaño, igual que producción; se
  señala en el resumen.
- **Estrategia semántica con chunks muy pequeños o muy grandes**: se acotan al mismo tamaño máximo que las otras y se
  unen los fragmentos demasiado cortos; el resumen muestra la distribución para que se vea el efecto.
- **Empate entre chunks de igual cercanía**: el orden es determinista (se resuelve por documento y posición), para que dos
  ejecuciones den la misma tabla.
- **Pregunta respondible desde varios documentos**: solo el documento esperado cuenta como correcto; las preguntas se
  redactan para que su respuesta no esté duplicada en otro documento.
- **Documentos solo en PDF**: no hay ninguno; todo el corpus tiene versión Markdown. Los PDF se excluyen porque duplican
  el texto de su Markdown y el parseo de PDF no es lo que se compara.
- **Datos reales**: el corpus es ficticio; el notebook y sus resultados no incluyen datos personales ni credenciales.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST incluir un notebook en `notebooks/` que compare las estrategias de chunking de tamaño
  fijo, por encabezados y semántica, con sus resultados guardados.
- **FR-002**: El notebook MUST usar como corpus los documentos Markdown de `data/kb`, sin modificarlos.
- **FR-003**: La estrategia de tamaño fijo MUST cortar el texto por longitud máxima con un solape configurable, sin tener
  en cuenta la estructura del documento.
- **FR-004**: La estrategia por encabezados MUST comportarse igual que el troceado de la ingesta de producción
  (ADR-0016), reutilizándolo en lugar de reimplementarlo, para que el resultado sea representativo del sistema real.
- **FR-005**: La estrategia semántica MUST cortar donde el significado de frases consecutivas cambia, medido con el mismo
  modelo de embeddings que usa el sistema (ADR-0017), con el umbral de corte como parámetro declarado.
- **FR-006**: Las tres estrategias MUST compararse en condiciones equivalentes: mismo corpus, mismo tamaño máximo de chunk,
  mismo modelo de embeddings, mismo `k` y mismas preguntas.
- **FR-007**: El notebook MUST mostrar, por estrategia, el número de chunks y el tamaño mínimo, medio y máximo, y al
  menos un pasaje cortado de las tres maneras.
- **FR-008**: El conjunto de pruebas MUST constar de 20 preguntas en español, cuatro por cada tema del corpus (pagos,
  privacidad, devoluciones, envíos, garantías), cada una con el documento esperado y el pasaje de referencia que contiene
  la respuesta.
- **FR-009**: Las preguntas MUST guardarse como datos versionados, separados del código del notebook, en un formato que
  una persona pueda leer y editar.
- **FR-010**: El notebook MUST validar las preguntas antes de medir: cada pasaje de referencia debe existir en su
  documento esperado; si no, se detiene y lista las preguntas afectadas.
- **FR-011**: Un chunk recuperado MUST contar como correcto solo si procede del documento esperado y contiene el pasaje de
  referencia.
- **FR-012**: Para cada pregunta y estrategia, el notebook MUST registrar la posición del primer chunk correcto entre los
  `k` recuperados, o «no encontrado».
- **FR-013**: El notebook MUST presentar una tabla con una fila por pregunta (20) y el resultado de cada estrategia, más un
  resumen con la tasa de acierto en los `k` primeros y el rango recíproco medio por estrategia.
- **FR-014**: El valor por defecto de `k` MUST ser el que usa el chat en producción (4), y ser configurable.
- **FR-015**: El notebook MUST incluir una conclusión que identifique la estrategia con mejores métricas, explique con al
  menos dos casos concretos por qué difieren los resultados, y declare los límites de la comparación.
- **FR-016**: Todos los parámetros (tamaño máximo, solape, `k`, umbral semántico, modelo de embeddings) MUST declararse en
  un único lugar al inicio, con una explicación breve.
- **FR-017**: Las estrategias de tamaño fijo y por encabezados MUST ser deterministas; la medición completa MUST dar las
  mismas métricas en dos ejecuciones con los mismos parámetros, o declarar la tolerancia observada.
- **FR-018**: El notebook MUST comprobar al inicio que el modelo de embeddings está disponible y, si no, detenerse con un
  mensaje que indique qué falta y cómo arrancarlo.
- **FR-019**: Un README junto al notebook MUST explicar en pocos pasos qué necesita, cómo ejecutarlo desde un clon limpio y
  cómo interpretar la tabla.
- **FR-020**: Las dependencias del notebook MUST declararse y poder instalarse con un solo comando, sin afectar a las
  dependencias de `ingest` ni del backend.
- **FR-021**: El código de producción (`backend/`, `ingest/`) MUST NOT importar nada del notebook (Constitución, apartado
  de tecnología); el notebook sí puede reutilizar código de `ingest` sin modificarlo.
- **FR-022**: Esta historia MUST NOT cambiar el chunking de producción, ni los valores por defecto de `ingest`, ni crear el
  dataset golden ni la evaluación automática de F2; solo mide y concluye.
- **FR-023**: El notebook y sus datos MUST NOT contener datos personales reales, secretos ni credenciales.

### Key Entities *(include if feature involves data)*

- **Estrategia de chunking**: forma de cortar un documento en chunks. Tiene nombre (tamaño fijo, por encabezados,
  semántica) y parámetros (tamaño máximo, solape y, en la semántica, umbral de corte).
- **Chunk**: fragmento de texto producido por una estrategia, con su documento de origen, su posición y, en la estrategia
  por encabezados, su sección.
- **Pregunta de prueba**: pregunta en español con su tema, el documento donde está la respuesta y el pasaje de referencia
  que la contiene. Existen 20, cuatro por tema.
- **Resultado de una pregunta**: para una pregunta y una estrategia, la posición (1 a `k`) del primer chunk correcto o
  «no encontrado».
- **Resumen por estrategia**: número y tamaños de chunks, tasa de acierto en los `k` primeros y rango recíproco medio.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El notebook compara exactamente tres estrategias sobre los 24 documentos del corpus y muestra el resumen de
  chunks (número y tamaños) de cada una.
- **SC-002**: La tabla de resultados tiene 20 filas, una por pregunta de prueba, con un resultado por estrategia, y un
  resumen con la tasa de acierto y el rango recíproco medio de cada una.
- **SC-003**: Una persona que no ha escrito el notebook, siguiendo solo el README, lo ejecuta de arriba abajo sin editar
  código en menos de 15 minutos desde un clon limpio, con los servicios locales ya instalados.
- **SC-004**: Dos ejecuciones consecutivas con los mismos parámetros dan las mismas métricas (o difieren dentro de la
  tolerancia que el notebook declara).
- **SC-005**: Al abrir el notebook sin ejecutarlo se ven las tablas, los ejemplos y la conclusión de la última ejecución.
- **SC-006**: La conclusión nombra la estrategia con mejores métricas y respalda la afirmación con al menos dos preguntas
  concretas de la tabla.
- **SC-007**: Si el modelo de embeddings no está disponible, el notebook se detiene en su primera celda de ejecución con un
  mensaje claro, sin haber producido ningún resultado parcial.

## Assumptions

- **Destinatario**: el «estudiante» es quien explora este laboratorio de RAG; no es un usuario final del helpdesk. El
  notebook es material de aprendizaje y de apoyo a decisiones futuras, no una funcionalidad de la aplicación.
- **Corpus**: se usa el corpus de LAB-18 (24 documentos Markdown, cinco temas). Los PDF se excluyen porque repiten el texto
  de su Markdown y el parseo de PDF queda fuera de la comparación.
- **Modelo de embeddings**: el mismo que el sistema (`bge-m3` vía el servicio local de modelos, ADR-0003 y ADR-0017), de
  modo que los resultados sean representativos; por eso el notebook necesita ese servicio en marcha para ejecutarse.
- **Tamaño máximo y solape**: se parte de los valores por defecto de producción (1 000 y 100 caracteres, ADR-0016) para las
  tres estrategias, ajustables en la celda de parámetros.
- **Métricas**: tasa de acierto en los `k` primeros (hit rate@k) y rango recíproco medio (MRR), por ser sencillas, estándar
  y suficientes para 20 preguntas. No se calculan métricas de generación (fidelidad, relevancia de la respuesta), que
  pertenecen a la evaluación de F2.
- **Las 20 preguntas** son de este notebook: se redactan aquí con su documento y pasaje de referencia. El dataset golden
  de F2 (`eval/`) podrá reutilizarlas o ampliarlas, pero esta historia no lo crea ni lo condiciona.
- **Dónde se guardan**: las preguntas viven junto al notebook en `notebooks/`, en un archivo de datos propio, no en `eval/`.
- **Estrategia semántica**: la implementación es la propia del notebook (corte por similitud entre frases consecutivas);
  ADR-0016 la descartó para producción en `ingest`, pero como experimento aquí es válida. Su resultado depende del umbral
  elegido; no se hace un barrido exhaustivo de umbrales, solo uno declarado (y, opcionalmente, una comparación breve).
- **Resultados guardados**: el notebook se versiona con las salidas de la última ejecución para poder leerlo sin
  ejecutarlo; las dependencias se instalan de forma aislada (p. ej. un proyecto propio bajo `notebooks/`).
- **Dependencias**: LAB-18 (corpus) y LAB-20 (troceado por encabezados) están en `main`; LAB-21 (embeddings con `bge-m3`)
  también. El cierre de F1 es el objetivo del sprint.
- **Sin cambios de producción**: la conclusión puede recomendar otra estrategia, pero adoptarla es una historia aparte que
  exige la evaluación de F2 (Constitución V, ADR-0016).
