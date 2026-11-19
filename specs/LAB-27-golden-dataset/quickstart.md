# Quickstart: validar el dataset golden

Guía para comprobar de principio a fin que LAB-27 funciona. Formato y reglas en
[contracts/golden-format.md](contracts/golden-format.md); tipos en [data-model.md](data-model.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- El repositorio actualizado, con el corpus en `data/kb`.
- No hacen falta Docker, Ollama, red ni base de datos.

## 1. Instalar y pasar las comprobaciones del proyecto

```bash
cd eval
uv sync --frozen
uv run ruff check .
uv run pytest
```

**Resultado esperado**: `ruff` sin avisos y `pytest` en verde con cobertura ≥ 80 %. Incluye la prueba contra el
`golden.jsonl` y el corpus reales (SC-001).

## 2. Validar el dataset y leer el resumen (US3)

```bash
uv run python -m shoplab_eval validate
```

**Resultado esperado** (código de salida `0`): entre 80 y 100 casos; cada tema con al menos 12 casos con respuesta; casos
sin respuesta entre el 15 % y el 25 % del total (≥ 12, de ellos ≥ 5 del tema y ≥ 5 `off_topic`); al menos 8 casos de dos
documentos; ningún documento del corpus sin caso (SC-001 a SC-004). Una segunda ejecución imprime exactamente lo mismo.

## 3. Comprobar que los errores se detectan (SC-006)

En una copia del archivo (nunca sobre el original), introducir un error cada vez y ejecutar
`uv run python -m shoplab_eval validate --golden <copia>`; cada uno debe fallar con código de salida `1`, nombrar el caso y
el motivo, y volver a pasar al revertirlo:

| Cambio | Código esperado |
|---|---|
| Borrar la clave `category` de una línea | `G-FIELDS` |
| Sustituir un documento esperado por `no-existe` | `G-DOC-MISSING` |
| Copiar una pregunta en otra línea | `G-DUPLICATE` |
| Quitar 10 líneas para quedar por debajo de 80 casos | `G-COUNT` |
| Escribir un número en la respuesta de un caso sin respuesta | `G-NOANSWER-DIGITS` |
| Pegar una frase literal de un documento como pregunta | `G-QUESTION-VERBATIM` |
| Dejar un fallo de cada tipo a la vez | todos los códigos juntos, no solo el primero |

**Referencia rota**: renombrar temporalmente en una copia del corpus un documento esperado
(`--kb <copia-del-corpus>`) y comprobar que se listan los casos afectados.

## 4. Revisión manual (SC-005 y US2)

1. Elegir 15 casos con respuesta de `eval/golden.jsonl` **con semilla fija y registrada**, p. ej.
   `uv run python -c "import random; print(sorted(random.Random(27).sample(range(1, 71), 15)))"` (ids `G001`–`G070`);
   para cada uno, leer solo sus documentos esperados y confirmar que la respuesta de referencia es correcta y completa.
2. Para cada caso sin respuesta del tema, buscar sus términos clave en `data/kb` (p. ej. `grep -ril "<término>" data/kb`) y
   confirmar que no hay dato publicado. Leer además las respuestas de los 20 casos sin respuesta y confirmar que no
   escriben ningún plazo en palabras («siete días», «una semana»).
3. Leer el indicador léxico del resumen de `validate` y comprobar que al menos el 30 % de las preguntas con respuesta tiene
   poco solapamiento con su documento (FR-013).
4. Anotar revisor, fecha, semilla y resultado en `eval/REVIEW.md`. Si falla un caso, revisar todos los de su tema.

**Resultado esperado**: 15 de 15 correctos, todos los casos sin respuesta sin dato en el corpus ni plazos en palabras, y el
indicador léxico en el 30 % o más.

## 5. Ampliar el conjunto con solo la guía (SC-007)

Siguiendo `eval/README.md`, añadir un caso nuevo (por ejemplo `G091`), ejecutar `validate` y comprobar que sigue pasando;
deshacerlo si era solo una prueba. **Resultado esperado**: menos de 10 minutos sin leer el código de la validación.

## 6. CI

Abrir un PR que toque `data/kb/**` **sin** tocar `eval/**` y comprobar en la matriz del job `python` que aparece el
componente `eval`. **Resultado esperado**: se ejecuta `uv run pytest` en `eval/` y pasa.

## Cuando algo falla

- **`uv sync --frozen` falla por el `uv.lock`**: regenerar con `uv lock` en `eval/` y subir el cambio.
- **`G-DOC-MISSING` tras un cambio del corpus**: el documento se eliminó o se renombró; actualizar el id en los casos
  señalados y revisar su contenido (README de `eval/`).
- **`G-QUESTION-VERBATIM`**: la pregunta es una frase del documento; reformularla como lo haría un cliente.
