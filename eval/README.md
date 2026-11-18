# eval/ — dataset golden y evaluación

Aquí vive el **dataset golden** de ShopLab Helpdesk: un conjunto fijo de preguntas de clientes con su respuesta de
referencia y los documentos de la base de conocimiento (`data/kb`) donde está esa respuesta. Es la vara de medir con la que
las historias de F2 juzgan al asistente: métricas de recuperación (LAB-28), calidad de respuesta (LAB-29) y evaluación en CI
(LAB-30). Sin un conjunto fijo, cada cambio de prompt, chunking o recuperación sería una impresión y no un dato.

| Archivo | Para qué sirve |
|---|---|
| `golden.jsonl` | El dataset: 90 casos, uno por línea |
| `REVIEW.md` | Revisión manual registrada (quién revisó qué y cuándo) |
| `src/shoplab_eval/` | Lector y validación del dataset (`load_golden`, `validate_golden`) |
| `tests/` | Pruebas, incluida una contra el `golden.jsonl` y el `data/kb` reales |

Especificación y decisiones: [`specs/LAB-27-golden-dataset/`](../specs/LAB-27-golden-dataset/).

## Formato de cada caso

JSON Lines en UTF-8 **sin BOM**: un objeto por línea, sin comentarios ni cabecera. Se toleran líneas vacías.

```json
{"id": "G001", "question": "Mi boleto venció sin que lo pagara, ¿qué puedo hacer?", "reference_answer": "Si el boleto vence sin pago, el pedido pasa a cancelado. Puedes volver a hacer el pedido y elegir otro método de pago.", "expected_documents": ["payments-03-boleto-and-vouchers"], "category": "payments"}
{"id": "G071", "question": "¿Qué porcentaje de interés me cobran en las cuotas que llevan recargo?", "reference_answer": "No tengo información sobre el porcentaje de interés de las cuotas con recargo en los documentos disponibles. Te recomiendo consultarlo con atención al cliente antes de elegir el número de cuotas.", "expected_documents": [], "category": "payments"}
{"id": "G083", "question": "¿Me recomiendas una receta de pasta para esta noche?", "reference_answer": "Solo puedo ayudarte con consultas sobre la tienda: pagos, privacidad, devoluciones, envíos y garantías. No tengo información sobre recetas de cocina.", "expected_documents": [], "category": "off_topic"}
```

| Clave | Qué es |
|---|---|
| `id` | `G` + tres dígitos (`G001`). Único y **estable**: no cambia nunca, aunque el caso se edite o se reordene |
| `question` | La pregunta, en español, tal como la escribiría un cliente. Autocontenida (sin «¿y en cuotas?») |
| `reference_answer` | La respuesta correcta según el corpus, redactada con palabras propias |
| `expected_documents` | De 0 a 2 ids de documento de `data/kb` (el nombre del archivo sin `.md`) donde está la respuesta |
| `category` | `payments`, `privacy`, `returns`, `shipping`, `warranty` u `off_topic` |

Se rechazan las claves desconocidas.

### Casos con y sin respuesta

- **Con respuesta**: `expected_documents` tiene uno o dos documentos. Si dos documentos repiten el mismo dato (una política y
  su FAQ), se listan los dos; si la respuesta exige **juntar** información de dos documentos, también.
- **Sin respuesta del tema**: la pregunta es de un tema de la tienda pero el dato **no está publicado** (p. ej. el
  porcentaje de interés de las cuotas). `expected_documents` va vacío y `category` conserva el tema. La respuesta de
  referencia reconoce que no hay información y **no inventa nada**.
- **Ajena a la tienda** (`off_topic`): nada que ver con la tienda (una receta, el tiempo). Lista vacía y categoría
  `off_topic`.

El tipo se deduce de la lista de documentos y de la categoría; no se guarda aparte.

La respuesta de referencia de un caso sin respuesta describe la **intención** («no hay información»), no el texto exacto del
mensaje fijo que el asistente usa cuando no recupera contexto (LAB-22).

## Validar

```bash
cd eval
uv sync --frozen
uv run python -m shoplab_eval validate     # resumen y código de salida 0, o un fallo por línea y código 1
uv run pytest                              # pruebas + el dataset real contra el corpus real
uv run ruff check .
```

Dos ejecuciones sobre el mismo archivo dan exactamente la misma salida. Para otro archivo o corpus:
`--golden <ruta> --kb <ruta>`. Desde Python: `from shoplab_eval import load_golden` (lanza `InvalidGoldenError` con todos los
fallos juntos).

### Qué significa cada código

| Código | Qué falla |
|---|---|
| `G-JSON` | la línea no es JSON válido, o el archivo tiene BOM o no es UTF-8 |
| `G-FIELDS` | falta una clave, hay una desconocida o un valor tiene el tipo equivocado |
| `G-ID` | el id no es `G` + tres dígitos, o se repite |
| `G-QUESTION-LENGTH` / `G-ANSWER-LENGTH` | pregunta fuera de 10–300 caracteres o respuesta fuera de 20–800 |
| `G-CATEGORY` | la categoría no es una de las seis |
| `G-DOC-COUNT` | más de dos documentos esperados, o repetidos |
| `G-DOC-MISSING` | un documento esperado no existe en `data/kb` (se renombró o se borró) |
| `G-CATEGORY-TOPIC` | la categoría no es el tema de ninguno de sus documentos, o `off_topic` con documentos |
| `G-NOANSWER-DIGITS` | la respuesta de un caso sin respuesta contiene una cifra (plazo o importe inventado) |
| `G-QUESTION-VERBATIM` | la pregunta es una frase copiada de un documento: reescríbela como un cliente |
| `G-PII` | un correo que no es `@shoplab.example` o una secuencia de 8 o más dígitos |
| `G-COUNT` | el total no está entre 80 y 100 |
| `G-PER-TOPIC` | un tema tiene menos de 12 casos con respuesta |
| `G-UNANSWERED` | los casos sin respuesta son menos de 12 o están fuera del 15–25 % del total |
| `G-UNANSWERED-KINDS` | menos de 5 sin respuesta del tema o menos de 5 `off_topic` |
| `G-MULTI-DOC` | menos de 8 casos con dos documentos |
| `G-COVERAGE` | algún documento del corpus no lo espera ningún caso |
| `G-DUPLICATE` | dos preguntas son iguales salvo mayúsculas, espacios o puntuación |

El resumen añade un **indicador léxico** (porcentaje de preguntas con respuesta cuyas palabras de contenido aparecen menos de
la mitad en sus documentos). Debe ser al menos el 30 % (FR-013) para que medir la recuperación no se reduzca a coincidencia
de texto; es informativo y no hace fallar la validación.

## Añadir un caso

1. Elige el siguiente `id` libre (`G091`, …). Con 100 casos se alcanza el máximo; subirlo es una decisión de la spec.
2. Abre los documentos del tema en `data/kb/` y redacta la pregunta como un cliente, **con palabras distintas** a las del
   documento. Redacta la respuesta solo con lo que dicen los documentos esperados; **no inventes datos**.
3. Para un caso **sin respuesta del tema**, busca los términos clave en `data/kb` (`grep -ril "<término>" data/kb`, con y sin
   tilde) y confirma que ningún documento permite contestar la pregunta, ni siquiera en parte. Su respuesta no lleva cifras
   **ni plazos o cantidades escritos en palabras**.
4. Añade la línea a `golden.jsonl`, ejecuta `uv run python -m shoplab_eval validate` y corrige lo que señale.
5. Una persona revisa el caso contra sus documentos y lo anota en `REVIEW.md`.

Cambiar un caso: edita su línea **sin tocar el `id`**, para que LAB-31 (ejecuciones) y LAB-33 (feedback) sigan
refiriéndose a él.

Sin datos personales reales: si necesitas un número de pedido, usa uno obviamente ficticio (`PED-0001`); los correos solo
pueden ser `@shoplab.example`.

## Cuando cambia el corpus

La validación se ejecuta en CI también cuando cambia `data/kb/**`.

- Si un documento **se elimina o se renombra**, `G-DOC-MISSING` señala cada caso que lo esperaba: actualiza el id.
- Si un documento **cambia de contenido**, la validación no lo detecta: quien lo cambia busca su id en `golden.jsonl`
  (`grep "<id-del-documento>" eval/golden.jsonl`), revisa que las respuestas de referencia siguen siendo ciertas y lo anota
  en su PR. Una respuesta de referencia falsa contamina todas las métricas.
- Si aparece información que contesta un caso **sin respuesta**, ese caso deja de serlo: cambia la pregunta o conviértelo
  en un caso con respuesta, y mantén el reparto del resumen.

## Revisión manual

Lo que la validación no puede comprobar lo revisa una persona y queda en [`REVIEW.md`](REVIEW.md): que cada respuesta de
referencia es cierta y completa leyendo solo sus documentos, que las preguntas sin respuesta del tema no se pueden contestar
con el corpus, y que ninguna respuesta sin respuesta escribe plazos en palabras. Además se repasa una **muestra de 15 casos
con semilla fija**. Si falla un caso de la muestra, se revisan todos los de su tema.

## Cambiar un umbral

Los números (80–100 casos, 12 con respuesta por tema, 15–25 % sin respuesta, 8 casos de dos documentos…) vienen de
`specs/LAB-27-golden-dataset/spec.md` y viven en `GoldenRules` (`src/shoplab_eval/constants.py`). Cambiar uno es una decisión
de la spec: se actualizan los dos a la vez.
