# kb-tools

Genera los PDF y valida el corpus de políticas y FAQs de `data/kb/`. La organización del corpus y cómo añadir un
documento están en [`docs/base-conocimiento.md`](../../docs/base-conocimiento.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/). No hacen falta base de datos ni backend.

## Uso

```bash
cd data/kb-tools
uv sync
uv run kb-tools build-pdf                 # genera los PDF de los documentos que lo requieren
uv run kb-tools build-pdf --only returns-01-return-policy
uv run kb-tools build-pdf --check         # no escribe; código 1 si algún PDF está desactualizado
uv run kb-tools validate                  # valida el corpus
uv run kb-tools validate --require-reviewed
```

`--kb-dir PATH` cambia el directorio del corpus (por defecto `../kb`). `validate` también acepta `--min-docs`,
`--max-docs` y `--min-per-topic` (por defecto 20, 30 y 3).

Los PDF son reproducibles (fecha de creación fija): el mismo Markdown produce los mismos bytes, así que regenerarlos no
ensucia el diff. Tienen texto seleccionable y sin imágenes.

## Reglas de `validate`

Cada error se imprime como `KBnnn ruta: mensaje`; el código de salida es 0 sin errores y 1 con al menos uno.

| Código | Regla |
|---|---|
| `KB001` | recuento de documentos fuera de rango (por documento, no por archivo) |
| `KB002` | tema con menos documentos del mínimo |
| `KB003` | tema sin ningún PDF |
| `KB004` | nombre de archivo inválido, o `topic` distinto del prefijo del nombre |
| `KB005` | front matter ausente o incompleto, valores no válidos o `title` duplicado |
| `KB006` | `INDEX.md` no coincide con el directorio |
| `KB007` | `REVIEW.md` no coincide con el directorio, o una fila `reviewed` sin revisor o fecha |
| `KB008` | PDF ilegible, sin texto o con texto distinto al del Markdown |
| `KB009` | dato personal evidente: correo fuera de `@shoplab.example`, teléfono o CPF |
| `KB010` | carácter fuera de latin-1 en un Markdown |
| `KB011` | con `--require-reviewed`: documento en `pending` |

La equivalencia entre PDF y Markdown se comprueba por los caracteres alfanuméricos de cada uno, sin tener en cuenta
espacios ni orden: la extracción de texto de un PDF pierde espacios en los saltos de línea y mezcla el orden de las celdas
de las tablas.

## Desarrollo

```bash
uv run ruff check .
uv run pytest
```

Las pruebas construyen un corpus mínimo en un directorio temporal (`tests/conftest.py`) y lo rompen regla a regla; una
prueba adicional (`tests/test_real_corpus.py`) valida el `data/kb/` real con `--require-reviewed`.
