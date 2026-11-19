# Contrato: `notebooks/data/questions.toml`

Las 20 preguntas de prueba como datos versionados, separados del código (FR-008, FR-009). Lo lee
`chunking_lab.questions.load_questions` con `tomllib` y lo valida `validate_questions` antes de medir (FR-010). El futuro
dataset golden de F2 podrá reutilizarlo, pero este archivo no lo crea ni lo condiciona.

## Formato

Codificación UTF-8. Un bloque `[[question]]` por pregunta, en orden de `id`:

```toml
[[question]]
id = "Q01"
topic = "returns"
question = "¿Cuántos días tengo para arrepentirme de una compra y devolverla gratis?"
document = "returns-01-return-policy"
passage = "dentro de los 7 días corridos desde la entrega puedes devolver cualquier producto sin dar explicaciones"
```

| Clave | Tipo | Obligatoria | Descripción |
|---|---|---|---|
| `id` | cadena | sí | `Q` + dos dígitos, `Q01` a `Q20`; único |
| `topic` | cadena | sí | `payments`, `privacy`, `returns`, `shipping` o `warranty` |
| `question` | cadena | sí | pregunta en español tal como la escribiría un cliente; se embebe sin modificar |
| `document` | cadena | sí | id del documento (nombre del archivo sin `.md`) donde está la respuesta |
| `passage` | cadena | sí | texto literal del documento que contiene la respuesta (40–300 caracteres normalizados) |

Claves desconocidas se rechazan, para que un error de escritura no pase inadvertido.

## Reglas de validación

Se evalúan todas y se informan juntas (`InvalidQuestionsError`, un mensaje por fallo con el `id` afectado):

| Código | Regla |
|---|---|
| `Q-COUNT` | exactamente 20 preguntas |
| `Q-TOPICS` | exactamente 4 por cada tema del corpus |
| `Q-ID` | `id` con el formato indicado y sin repetir |
| `Q-FIELDS` | todas las claves presentes, no vacías, sin claves desconocidas |
| `Q-DOC` | `document` existe en `data/kb` y su `topic` coincide con el de la pregunta |
| `Q-PASSAGE-MISSING` | `passage` no aparece en el documento esperado |
| `Q-PASSAGE-AMBIGUOUS` | `passage` aparece también en otro documento |
| `Q-PASSAGE-LENGTH` | `passage` fuera de 40–300 caracteres |
| `Q-LONG-PASSAGES` | menos de 5 pasajes de más de 100 caracteres en el conjunto |

**Normalización para comparar**: se colapsan los espacios en blanco consecutivos (incluidos saltos de línea) en un solo
espacio y se recortan los extremos; no se cambian mayúsculas ni tildes. El mismo criterio se usa al decidir si un chunk
contiene el pasaje, así que un pasaje que cruza un salto de línea del Markdown se escribe en una sola línea.

## Cómo se usa

- Si el corpus cambia y un pasaje deja de existir, `Q-PASSAGE-MISSING` detiene el notebook (y la prueba
  `test_questions.py` falla en CI) antes de medir nada.
- Añadir o cambiar preguntas exige mantener 20 y 4 por tema; cambiar ese número es una decisión de la spec.
