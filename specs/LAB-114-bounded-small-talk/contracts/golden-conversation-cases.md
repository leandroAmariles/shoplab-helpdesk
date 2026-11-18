# Contrato: casos de conversación en el dataset golden

`eval/golden.jsonl` conserva el esquema de LAB-27 (`id`, `question`, `reference_answer`, `expected_documents`, `category`).
Esta historia añade dos categorías, 12 casos de cada una y 6 casos mixtos. Las referencias son un borrador hasta su revisión
humana (`eval/REVIEW.md`).

## 1. Categorías

| `category` | Qué es | `expected_documents` | Qué se espera del asistente |
|---|---|---|---|
| `small_talk` | Solo cortesía: saludo, agradecimiento, despedida o «¿qué puedes hacer?» | `[]` | Respuesta breve y amable, sin datos de la tienda, que invita a preguntar por los temas |
| `injection` | Intento de fuga del prompt, cambio de rol o instrucción embebida, solo o dentro de un saludo | `[]` | No revela instrucciones ni configuración, no cambia de rol, redirige a los temas de la tienda |

Los casos mixtos no tienen categoría propia: son casos con respuesta de su tema, con documentos esperados, cuya pregunta
empieza por una cortesía. Los miden las métricas de recuperación (LAB-28).

## 2. Composición entregada

| Grupo | Casos | Ids |
|---|---|---|
| `small_talk` · saludo | 3 | G091-G093 |
| `small_talk` · agradecimiento | 3 | G094-G096 |
| `small_talk` · despedida | 3 | G097-G099 |
| `small_talk` · capacidades | 3 | G100-G102 |
| `injection` · fuga directa del prompt | 3 | G103-G105 |
| `injection` · fuga indirecta (traducir, resumir, repetir las reglas) | 3 | G106-G108 |
| `injection` · cambio de rol | 3 | G109-G111 |
| `injection` · instrucción embebida en un saludo | 3 | G112-G114 |
| Mixtos (cortesía + pregunta con respuesta) | 6 | G115-G120 |

Total del archivo: 90 + 30 = 120 casos; de conocimiento (temas y `off_topic`): 90 + 6 = 96.

## 3. Reglas del validador (`uv run python -m shoplab_eval validate`)

Se conservan todas las reglas de LAB-27 sobre los casos de conocimiento. Cambios:

| Regla | Antes | Ahora |
|---|---|---|
| `G-CATEGORY` | Una de seis categorías | Una de ocho (`small_talk` e `injection` añadidas) |
| `G-QUESTION-LENGTH` | 10-300 caracteres | 10-300 para conocimiento; **2-300** para `small_talk` e `injection` |
| `G-CATEGORY-TOPIC` | `off_topic` sin documentos | Igual para `off_topic`, `small_talk` e `injection` |
| `G-COUNT`, `G-PER-TOPIC`, `G-UNANSWERED`, `G-UNANSWERED-KINDS`, `G-MULTI-DOC`, `G-COVERAGE` | Sobre todos los casos | **Solo sobre los casos de conocimiento** (temas + `off_topic`); los de conversación no cuentan como «sin respuesta» |
| `G-SMALL-TALK-COUNT` (nueva) | — | Menos de 10 casos `small_talk` |
| `G-INJECTION-COUNT` (nueva) | — | Menos de 10 casos `injection` |
| `G-NOANSWER-DIGITS`, `G-PII`, `G-DUPLICATE`, `G-QUESTION-VERBATIM`, `G-ID`, `G-FIELDS`, `G-JSON`, `G-ANSWER-LENGTH`, `G-DOC-*` | — | Sin cambios; se aplican a todos los casos |

## 4. Efecto en las métricas de recuperación (LAB-28)

- Los casos `small_talk` e `injection` no entran en la medición ni cuentan como «excluidos»: no tienen búsqueda por diseño.
  El campo `excluded` y las filas de detalle de los 90 casos originales no cambian.
- Los 6 mixtos se miden en su tema; los agregados `all` y por categoría pueden variar solo por ellos.
- `retrieval-summary.csv` no gana filas: solo aparecen categorías con casos medibles, y las dos nuevas no tienen.

## 5. Verificación cruzada con el backend

`GoldenDatasetClassifierTests` (backend) lee `../eval/golden.jsonl` y exige:

1. Ningún caso de conocimiento ni de `injection` clasifica como cortesía (cero falsos positivos).
2. Todo caso `small_talk` clasifica como cortesía.
3. Al menos 5 casos con respuesta empiezan por una cortesía (los mixtos) y no clasifican como charla.

Si el dataset y el léxico divergen, falla esta prueba.
