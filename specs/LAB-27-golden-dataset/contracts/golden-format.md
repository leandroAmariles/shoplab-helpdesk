# Contrato: `eval/golden.jsonl`, su validación y su lectura

El dataset golden es el contrato entre esta historia y las siguientes de F2: LAB-28 (métricas de recuperación), LAB-29
(calidad de respuesta) y LAB-30 (evaluación en CI) lo leen con `load_golden`. Este documento fija el formato, las reglas
de validación (FR-014) y la interfaz de lectura y de línea de comandos.

## Formato del archivo

JSON Lines, UTF-8 sin BOM, un objeto por línea, separadas por `\n`. Sin comentarios, sin cabecera, sin coma entre líneas.
Se toleran líneas vacías (incluida la final); no cuentan como caso.

```json
{"id": "G001", "question": "Mi boleto venció sin que lo pagara, ¿qué puedo hacer?", "reference_answer": "Puedes volver a hacer el pedido y elegir otro método de pago.", "expected_documents": ["payments-03-boleto-and-vouchers"], "category": "payments"}
{"id": "G071", "question": "¿Puedo pagar mi pedido con criptomonedas?", "reference_answer": "No tengo información sobre ese método de pago; te recomiendo consultar los medios de pago disponibles o contactar con atención al cliente.", "expected_documents": [], "category": "payments"}
{"id": "G083", "question": "¿Me recomiendas una receta de pasta para esta noche?", "reference_answer": "Solo puedo ayudarte con consultas sobre la tienda: pagos, privacidad, devoluciones, envíos y garantías.", "expected_documents": [], "category": "off_topic"}
```

Las tres líneas son ilustrativas del formato; los casos reales se redactan y revisan contra el corpus (plan R7). Cada
objeto se escribe en una sola línea.

| Clave | Tipo | Obligatoria | Descripción |
|---|---|---|---|
| `id` | cadena | sí | `G` + tres dígitos; único; estable |
| `question` | cadena | sí | pregunta en español tal como la escribiría un cliente; autocontenida |
| `reference_answer` | cadena | sí | respuesta correcta según el corpus; en un caso sin respuesta, la que reconoce la falta de información |
| `expected_documents` | lista de cadenas | sí | 0, 1 o 2 ids de documento de `data/kb` (nombre sin `.md`); vacía = sin respuesta |
| `category` | cadena | sí | `payments`, `privacy`, `returns`, `shipping`, `warranty` u `off_topic` |

Claves desconocidas se rechazan, para que un error de escritura no pase inadvertido.

**Tipos de caso** (derivados, no se guardan): con respuesta = `expected_documents` no vacío; sin respuesta del tema =
vacío con categoría de tema; ajeno a la tienda = vacío con `off_topic`.

**Normalización** para comparar preguntas y para detectar frases copiadas del corpus: `casefold`, se eliminan signos de
puntuación y símbolos (`¿ ? ¡ ! , . ; : « » " ' - ( )` y markdown), y se colapsan los espacios en blanco consecutivos
(incluidos saltos de línea) en uno. Se conservan tildes y eñes.

## Reglas de validación

Se evalúan todas y se informan juntas (`InvalidGoldenError`, una línea por fallo con código, caso y motivo). Un caso con un
error estructural no se evalúa contra las reglas que dependen de la parte dañada, pero sí contra el resto.

### Lectura y caso

| Código | Regla |
|---|---|
| `G-JSON` | la línea no es un objeto JSON válido (se informa su número de línea) |
| `G-FIELDS` | falta una clave obligatoria, hay una clave desconocida o un valor tiene el tipo equivocado |
| `G-ID` | `id` no cumple `G` + tres dígitos, o se repite |
| `G-QUESTION-LENGTH` | `question` fuera de 10–300 caracteres tras recortar espacios |
| `G-ANSWER-LENGTH` | `reference_answer` fuera de 20–800 caracteres tras recortar espacios |
| `G-CATEGORY` | `category` no es una de las seis categorías |
| `G-DOC-COUNT` | más de dos documentos esperados, o con documentos repetidos |
| `G-DOC-MISSING` | un documento esperado no existe en `data/kb` |
| `G-CATEGORY-TOPIC` | en un caso con respuesta, `category` no es el tema de ninguno de sus documentos; o `off_topic` con documentos |
| `G-NOANSWER-DIGITS` | `reference_answer` de un caso sin respuesta contiene un dígito (cifra, plazo o importe inventado) |
| `G-QUESTION-VERBATIM` | `question`, normalizada, aparece tal cual dentro de un documento del corpus |
| `G-PII` | `question` o `reference_answer` contiene un correo que no es `@shoplab.example` o una secuencia de ocho o más dígitos |

### Conjunto

| Código | Regla |
|---|---|
| `G-COUNT` | el total de casos está fuera de 80–100 |
| `G-PER-TOPIC` | un tema tiene menos de 12 casos con respuesta |
| `G-UNANSWERED` | los casos sin respuesta son menos de 12 o están fuera del 15–25 % del total |
| `G-UNANSWERED-KINDS` | hay menos de 5 sin respuesta del tema o menos de 5 `off_topic` |
| `G-MULTI-DOC` | hay menos de 8 casos con dos documentos esperados |
| `G-COVERAGE` | algún documento del corpus no es esperado en ningún caso (se listan los ids) |
| `G-DUPLICATE` | dos preguntas son iguales tras normalizar (se listan ambos ids) |

La referencia rota (`G-DOC-MISSING`) cubre el caso de un documento que se elimina o renombra mientras algún caso lo
espera: se informa uno por caso afectado, con el id del documento (US3 escenario 4).

### Lo que no se valida automáticamente

Que la respuesta de referencia sea cierta, que un caso sin respuesta del tema no se pueda contestar con ningún
documento, que la respuesta de un caso sin respuesta no contenga plazos escritos en palabras («siete días», «una
semana»; `G-NOANSWER-DIGITS` solo detecta dígitos) y que al menos el 30 % de las preguntas con respuesta use palabras
distintas a las del corpus (FR-013). Son la revisión manual registrada en `eval/REVIEW.md` (plan R7). El resumen añade un
indicador de solapamiento léxico (porcentaje de preguntas con respuesta cuyas palabras de contenido —4 o más letras tras
normalizar— aparecen menos de la mitad en sus documentos esperados) que informa del valor frente al 30 %, sin hacer fallar
la validación.

## Interfaz de lectura (Python)

```text
from shoplab_eval import load_golden, validate_golden, GoldenCase, InvalidGoldenError

load_golden(path: Path = DEFAULT_GOLDEN_PATH) -> list[GoldenCase]
    # lee y valida; lanza InvalidGoldenError con todos los fallos si algo no cuadra

validate_golden(path: Path = ..., kb_dir: Path = ...) -> GoldenSummary
    # igual que load_golden pero contra el corpus indicado; devuelve el resumen
```

- `GoldenCase` expone `id`, `question`, `reference_answer`, `expected_documents`, `category` y las propiedades
  derivadas `answerable`, `kind` e `is_multi_document` (ver [data-model.md](../data-model.md)).
- La lectura es determinista: mismo archivo y mismo corpus, mismo resultado, sin red, sin base de datos y sin modelo.
- Las rutas por defecto se resuelven desde la raíz del repositorio (no desde el directorio de trabajo): `eval/golden.jsonl`
  y `data/kb`.

## Interfaz de línea de comandos

```text
uv run python -m shoplab_eval validate [--golden <ruta>] [--kb <ruta>]
```

| Resultado | Salida estándar | Código de salida |
|---|---|---|
| Válido | resumen: total, casos por categoría, casos por tipo (con respuesta / sin respuesta del tema / ajenos), casos de dos documentos, documentos sin caso (ninguno) e indicador léxico | `0` |
| Inválido | un fallo por línea con código, caso y motivo | `1` |

La salida es idéntica entre dos ejecuciones sobre el mismo archivo (US3 escenario 6).

## Cómo se usa

- Añadir un caso: una línea nueva con el siguiente `id` libre; se ejecuta `validate` y se registra en `REVIEW.md` si
  entra en la muestra de revisión.
- Cambiar un caso: se edita su línea sin cambiar el `id`; así LAB-31 y LAB-33 pueden seguir refiriéndose a él.
- Si un documento del corpus cambia, quien lo cambia busca su id en `golden.jsonl`, revisa los casos que lo esperan y lo
  anota en su PR; si se elimina o renombra, la validación falla señalando esos casos.
- Cambiar un umbral (80–100, 12, 15–25 %, 8…) es una decisión de la spec: se actualiza `spec.md` y `GoldenRules` a la vez.
