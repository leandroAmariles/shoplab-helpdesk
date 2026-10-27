# Data Model: Corpus de políticas y FAQs de la tienda

No hay base de datos: el modelo describe los archivos del corpus y sus metadatos. Reglas de formato en
[contracts/corpus-format.md](contracts/corpus-format.md); valores de negocio en
[contracts/corpus-facts.md](contracts/corpus-facts.md).

## Entidades

### Documento de conocimiento (`data/kb/<topic>-<NN>-<slug>.md`)

| Campo | Origen | Regla |
|---|---|---|
| `id` | nombre de archivo sin extensión | `^(shipping\|returns\|warranty\|payments\|privacy)-\d{2}-[a-z0-9-]+$`; único |
| `title` | front matter | no vacío, ≤ 90 caracteres, único en el corpus |
| `topic` | front matter | uno de los cinco temas; igual al prefijo del `id` |
| `type` | front matter | `policy` o `faq` |
| `language` | front matter | `es` |
| `scope` | front matter | una frase: a qué casos aplica el documento |
| contenido | cuerpo Markdown | secciones fijas (políticas) o preguntas (FAQ); solo caracteres latin-1 |
| formatos | archivos presentes | `md` siempre; `pdf` si existe `<id>.pdf` |

Reparto obligatorio (24 documentos):

| Tema | Documentos | PDF |
|---|---|---|
| `shipping` | `01-delivery-times`, `02-shipping-costs`, `03-order-tracking`, `04-delivery-issues`, `05-faq-shipping` (faq) | 01, 02 |
| `returns` | `01-return-policy`, `02-return-process`, `03-refunds`, `04-exchanges-and-cancellations`, `05-faq-returns` (faq) | 01, 03 |
| `warranty` | `01-legal-warranty`, `02-extended-warranty`, `03-warranty-claims`, `04-faq-warranty` (faq) | 01, 02 |
| `payments` | `01-payment-methods`, `02-installments`, `03-boleto-and-vouchers`, `04-payment-issues`, `05-faq-payments` (faq) | 01, 02 |
| `privacy` | `01-privacy-policy`, `02-data-collection-and-use`, `03-data-rights`, `04-cookies`, `05-faq-privacy` (faq) | 01, 03 |

### Tema

Enumeración cerrada: `shipping` (envíos), `returns` (devoluciones), `warranty` (garantías), `payments` (pagos),
`privacy` (privacidad). Cada tema exige ≥ 3 documentos y ≥ 1 PDF.

### Índice del corpus (`data/kb/INDEX.md`)

Tabla Markdown con una fila por documento: `id`, `title`, `topic`, `type`, `formats` (`md` o `md, pdf`). Invariante:
el conjunto de `id` del índice es igual al de los archivos `.md` del directorio, y `formats` refleja los PDF existentes.

### Registro de revisión (`data/kb/REVIEW.md`)

Tabla con una fila por documento: `id`, `reviewer`, `date` (ISO `YYYY-MM-DD`), `status` (`pending` o `reviewed`).
Invariantes: mismo conjunto de `id` que el directorio; `status = reviewed` exige revisor y fecha no vacíos; una
modificación del documento posterior a su revisión devuelve la fila a `pending` (control manual, vía la revisión del PR).

## Relaciones

- Tema 1–N Documento; Documento 1–1 fila de Índice; Documento 1–1 fila de Registro de revisión.
- Documento 1–0..1 PDF (mismo nombre base). El Markdown es la fuente; el PDF se regenera con `kb-tools build-pdf`.
- Los documentos no se enlazan entre sí por ruta (cada uno es autosuficiente, FR-006); pueden mencionar otro por su
  título.

## Transiciones de estado de un documento

`pending` (redactado) → `reviewed` (revisión manual registrada). Un cambio de contenido o de un valor canónico en
`corpus-facts.md` devuelve los documentos afectados a `pending`.
