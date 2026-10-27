# Feature Specification: Corpus de políticas y FAQs de la tienda

**Feature Branch**: `feature/LAB-18-kb-policies-faqs-corpus`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "LAB-18 (F1-01) Corpus de políticas y FAQs de la tienda. Como administrador, quiero un corpus
de 20–30 políticas y FAQs de la tienda. Criterios: documentos en Markdown y PDF en `data/kb`; cubre envíos,
devoluciones, garantías, pagos y privacidad; revisados a mano."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Base de conocimiento publicada con cobertura completa (Priority: P1)

Como administrador, quiero disponer de un conjunto de 20 a 30 documentos de políticas y preguntas frecuentes de la
tienda, ubicados en la carpeta `data/kb`, que cubran envíos, devoluciones, garantías, pagos y privacidad, para tener el
contenido sobre el que el asistente responderá a los clientes (la indexación y la búsqueda llegan en historias
posteriores de F1).

**Why this priority**: Sin contenido no hay base de conocimiento: todas las historias siguientes de F1 (ingesta,
recuperación, respuestas con citas, evaluación) dependen de este corpus. Es el MVP de la historia.

**Independent Test**: Abrir `data/kb`, contar los documentos y comprobar que hay entre 20 y 30, que cada uno de los cinco
temas tiene al menos un documento y que cada documento se lee de principio a fin sin ayuda de otro material.

**Acceptance Scenarios**:

1. **Given** el repositorio actualizado, **When** el administrador lista `data/kb`, **Then** encuentra entre 20 y 30
   documentos de políticas y FAQs (contando cada documento una sola vez aunque exista en ambos formatos).
2. **Given** el corpus, **When** se agrupan los documentos por tema, **Then** hay al menos un documento de cada uno de
   los temas: envíos, devoluciones, garantías, pagos y privacidad.
3. **Given** un documento cualquiera, **When** el administrador lo lee, **Then** es autosuficiente: tiene título, alcance
   y contenido claro, sin depender de otro documento para entender la respuesta.
4. **Given** una pregunta típica de un cliente sobre cualquiera de los cinco temas (p. ej. «¿cuánto tarda mi envío?»,
   «¿puedo devolver un producto?»), **When** se busca en el corpus, **Then** existe al menos un documento que la responde
   de forma concreta.

---

### User Story 2 - Contenido en Markdown y en PDF (Priority: P1)

Como administrador, quiero que el corpus esté disponible tanto en Markdown como en PDF, para poder editarlo y versionarlo
con comodidad (Markdown) y, a la vez, disponer de documentos en el formato en que normalmente se distribuyen las
políticas de una tienda (PDF), de modo que la ingesta posterior se pruebe con ambos tipos de archivo.

**Why this priority**: Es un criterio de aceptación explícito de la historia y condiciona el trabajo de la ingesta en F1
(leer ambos formatos). Se separa de la historia 1 porque puede verificarse de forma independiente del contenido.

**Independent Test**: Comprobar que `data/kb` contiene archivos `.md` y archivos `.pdf`, que ambos tipos se abren sin
error y que, para cada documento que existe en los dos formatos, el contenido es equivalente.

**Acceptance Scenarios**:

1. **Given** el corpus, **When** se listan los archivos por extensión, **Then** hay archivos `.md` y archivos `.pdf`.
2. **Given** un documento disponible en ambos formatos, **When** se comparan, **Then** el texto del PDF coincide con el
   del Markdown (mismo contenido, solo cambia la presentación).
3. **Given** cualquier archivo `.pdf` del corpus, **When** se abre y se selecciona su texto, **Then** el texto es
   seleccionable y copiable (no es una imagen escaneada).
4. **Given** cualquier archivo `.md` del corpus, **When** se abre en un visor de Markdown, **Then** se muestra sin
   errores de formato (títulos, listas y tablas legibles).

---

### User Story 3 - Contenido revisado a mano y coherente con los datos de la tienda (Priority: P1)

Como administrador, quiero que cada documento haya sido revisado a mano antes de entrar al corpus y que sea coherente
con los datos de la tienda (los pedidos, métodos de pago y estados del dataset de Olist ya cargado), para que el asistente
no responda con información contradictoria, inventada o que choque con lo que el cliente ve en su pedido.

**Why this priority**: El asistente solo será tan fiable como su base de conocimiento; un documento contradictorio o con
datos personales degrada todas las respuestas posteriores. Es un criterio de aceptación explícito.

**Independent Test**: Revisar el registro de revisión del corpus y verificar que cada documento figura como revisado,
que no hay contradicciones entre documentos sobre un mismo asunto y que no aparece ningún dato personal real.

**Acceptance Scenarios**:

1. **Given** el corpus, **When** el administrador consulta el registro de revisión, **Then** cada documento figura como
   revisado a mano, con quién lo revisó y cuándo.
2. **Given** dos documentos que tratan el mismo asunto (p. ej. plazos de devolución en la política y en una FAQ),
   **When** se comparan, **Then** los plazos, importes y condiciones coinciden.
3. **Given** los documentos de pagos, **When** se revisan, **Then** los métodos de pago mencionados coinciden con los que
   existen en los pedidos del dataset cargado (tarjeta de crédito, boleto, voucher y tarjeta de débito).
4. **Given** el corpus completo, **When** se busca información personal real (nombres, correos, teléfonos, documentos
   de identidad, direcciones de personas), **Then** no aparece ninguna; solo canales de contacto genéricos de la tienda.

---

### User Story 4 - Corpus fácil de mantener y de localizar por tema (Priority: P2)

Como administrador, quiero que los documentos sigan una convención de nombres y de estructura uniforme, con un índice
del corpus que indique tema, título y formato de cada uno, para poder encontrar, actualizar o añadir una política sin
revisar todo el directorio.

**Why this priority**: Mejora el mantenimiento y reduce errores al ampliar el corpus, pero la historia entrega valor sin
ella (el contenido de las historias 1 a 3 ya sirve para las historias siguientes).

**Independent Test**: Abrir el índice y comprobar que lista todos los documentos del directorio y solo esos, y que
añadir un documento nuevo siguiendo la convención no requiere cambiar los existentes.

**Acceptance Scenarios**:

1. **Given** el directorio `data/kb`, **When** el administrador abre el índice del corpus, **Then** ve todos los
   documentos con su tema, título y formatos disponibles, y ningún archivo del directorio queda sin listar.
2. **Given** los nombres de archivo, **When** se leen, **Then** siguen una convención uniforme que revela el tema del
   documento sin abrirlo.
3. **Given** un documento nuevo que respeta la convención, **When** se añade al directorio y al índice, **Then** el
   resto del corpus no necesita modificarse.

---

### Edge Cases

- **Corpus fuera de rango**: menos de 20 o más de 30 documentos incumple la historia; el recuento se hace por documento
  (un mismo documento en Markdown y PDF cuenta una vez).
- **Tema sin cobertura**: si algún tema de los cinco queda sin documento, el corpus no se considera completo.
- **Discrepancia entre formatos**: si el PDF y el Markdown de un mismo documento difieren, prevalece el Markdown como
  fuente y el PDF se regenera.
- **Contradicción entre documentos**: se corrige antes de dar el corpus por revisado; no se admiten dos plazos o importes
  distintos para el mismo asunto.
- **Información que depende del cliente o del pedido** (estado concreto de un pedido, importe de un reembolso, fecha
  exacta de entrega): el corpus solo describe las reglas generales; el dato de un pedido concreto lo aporta el sistema de
  pedidos, nunca la base de conocimiento.
- **Datos personales y sensibles**: el corpus no contiene datos reales de personas; los ejemplos usan datos ficticios
  evidentes.
- **PDF no seleccionable** (imagen escaneada): se rechaza, porque la ingesta no podría extraer su texto.
- **Idioma**: todos los documentos están en el mismo idioma que el resto de la documentación de cara al usuario
  (español); no se mezclan idiomas dentro de un documento.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST incluir en `data/kb` un corpus de entre 20 y 30 documentos distintos de políticas y
  preguntas frecuentes de la tienda.
- **FR-002**: El corpus MUST cubrir los cinco temas: envíos, devoluciones, garantías, pagos y privacidad, con al menos
  tres documentos por tema.
- **FR-003**: Cada documento MUST estar disponible en Markdown; un subconjunto representativo de los documentos MUST estar
  además disponible en PDF, con al menos un documento en PDF por tema.
- **FR-004**: Para cada documento disponible en ambos formatos, el contenido textual MUST ser equivalente; el Markdown es
  la fuente y el PDF se genera a partir de él.
- **FR-005**: Los PDF MUST contener texto seleccionable (no imágenes) y MUST abrirse sin error.
- **FR-006**: Cada documento MUST tener título, tema y alcance explícitos y ser autosuficiente, de modo que se entienda
  sin leer otro documento.
- **FR-007**: El contenido MUST ser concreto y verificable (plazos, importes o porcentajes, condiciones, pasos y canales
  de contacto), evitando afirmaciones vagas, y MUST ser coherente entre documentos: un mismo asunto no puede tener dos
  valores distintos.
- **FR-008**: Las políticas de pago MUST ser coherentes con los métodos de pago presentes en los datos de la tienda
  (tarjeta de crédito, boleto, voucher y tarjeta de débito) y las de envío, devolución y garantía con los estados de
  pedido existentes en esos datos.
- **FR-009**: El corpus MUST NOT contener datos personales reales (nombres, correos, teléfonos, documentos de identidad,
  direcciones de personas) ni secretos; solo canales de contacto genéricos de la tienda y ejemplos con datos ficticios.
- **FR-010**: Todos los documentos MUST haber sido revisados a mano, y la revisión MUST quedar registrada por documento
  (revisor y fecha) en un registro de revisión dentro del corpus.
- **FR-011**: Los archivos MUST seguir una convención de nombres uniforme que indique el tema, y el corpus MUST incluir un
  índice que liste cada documento con su tema, título y formatos; el índice MUST coincidir exactamente con el contenido
  del directorio.
- **FR-012**: Los documentos MUST estar escritos en español, con estructura uniforme (título, alcance, contenido por
  secciones) para facilitar su lectura y su posterior fragmentación en la ingesta.
- **FR-013**: Una verificación automática MUST comprobar el número de documentos (20–30), la cobertura de los cinco
  temas, la correspondencia del índice con el directorio, la existencia de PDF con texto extraíble y la ausencia de
  patrones de datos personales evidentes (correos y teléfonos reales), y MUST fallar si alguna condición no se cumple.
- **FR-014**: Quedan fuera de alcance: la ingesta, el troceado, los embeddings y la búsqueda sobre el corpus; las
  respuestas del asistente y sus citas; el soporte de varios idiomas; la carga del corpus en una base de datos; y la
  gestión de versiones o publicación de políticas desde una interfaz de administración.

### Key Entities *(include if feature involves data)*

- **Documento de conocimiento**: unidad del corpus (política o FAQ); atributos: título, tema, tipo (política o FAQ),
  alcance, contenido, formatos disponibles (Markdown y, si aplica, PDF) y estado de revisión.
- **Tema**: clasificación del documento; uno de envíos, devoluciones, garantías, pagos o privacidad.
- **Índice del corpus**: relación de todos los documentos con su tema, título y formatos; refleja el contenido del
  directorio.
- **Registro de revisión**: constancia, por documento, de quién lo revisó a mano y cuándo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El corpus contiene entre 20 y 30 documentos distintos y el 100 % de los cinco temas tiene al menos tres
  documentos.
- **SC-002**: El 100 % de los documentos está disponible en Markdown y al menos un documento por tema lo está también en
  PDF.
- **SC-003**: El 100 % de los PDF se abre sin error y su texto es seleccionable; el 100 % de los documentos con ambos
  formatos tiene contenido equivalente.
- **SC-004**: El 100 % de los documentos figura como revisado a mano en el registro, con revisor y fecha.
- **SC-005**: Para un conjunto de al menos 25 preguntas típicas de clientes (cubriendo los cinco temas), el 100 % tiene
  un documento que la responde de forma concreta, y 0 preguntas reciben respuestas contradictorias entre documentos.
- **SC-006**: 0 datos personales reales en el corpus, comprobado por revisión manual y por la verificación automática.
- **SC-007**: El índice lista el 100 % de los documentos del directorio y ninguno inexistente.
- **SC-008**: Las historias siguientes de F1 (ingesta y recuperación) pueden empezar sin necesidad de crear ni reescribir
  contenido de políticas.

## Assumptions

- **Tienda ficticia**: ShopLab es una tienda de demostración; las políticas son inventadas pero verosímiles, no
  asesoramiento legal ni reflejan una empresa real.
- **Contexto del dataset**: los pedidos del dataset de Olist (LAB-17) son el modelo de negocio de referencia; por eso los
  métodos de pago y los estados de pedido de las políticas se alinean con los de ese dataset.
- **Idioma**: español, igual que el resto de la documentación de cara al usuario; el soporte multi-idioma queda fuera.
- **PDF como subconjunto**: no es necesario duplicar los 20–30 documentos en PDF; basta un subconjunto representativo
  (al menos uno por tema) para probar la ingesta con ambos formatos. Si se prefiere PDF para todos, el criterio sigue
  cumpliéndose.
- **Origen del contenido**: redactado para el proyecto (apoyado en prácticas habituales del comercio electrónico) y
  revisado a mano por el administrador; no se copian políticas de otras empresas.
- **Revisor**: el administrador de la tienda (quien abre la historia) realiza la revisión manual y la registra.
- **Ubicación**: los documentos viven en `data/kb`, carpeta ya reservada en la estructura del monorepo (LAB-9); no se
  versionan secretos ni datos personales.
- **Dependencias**: no hay dependencia técnica con otras historias; es la primera historia de la fase F1 y alimenta las
  de ingesta y recuperación posteriores.
