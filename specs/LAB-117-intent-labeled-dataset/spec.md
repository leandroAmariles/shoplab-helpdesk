# Feature Specification: Dataset etiquetado de intenciones del cliente

**Feature Branch**: `feature/LAB-117-intent-labeled-dataset`

**Created**: 2026-11-21

**Status**: Draft

**Input**: User description: "LAB-117 (F8-02) Dataset etiquetado de intenciones del cliente. Como desarrollador, quiero un
dataset etiquetado de mensajes de cliente por intención, para entrenar y evaluar un clasificador propio. Criterios:
etiquetas fijas `politica`, `producto`, `pedido`, `escalado`, `charla` (las de LAB-36 más escalado y la charla de LAB-114);
al menos 600 ejemplos en español y portugués, a partir de las preguntas del golden (LAB-27), de comentarios de reseñas de
Olist y de paráfrasis generadas con el LLM local, revisadas a mano; guía de etiquetado con definición y ejemplos límite de
cada clase y muestra de 50 casos doble-etiquetada con acuerdo (kappa de Cohen) reportado; partición estratificada
train/val/test fija con semilla, sin paráfrasis de un mismo origen repartidas entre particiones; ficha del dataset (origen,
tamaño por clase e idioma, licencias, limitaciones) junto a los datos; sin datos personales, aplicando el mismo enmascarado
que en las trazas. Depende de LAB-27 y LAB-114." Segunda historia de la épica F8 · ML y MLOps (LAB-115).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Disponer de un dataset de mensajes etiquetados por intención (Priority: P1)

Como desarrollador, quiero un conjunto de mensajes de cliente en español y portugués, cada uno con una única intención de
cinco posibles, para tener con qué entrenar y evaluar un clasificador propio en las historias siguientes de F8 (LAB-118 y
LAB-126).

Las cinco intenciones son fijas: `politica` (pregunta sobre las reglas generales de la tienda: pagos, privacidad,
devoluciones, envíos, garantías), `producto` (pregunta o queja sobre un artículo concreto o sobre el catálogo), `pedido`
(consulta sobre un pedido concreto del cliente: dónde está, en qué estado, qué contiene), `escalado` (el cliente pide o exige
hablar con una persona, o expresa un problema que el bot no debe resolver solo) y `charla` (saludos, agradecimientos,
despedidas y «¿qué puedes hacer?», la conversación acotada de LAB-114). El dataset reúne al menos 600 ejemplos con tres
orígenes: las preguntas del golden (LAB-27), comentarios de reseñas de Olist y paráfrasis generadas con el modelo local y
aceptadas tras revisión manual.

**Why this priority**: Es el entregable de la historia y lo único que bloquea a LAB-118: sin ejemplos etiquetados no hay
clasificador. Por sí solo ya permite entrenar un primer modelo.

**Independent Test**: Cargar el dataset y comprobar que todos los ejemplos tienen texto, idioma y una de las cinco etiquetas,
que hay al menos 600, que cada clase tiene ejemplos en español y en portugués y que los tres orígenes están presentes.

**Acceptance Scenarios**:

1. **Given** el dataset publicado, **When** se cuentan sus ejemplos, **Then** hay al menos 600, cada uno con exactamente una
   de las cinco etiquetas fijas y con su idioma (español o portugués).
2. **Given** el dataset, **When** se cuenta por clase e idioma, **Then** cada una de las cinco clases tiene un mínimo
   declarado de ejemplos en español y en portugués, de modo que ninguna clase existe en un solo idioma.
3. **Given** las preguntas del golden (LAB-27), **When** se revisa el dataset, **Then** contiene ejemplos derivados de ellas,
   cada uno con el identificador del caso del golden del que procede.
4. **Given** comentarios de reseñas de Olist, **When** se revisa el dataset, **Then** contiene ejemplos derivados de ellos,
   cada uno marcado como tal y con el texto ya enmascarado.
5. **Given** una paráfrasis generada con el modelo local, **When** forma parte del dataset, **Then** consta como paráfrasis,
   apunta al ejemplo del que parte y tiene registrada su revisión manual; las paráfrasis sin revisar no están en el dataset.
6. **Given** un mensaje que mezcla cortesía y una pregunta real («hola, ¿cuánto tarda un envío?»), **When** se etiqueta,
   **Then** recibe la intención de la petición sustantiva (aquí `politica`), no `charla`, igual que el chat lo envía a la
   recuperación (LAB-114).

---

### User Story 2 - Etiquetas consistentes, con guía y acuerdo medido (Priority: P1)

Como desarrollador, quiero una guía de etiquetado con la definición de cada clase y sus casos límite, y una medida del acuerdo
entre dos personas que etiquetan por separado, para confiar en que las etiquetas son consistentes y no una opinión de quien
las puso.

La guía define cada una de las cinco clases, da ejemplos claros en ambos idiomas y, sobre todo, los casos límite que se
confunden entre sí (política general frente a pedido concreto, producto frente a pedido, queja que debe escalarse frente a
queja que el bot puede atender, mensaje mixto). Una muestra de 50 ejemplos se etiqueta de forma independiente por dos
anotadores y se reporta el acuerdo (kappa de Cohen), además de los desacuerdos y cómo se resolvieron.

**Why this priority**: Un clasificador no puede ser mejor que sus etiquetas; si las clases se solapan sin criterio, el
F1 de LAB-118 mide el ruido del etiquetado y no el modelo. Es independiente de la partición y del enmascarado.

**Independent Test**: Un anotador que solo dispone de la guía etiqueta la muestra de 50 casos sin ver las etiquetas existentes;
se calcula el kappa frente a las etiquetas del dataset y se lista cada desacuerdo.

**Acceptance Scenarios**:

1. **Given** la guía de etiquetado, **When** se lee, **Then** cada una de las cinco clases tiene su definición, qué incluye,
   qué excluye y al menos dos ejemplos en cada idioma.
2. **Given** la guía, **When** se revisan los casos límite, **Then** cubre al menos los pares que más se confunden
   (`politica`/`pedido`, `producto`/`pedido`, `pedido`/`escalado`, `charla`/`politica` en mensajes mixtos) con una regla de
   desempate explícita y ejemplos.
3. **Given** una muestra de 50 ejemplos, **When** dos anotadores la etiquetan sin ver las etiquetas del otro, **Then** se
   calcula y se reporta el kappa de Cohen con el número de casos, las dos etiquetas de cada caso y la matriz de desacuerdos.
4. **Given** un desacuerdo en la muestra, **When** se resuelve, **Then** queda registrada la etiqueta final y el motivo, y, si
   revela una regla ausente, la guía se corrige.
5. **Given** la muestra de 50, **When** se elige, **Then** incluye ejemplos de las cinco clases y de ambos idiomas, y no está
   formada solo por casos fáciles.

---

### User Story 3 - Partición fija y sin fuga entre train, val y test (Priority: P1)

Como desarrollador, quiero una partición train/val/test estratificada, fija y reproducible, en la que las paráfrasis de un
mismo mensaje de origen no queden repartidas entre particiones, para que las métricas de test no estén infladas por ejemplos
casi idénticos a los de entrenamiento.

Cada ejemplo pertenece a un grupo de origen (el mensaje original y todas sus paráfrasis). La partición asigna grupos
completos a una sola partición, manteniendo en cada una la proporción de clases y de idiomas, con una semilla fija que da
siempre el mismo resultado.

**Why this priority**: Una fuga entre particiones invalida el criterio «la partición test solo se usa una vez al final» de
LAB-118 y la comparación con el modelo de deep learning de LAB-126; no se puede arreglar después sin rehacer el test.

**Independent Test**: Cargar la partición, comprobar que ningún grupo de origen aparece en más de una partición, que la
proporción de clases e idiomas es parecida en las tres y que regenerarla con la misma semilla da exactamente el mismo
resultado.

**Acceptance Scenarios**:

1. **Given** el dataset, **When** se genera la partición, **Then** cada ejemplo está en exactamente una de las tres
   particiones (train, val, test) y las tres tienen ejemplos de las cinco clases y de los dos idiomas.
2. **Given** un mensaje de origen con varias paráfrasis, **When** se asignan a particiones, **Then** el original y todas sus
   paráfrasis quedan en la misma.
3. **Given** el dataset y la semilla, **When** se regenera la partición, **Then** el resultado es idéntico al anterior, y un
   cambio en la semilla es la única forma de cambiarlo.
4. **Given** dos ejemplos con el mismo texto una vez normalizado (mayúsculas, espacios, signos), **When** se asignan a
   particiones, **Then** no quedan en particiones distintas.
5. **Given** la partición publicada, **When** se añaden ejemplos en el futuro (cola de revisión de LAB-124), **Then** los
   ejemplos que ya están conservan su partición.
6. **Given** los ejemplos derivados del golden (LAB-27), **When** se mira su reparto, **Then** el reparto por partición queda a
   la vista para que quien compare el clasificador con el golden (LAB-121) sepa qué casos ha visto el modelo al entrenar.

---

### User Story 4 - Sin datos personales en el dataset (Priority: P1)

Como desarrollador, quiero que ningún ejemplo contenga datos personales, para poder versionar el dataset, compartirlo y
entrenar con él sin exponer a nadie.

Los comentarios de reseñas de Olist son texto libre de clientes y pueden incluir correos, teléfonos, documentos, direcciones,
códigos postales, identificadores de pedido o de cliente, enlaces o nombres. Antes de entrar al dataset, el texto pasa por un
enmascarado que sustituye cada dato personal por una marca fija de su tipo (por ejemplo `[EMAIL]`), y el dataset se verifica
automáticamente para comprobar que no queda ninguno de los tipos reconocibles por patrón.

**Why this priority**: Es un requisito de la constitución (VI) y condición para publicar el dataset: un solo dato personal
versionado no se puede deshacer. Bloquea la fusión, no el etiquetado.

**Independent Test**: Pasar al enmascarado un texto con un ejemplo de cada tipo de dato personal y comprobar que todos se
sustituyen por su marca; ejecutar la verificación sobre el dataset completo y comprobar que no encuentra coincidencias.

**Acceptance Scenarios**:

1. **Given** un texto con correo, teléfono, documento de identidad (CPF o CNPJ), código postal (CEP), identificador de pedido
   o cliente y enlace, **When** se enmascara, **Then** cada uno se sustituye por una marca de su tipo y el resto del texto
   queda intacto.
2. **Given** el dataset completo, **When** se ejecuta la verificación de datos personales, **Then** no encuentra ninguna
   coincidencia de los tipos reconocibles por patrón.
3. **Given** un texto con un nombre propio de persona, **When** el patrón no puede reconocerlo, **Then** existe una revisión
   manual registrada de todos los ejemplos de origen Olist que lo detecta, y los nombres que aparezcan se enmascaran.
4. **Given** una paráfrasis generada con el modelo, **When** se revisa, **Then** se comprueba también que no ha introducido
   datos personales inventados que parezcan reales (nombres, números de pedido, correos).
5. **Given** el enmascarado, **When** se aplica dos veces al mismo texto, **Then** el resultado es el mismo (es idempotente) y
   las marcas no se vuelven a enmascarar.
6. **Given** el repositorio tras la historia, **When** se revisa, **Then** no contiene el texto original sin enmascarar de
   ninguna reseña ni una copia de la tabla de reseñas de Olist.

---

### User Story 5 - Ficha del dataset y verificación reproducible (Priority: P2)

Como desarrollador, quiero una ficha que describa el dataset y una verificación automática que lo valide, para que quien lo use
sepa qué contiene y de dónde viene, y para que un cambio que lo rompa se detecte antes de entrenar.

La ficha, junto a los datos, recoge el origen de cada parte, el tamaño por clase e idioma y por partición, la licencia de cada
origen (incluida la de Olist), cómo se generaron y revisaron las paráfrasis, el resultado del acuerdo entre anotadores, el
enmascarado aplicado y las limitaciones conocidas. La verificación comprueba el esquema y las reglas del dataset y falla con
un mensaje claro cuando algo se incumple.

**Why this priority**: Hace el dataset utilizable y auditable (LAB-125 revisará esta ficha), pero el contenido (historias 1 a 4)
ya tiene valor sin ella.

**Independent Test**: Leer la ficha y comprobar que contiene todos los apartados exigidos y que sus cifras coinciden con las
que calcula la verificación sobre el dataset; introducir defectos provocados y comprobar que la verificación falla.

**Acceptance Scenarios**:

1. **Given** la ficha, **When** se lee, **Then** declara origen, tamaño por clase e idioma, tamaño por partición, licencias,
   proporción de cada origen, proceso de generación y revisión de paráfrasis, kappa obtenido, enmascarado y limitaciones.
2. **Given** las cifras de la ficha, **When** se comparan con las que calcula la verificación sobre el dataset, **Then**
   coinciden.
3. **Given** un dataset con una etiqueta desconocida, una clase sin ejemplos en un idioma, un grupo repartido entre
   particiones o un dato personal, **When** se ejecuta la verificación, **Then** falla y dice qué ejemplo y qué regla se
   incumple.
4. **Given** el dataset correcto, **When** se ejecuta la verificación, **Then** termina sin errores y sin depender de la red, de
   Olist, ni del modelo local.
5. **Given** la ficha, **When** se leen las limitaciones, **Then** declaran al menos: el dominio de los comentarios de Olist
   (reseñas, no mensajes a un chat), el peso de las paráfrasis sintéticas, el tamaño reducido y el solape con el golden.

---

### Edge Cases

- **Mensajes mixtos**: una cortesía seguida de una pregunta real se etiqueta por la petición sustantiva; la guía lo fija con
  ejemplos, y nunca se etiqueta `charla` un mensaje que contenga una petición real.
- **Mensajes fuera de alcance y de inyección** («¿me recomiendas una receta?», «ignora tus instrucciones»): el golden los
  incluye (categorías `off_topic` e `injection`) y las cinco etiquetas no tienen una clase para ellos; se etiquetan `charla`
  (FR-007) y la ficha declara que `charla` es más amplia que la cortesía.
- **Comentarios de Olist vacíos, de una palabra o sin intención** («ok», «recomendo»): no son mensajes dirigidos a un chat; se
  descartan o, si encajan, se etiquetan según la guía, pero no entran como ruido.
- **Comentarios largos o con varios temas**: se recortan o se parten a un mensaje con una sola intención dominante, o se
  descartan si no se puede decidir; el dataset no contiene mensajes con dos etiquetas.
- **Español, portugués y mezcla**: algunos mensajes mezclan idiomas o carecen de acentos y tienen faltas; se etiquetan con el
  idioma predominante y se conservan, porque así escribe el cliente real.
- **Paráfrasis que cambian de clase**: una paráfrasis del modelo que, al reescribir, deja de ser de la misma clase que su
  origen (p. ej. una pregunta de política que deriva en una consulta de pedido) se descarta o se re-etiqueta explícitamente.
- **Paráfrasis casi idénticas al original o entre sí**: se descartan las que no aportan variación para no inflar el número
  de ejemplos.
- **Datos personales inventados por el modelo**: una paráfrasis puede incluir un nombre, un correo o un número de pedido que
  parezcan reales; se revisan igual que el texto de Olist.
- **Desacuerdo entre anotadores en un par de clases concreto**: si un par se confunde de forma sistemática, la guía se
  corrige y el caso se vuelve a etiquetar, en lugar de decidir por mayoría sin más.
- **Clase con pocos ejemplos reales** (p. ej. `escalado` y `producto`, que no aparecen en el golden): se completa con
  reseñas y paráfrasis y la ficha lo declara como limitación, sin forzar el número con ejemplos artificiales de baja calidad.
- **Ejemplos del golden y evaluación del chat**: el golden sigue siendo el conjunto de evaluación de RAG; usar sus preguntas
  para entrenar el clasificador puede inflar cualquier comparación futura sobre el golden y debe quedar visible.
- **Licencia de Olist**: el dataset es CC BY-NC-SA 4.0; los textos derivados de las reseñas heredan sus condiciones y la
  ficha lo declara.
- **Crecimiento posterior**: LAB-124 añadirá ejemplos etiquetados desde producción; los identificadores, las particiones y las
  reglas de validación no deben cambiar para los ejemplos que ya existen.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El dataset MUST usar exactamente cinco etiquetas fijas, `politica`, `producto`, `pedido`, `escalado` y `charla`,
  y cada ejemplo MUST tener una y solo una de ellas.
- **FR-002**: El dataset MUST contener al menos 600 ejemplos, cada uno con su texto, su etiqueta, su idioma (español o
  portugués), su origen y su grupo de origen.
- **FR-003**: Cada una de las cinco clases MUST tener al menos 60 ejemplos en total y al menos 20 en cada idioma.
- **FR-004**: El dataset MUST incluir ejemplos de los tres orígenes: preguntas del golden (LAB-27), comentarios de reseñas de
  Olist y paráfrasis generadas con el modelo local; cada ejemplo MUST indicar el suyo. MAY incluir además mensajes
  redactados a mano (semillas), necesarios porque el golden apenas cubre `pedido`, `producto` y `escalado`; constan como un
  cuarto origen.
- **FR-005**: Cada ejemplo derivado del golden MUST conservar el identificador del caso del golden del que procede, y cada
  paráfrasis MUST conservar el del ejemplo del que parte.
- **FR-006**: Una paráfrasis MUST entrar al dataset solo tras una revisión manual registrada que confirme que conserva la
  intención de su origen, es natural en su idioma y no contiene datos personales; las paráfrasis sin revisar MUST NOT estar en
  el dataset.
- **FR-007**: Los ejemplos del golden de categoría `off_topic` e `injection` MUST etiquetarse `charla`: la guía define `charla`
  como todo mensaje que el chat resuelve sin recuperar documentos (cortesías y redirección de lo ajeno a la tienda), igual que
  la ruta acotada de LAB-114. Un intento de inyección dentro de un mensaje de cortesía también es `charla`.
- **FR-008**: Los ejemplos del golden de categoría `small_talk` MUST etiquetarse `charla`, y las preguntas de conocimiento del
  golden MUST etiquetarse según la guía (`politica` salvo que pregunten por un pedido concreto, un producto concreto o pidan
  una persona).
- **FR-009**: Un mensaje que mezcle cortesía y una petición real MUST etiquetarse por la petición sustantiva; MUST NOT haber
  ejemplos `charla` que contengan una petición de información o de acción.
- **FR-010**: El repositorio MUST incluir una guía de etiquetado con, para cada clase, su definición, qué incluye, qué excluye
  y ejemplos en español y portugués, y una sección de casos límite con reglas de desempate para los pares de clases que más se
  confunden.
- **FR-011**: Una muestra de 50 ejemplos MUST ser etiquetada de forma independiente por dos anotadores, y el acuerdo MUST
  reportarse con el kappa de Cohen, el número de casos, las etiquetas de cada anotador y la matriz de desacuerdos.
- **FR-012**: La muestra de 50 MUST incluir las cinco clases y los dos idiomas, y MUST registrarse cada desacuerdo con su
  etiqueta final y el motivo; el kappa reportado MUST ser el calculado antes de resolver los desacuerdos.
- **FR-013**: El dataset MUST venir con una partición train/val/test estratificada por clase e idioma, fija y definida por
  una semilla, en la que cada ejemplo pertenece a exactamente una partición.
- **FR-014**: La partición MUST asignar cada grupo de origen (el original y todas sus paráfrasis) a una sola partición, y MUST
  mantener juntos los textos idénticos una vez normalizados.
- **FR-015**: Regenerar la partición con los mismos datos y la misma semilla MUST producir exactamente el mismo resultado, y
  los ejemplos ya asignados MUST conservar su partición cuando se añadan otros.
- **FR-016**: Cada partición MUST contener ejemplos de las cinco clases y de los dos idiomas, en proporción similar a la del
  dataset completo.
- **FR-017**: Todo texto MUST pasar por un enmascarado que sustituya los datos personales reconocibles por patrón (correos,
  teléfonos, CPF/CNPJ, códigos postales, identificadores de pedido o de cliente y enlaces) por una marca fija de su tipo, de
  forma idempotente.
- **FR-018**: Los comentarios de Olist MUST pasar además por una revisión manual registrada que detecte y enmascare los
  nombres de persona y otros datos que el patrón no reconoce.
- **FR-019**: Una verificación automática MUST fallar si el dataset contiene alguna coincidencia de datos personales por
  patrón, y MUST ejecutarse en cada cambio del dataset.
- **FR-020**: El repositorio MUST NOT contener el texto sin enmascarar de ninguna reseña, ni una copia de las tablas ni de los
  CSV de Olist; solo los ejemplos ya enmascarados y seleccionados.
- **FR-021**: Junto a los datos MUST existir una ficha del dataset con: origen de cada parte, tamaño por clase e idioma y por
  partición, proporción por origen, licencia de cada origen (incluida CC BY-NC-SA 4.0 de Olist), cómo se generaron y
  revisaron las paráfrasis (modelo usado, instrucciones, quién revisó y cuándo), el kappa, el enmascarado aplicado y las
  limitaciones.
- **FR-022**: Las limitaciones de la ficha MUST incluir al menos el dominio de los comentarios de Olist (reseñas y no mensajes
  a un chat), el peso de las paráfrasis sintéticas, el tamaño del dataset y el solape con el golden.
- **FR-023**: Una verificación automática MUST comprobar el esquema, las cinco etiquetas, los mínimos por clase e idioma, que no
  haya un grupo de origen en más de una partición, ni textos duplicados entre particiones, y que las cifras de la ficha
  coincidan con las del dataset; MUST fallar con un mensaje que indique el ejemplo y la regla incumplida.
- **FR-024**: La verificación MUST ejecutarse sin red, sin la base de datos de Olist y sin el modelo local, para que sea
  determinista en integración continua.
- **FR-025**: Esta historia MUST NOT modificar el dataset golden (`eval/golden.jsonl`), el clasificador de intención de LAB-36,
  el módulo de chat, las trazas ni el código de producción (`backend/`, `ingest/`); solo añade datos, guía, ficha y la
  herramienta de verificación.
- **FR-026**: El dataset, la guía y la ficha MUST NOT contener secretos, credenciales ni datos de clientes reales.

### Key Entities *(include if feature involves data)*

- **Ejemplo**: un mensaje de cliente con su texto ya enmascarado, su única etiqueta de intención, su idioma, su origen (golden,
  reseña de Olist o paráfrasis), su grupo de origen, su partición y el identificador estable que no cambia aunque se edite o se
  añadan otros.
- **Intención**: una de las cinco clases fijas (`politica`, `producto`, `pedido`, `escalado`, `charla`), con su definición y sus
  casos límite en la guía.
- **Grupo de origen**: el conjunto formado por un mensaje original y todas sus paráfrasis; es la unidad que la partición
  reparte, de modo que nunca se divide entre particiones.
- **Partición**: train, val o test, asignada por estratificación por clase e idioma con una semilla fija.
- **Revisión de paráfrasis**: registro, por cada paráfrasis aceptada, de quién la revisó, cuándo y qué comprobó.
- **Muestra doble-etiquetada**: los 50 ejemplos etiquetados por dos anotadores, con las dos etiquetas de cada caso, la
  etiqueta final, el motivo de cada desacuerdo y el kappa de Cohen resultante.
- **Guía de etiquetado**: documento con la definición de cada clase, ejemplos en ambos idiomas, casos límite y reglas de
  desempate.
- **Ficha del dataset**: documento junto a los datos con origen, tamaños, licencias, proceso, acuerdo, enmascarado y
  limitaciones.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El dataset tiene al menos 600 ejemplos y el 100 % lleva una de las cinco etiquetas fijas, un idioma y un origen
  válidos.
- **SC-002**: Cada una de las 5 clases tiene al menos 60 ejemplos y al menos 20 en cada uno de los 2 idiomas (10 de 10
  combinaciones clase-idioma cumplen el mínimo).
- **SC-003**: Se reporta el kappa de Cohen de una muestra de 50 casos doble-etiquetados de forma independiente, y el 100 % de los
  desacuerdos tiene su etiqueta final y su motivo registrados.
- **SC-004**: Ningún grupo de origen aparece en más de una de las tres particiones y ningún texto normalizado está repetido
  entre particiones (0 casos).
- **SC-005**: Dos regeneraciones de la partición con la misma semilla producen resultados idénticos en el 100 % de los
  ejemplos, y las tres particiones contienen las cinco clases y los dos idiomas.
- **SC-006**: La verificación de datos personales no encuentra ninguna coincidencia en el 100 % de los ejemplos, y existe el
  registro de la revisión manual del 100 % de los comentarios de Olist incluidos.
- **SC-007**: El 100 % de las paráfrasis del dataset tiene su revisión manual registrada y apunta al ejemplo del que parte.
- **SC-008**: La ficha contiene el 100 % de los apartados exigidos y sus cifras coinciden con las que calcula la verificación.
- **SC-009**: La verificación falla en el 100 % de los defectos provocados en las pruebas (etiqueta desconocida, clase sin
  ejemplos en un idioma, grupo repartido entre particiones, dato personal) y pasa sobre el dataset correcto sin red.
- **SC-010**: LAB-118 puede entrenar y evaluar con la partición publicada sin editar ni reordenar los datos, y sin que ninguna
  paráfrasis de un ejemplo de entrenamiento aparezca en test.

## Assumptions

- **Destinatario**: el «desarrollador» es quien entrena y evalúa el clasificador (LAB-118, LAB-126) y quien revisará el
  dataset (LAB-125); no es un usuario final del chat. El dataset es un activo de datos, no una funcionalidad de la aplicación.
- **Ubicación**: los datos, la guía y la ficha viven en `ml/`, el área de F8 (constitución: «ML (F8)»), porque LAB-118 los
  consume desde allí; la ruta exacta y el formato se fijan en el plan. El golden y su evaluación siguen en `eval/`.
- **Enmascarado «de las trazas»**: el criterio de la historia pide aplicar «el mismo enmascarado que en las trazas», pero hoy
  no existe ninguno: LAB-64 asumió datos públicos o de prueba y dejó el enmascarado para una historia propia (relacionada con
  LAB-66), y el backend no enmascara nada. Esta historia define y verifica un enmascarado mínimo para el dataset, de forma que
  otra historia pueda reutilizarlo (LAB-124 guardará texto enmascarado); no cambia las trazas ni el backend. Se señala la
  diferencia con el criterio original para confirmarla en la revisión.
- **Definiciones de las clases**: `politica` es una pregunta sobre reglas generales de la tienda (corpus de LAB-18);
  `producto`, sobre un artículo concreto o el catálogo; `pedido`, sobre un pedido concreto del cliente (consulta de LAB-41);
  `escalado`, la petición o la necesidad de hablar con una persona (queja que el bot no debe resolver solo, amenaza legal,
  petición explícita de un agente); `charla`, la conversación acotada de LAB-114 ampliada a lo ajeno a la tienda y a los intentos de inyección (decisión del
  2026-11-21: opción recomendada, no confirmada expresamente por el usuario; pendiente de revisión). La guía las precisa y es
  la fuente de verdad.
- **Orígenes y volumen**: el golden solo aporta las clases `politica` y `charla` (y quizá algún `pedido`); `producto`,
  `pedido` y `escalado` dependen sobre todo de las reseñas de Olist y de las paráfrasis y de semillas redactadas a mano. El
  reparto exacto por origen no se fija aquí; la ficha lo declara y el plan decide cómo conseguir el mínimo por clase.
- **Idiomas**: las preguntas del golden están en español y las reseñas de Olist en portugués; el resto se obtiene por
  paráfrasis y traducción revisada a mano. Se admite mezcla de idiomas y faltas de ortografía como parte del dato real.
- **Segundo anotador**: la muestra de 50 se etiqueta por dos anotadores que no ven las etiquetas del otro. Si no hay una segunda
  persona disponible, el segundo anotador puede ser el modelo local sin acceso a las etiquetas, y la ficha lo declara con
  claridad como una limitación del kappa; nunca se presenta como acuerdo entre personas.
- **Revisión manual**: «revisadas a mano» significa que una persona confirma cada paráfrasis y cada comentario de Olist; el
  dataset se considera borrador hasta que esa revisión esté registrada, igual que el golden (LAB-27).
- **Generación de paráfrasis**: usa el modelo local (Ollama a través del gateway de LAB-84), solo en la preparación de los datos;
  el dataset publicado es la fuente de verdad y no hace falta regenerarlo, y la verificación no invoca al modelo.
- **Licencia de Olist**: los comentarios proceden de un dataset público y anonimizado bajo CC BY-NC-SA 4.0. Se versionan
  únicamente los ejemplos seleccionados y enmascarados (no la tabla ni los CSV), con atribución y la condición de uso no
  comercial y compartir igual declaradas en la ficha; el uso es didáctico.
- **Proporción de la partición**: aproximadamente 70 % train, 15 % val y 15 % test, estratificada por clase e idioma; el plan
  fija los porcentajes y la semilla.
- **Kappa**: la historia exige reportarlo, no alcanzar un valor; se toma como referencia un acuerdo sustancial (≥ 0,70) y,
  si no se alcanza, se corrige la guía y se vuelve a etiquetar antes de dar el dataset por terminado.
- **Dependencias**: LAB-27 (golden, terminada), LAB-114 (charla acotada, terminada) y LAB-116 (proyecto `ml/`, terminada).
  LAB-36 (clasificador por filtros) está pendiente y solo se toma como referencia de las etiquetas `politica`, `producto` y
  `pedido`.
- **Fuera de alcance**: entrenar el clasificador (LAB-118), registrar modelos (LAB-119), versionar los datos con DVC
  (LAB-122), la cola de revisión de producción (LAB-124), la model card y la evaluación de sesgos (LAB-125), y cualquier cambio
  en el chat o en las trazas.
- **Pruebas deterministas**: ninguna prueba ni la integración continua llaman a un modelo ni a la red; las llamadas al modelo
  solo ocurren al preparar los datos.
