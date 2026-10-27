# Research: Corpus de políticas y FAQs de la tienda

Decisiones de la Fase 0. No quedó ningún `NEEDS CLARIFICATION` en el contexto técnico; aquí se fijan las elecciones que
la spec dejó abiertas por defecto.

## D-1. Tamaño y reparto del corpus

- **Decision**: 24 documentos: envíos 5, devoluciones 5, garantías 4, pagos 5, privacidad 5. En cada tema, un documento
  es la FAQ del tema (preguntas cortas) y el resto son políticas.
- **Rationale**: entra en el rango 20–30 con margen para añadir o quitar sin salir de él (FR-001) y cumple el mínimo de
  3 por tema (FR-002). Una FAQ por tema reproduce el formato de pregunta/respuesta que usan los clientes, útil para la
  recuperación en F1.
- **Alternatives considered**: 20 documentos exactos (sin margen: perder uno rompe el criterio); 30 (más redacción y
  más revisión manual sin beneficio para F1); FAQ única transversal (mezcla temas y dificulta la cobertura por tema).

## D-2. Formato del documento

- **Decision**: Markdown con front matter YAML (`title`, `topic`, `type`, `language`, `scope`) y secciones fijas:
  `## Alcance`, `## Cómo funciona`, `## Plazos y costos`, `## Casos especiales`, `## Contacto`. Las FAQs usan
  `## <pregunta>` por entrada en lugar de las secciones fijas, salvo `## Alcance` y `## Contacto`.
- **Rationale**: estructura uniforme y legible (FR-012); el front matter da a la ingesta metadatos de tema sin
  inferirlos; los encabezados son puntos naturales de fragmentación. El «alcance» hace cada documento autosuficiente
  (FR-006).
- **Alternatives considered**: texto plano (sin estructura ni metadatos); HTML (peor para edición y diff); un archivo
  por pregunta de FAQ (cientos de archivos, rompe el rango 20–30).

## D-3. Nombre de archivo

- **Decision**: `<topic>-<NN>-<slug>.md` con `topic` ∈ {`shipping`, `returns`, `warranty`, `payments`, `privacy`}, `NN`
  de dos dígitos y `slug` en inglés kebab-case (p. ej. `returns-01-return-policy.md`). El PDF comparte nombre base.
- **Rationale**: el tema se ve sin abrir el archivo (FR-011); orden estable; coherente con el criterio de nombres en
  inglés del proyecto; añadir un documento no renombra los demás.
- **Alternatives considered**: subcarpeta por tema (la ingesta tendría que recorrer árbol y el índice repetiría la
  ruta); nombres en español (mezcla con identificadores en inglés).

## D-4. Moneda, mercado y coherencia con Olist

- **Decision**: la tienda opera en reales brasileños (importes `R$`), como los precios del dataset de Olist. Los métodos
  de pago se nombran en lenguaje natural pero mapean a los del dataset: `credit_card` (tarjeta de crédito), `debit_card`
  (tarjeta de débito), `boleto` (boleto bancario) y `voucher` (vale). Los estados de pedido citados son los del dataset:
  `created`, `approved`, `invoiced`, `processing`, `shipped`, `delivered`, `unavailable`, `canceled`.
- **Rationale**: FR-008 exige coherencia con los datos cargados en LAB-17; así, cuando el agente consulte un pedido
  (`shipped`) y la base de conocimiento explique qué significa ese estado, no habrá desajuste.
- **Alternatives considered**: moneda genérica (importes ambiguos); USD (no coincide con los precios del dataset).

## D-5. Una sola fuente de valores canónicos

- **Decision**: [contracts/corpus-facts.md](contracts/corpus-facts.md) lista cada plazo, importe y condición una vez
  (p. ej. «devolución: 30 días corridos desde la entrega»). Los documentos copian esos valores literalmente; la revisión
  manual compara contra esa tabla.
- **Rationale**: la contradicción entre documentos es el riesgo principal del corpus (FR-007, SC-005) y se previene mejor
  con una tabla única que detectándola después.
- **Alternatives considered**: validador semántico de contradicciones (no fiable sin un modelo; fuera de alcance);
  confiar solo en la revisión (sin referencia común, se escapan diferencias de formato como «30 días» frente a «un mes»).

## D-6. Generación del PDF

- **Decision**: `fpdf2` + `markdown-it-py`: el Markdown se convierte a bloques (títulos, párrafos, listas, tablas) y se
  dibuja con fuentes estándar de PDF. Se fija la fecha de creación y se desactiva el identificador aleatorio para que la
  salida sea reproducible. El texto se normaliza (comillas y guiones tipográficos → ASCII) y el validador rechaza
  caracteres fuera de latin-1 en el Markdown, de forma que el PDF nunca pierde símbolos.
- **Rationale**: pura Python, instalable con `uv sync` en Windows y Linux sin bibliotecas nativas; texto seleccionable
  por construcción (FR-005); salida reproducible evita ruido en los diffs al regenerar.
- **Alternatives considered**: WeasyPrint (mejor maquetación, pero exige GTK/Pango, frágil en Windows y en CI);
  Pandoc + LaTeX (instalación pesada); `reportlab` (válido, API más verbosa para este caso); imprimir a PDF desde un
  navegador (manual, no reproducible).

## D-7. Qué documentos tienen PDF

- **Decision**: 10 de 24, dos por tema: `shipping-01`, `shipping-02`, `returns-01`, `returns-03`, `warranty-01`,
  `warranty-02`, `payments-01`, `payments-02`, `privacy-01`, `privacy-03`.
- **Rationale**: FR-003 pide al menos uno por tema; dos por tema permiten probar la ingesta de PDF con varios documentos
  sin duplicar todo el corpus. Se eligen las políticas principales, que son las que normalmente se distribuyen en PDF.
- **Alternatives considered**: PDF de los 24 (más archivos binarios en git sin beneficio); solo 5 PDF (un solo caso por
  tema, poca variedad para probar la ingesta).

## D-8. Validación automática (FR-013)

- **Decision**: `kb-tools validate` comprueba, con salida accionable y código de salida distinto de cero:
  1. recuento de documentos (por nombre base) entre 20 y 30;
  2. al menos 3 documentos por tema y al menos 1 PDF por tema;
  3. nombre de archivo y front matter válidos, y coincidencia entre el `topic` del front matter y el del nombre;
  4. `INDEX.md` y `REVIEW.md` listan exactamente los documentos del directorio;
  5. cada PDF se abre, tiene texto extraíble y su texto normalizado coincide con el del Markdown;
  6. ausencia de patrones de datos personales: correos fuera de `@shoplab.example` y números de teléfono o documentos
     de identidad evidentes;
  7. solo caracteres latin-1 en el Markdown;
  8. con `--require-reviewed`, que cada documento figure como revisado (revisor y fecha).
- **Rationale**: cubre los criterios comprobables por máquina; la lectura de contenido y la ausencia de contradicciones
  siguen siendo revisión humana (así lo dice el criterio «revisados a mano»).
- **Alternatives considered**: no validar (los errores de índice y PDF desactualizados llegarían a la ingesta);
  comprobar contradicciones por LLM (no determinista, contra el principio IV).

## D-9. Registro de revisión y control de la entrega

- **Decision**: `REVIEW.md` es una tabla (documento, revisor, fecha, estado). El CI valida el corpus con
  `--require-reviewed`: la historia solo se puede fusionar cuando todos los documentos están revisados. Mientras se
  redacta, los documentos figuran como `pendiente`.
- **Rationale**: convierte «revisados a mano» en una condición verificable sin fingir una revisión humana; los
  documentos redactados por el agente no se marcan como revisados hasta que el administrador lo confirme.
- **Alternatives considered**: marcar como revisado al redactar (incumple el espíritu del criterio); no registrar la
  revisión (no verificable, SC-004).

## D-10. Integración en CI y en el repositorio

- **Decision**: añadir `data/kb-tools` y `data/kb` al filtro de rutas de `ci.yml` y a la matriz Python (que ya ejecuta
  `ruff` y `pytest` por componente). La prueba del corpus real corre dentro de `pytest`, así que no hace falta un job
  nuevo. Los PDF se versionan en git (son pequeños, < 100 KB cada uno).
- **Rationale**: reutiliza el mecanismo existente de LAB-17 sin pasos nuevos; un corpus que se rompe falla el CI.
- **Alternatives considered**: job propio (duplica configuración); no versionar los PDF y generarlos en CI (el criterio
  pide los documentos en `data/kb`).

## D-11. Fuera de esta historia

Ingesta, troceado, embeddings y búsqueda (F1-02 en adelante); respuestas con citas; varios idiomas; interfaz de
administración de políticas (FR-014). No requiere ADR (ninguna decisión arquitectónica nueva).
