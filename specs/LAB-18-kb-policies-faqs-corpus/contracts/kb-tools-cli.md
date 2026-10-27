# Contrato: CLI `kb-tools`

Proyecto `data/kb-tools/` (Python 3.12, `uv`). Entrada de consola: `kb-tools` (`uv run kb-tools ...`). Por defecto
trabaja sobre `../kb` (relativo a `data/kb-tools/`); `--kb-dir` lo cambia.

## `kb-tools build-pdf`

Genera el PDF de los documentos que lo requieren.

| Opción | Descripción |
|---|---|
| `--kb-dir PATH` | directorio del corpus (por defecto `../kb`) |
| `--only ID` | genera solo ese documento (repetible) |
| `--all` | genera el PDF de los 10 documentos de la constante `PDF_DOCUMENT_IDS` (valor por defecto) |
| `--check` | no escribe: falla si algún PDF difiere de lo que se generaría desde el Markdown |

Comportamiento: lee el Markdown, lo renderiza (títulos, párrafos, listas, tablas, énfasis) y escribe `<id>.pdf` junto
al `.md`. La salida es reproducible: fecha de creación fija e identificador determinista; dos ejecuciones sobre el mismo
Markdown producen los mismos bytes. El PDF incluye título y texto seleccionable, sin imágenes.

Salida: una línea por documento (`ok shipping-01-delivery-times.pdf` / `unchanged ...`) y el total.

## `kb-tools validate`

Valida el corpus (FR-013). Solo lee.

| Opción | Descripción |
|---|---|
| `--kb-dir PATH` | directorio del corpus |
| `--require-reviewed` | exige que todos los documentos figuren como `reviewed` en `REVIEW.md` |
| `--min-docs N` / `--max-docs N` | rango de documentos (por defecto 20 y 30) |
| `--min-per-topic N` | mínimo por tema (por defecto 3) |

Reglas, cada una con código estable:

| Código | Regla |
|---|---|
| `KB001` | recuento de documentos fuera de rango |
| `KB002` | tema con menos documentos del mínimo |
| `KB003` | tema sin ningún PDF |
| `KB004` | nombre de archivo inválido, o `topic` del front matter distinto del prefijo del nombre |
| `KB005` | front matter ausente o incompleto (`title`, `topic`, `type`, `language`, `scope`), o `title` duplicado |
| `KB006` | `INDEX.md` no coincide con el directorio (documento faltante, sobrante o formatos incorrectos) |
| `KB007` | `REVIEW.md` no coincide con el directorio, o un `reviewed` sin revisor o fecha |
| `KB008` | PDF ilegible, sin texto extraíble o con texto distinto al del Markdown |
| `KB009` | dato personal evidente: correo fuera de `@shoplab.example`, teléfono o documento de identidad |
| `KB010` | carácter fuera de latin-1 en un Markdown |
| `KB011` | con `--require-reviewed`: documento con `status = pending` |

Formato de cada error: `KB006 data/kb/INDEX.md: falta el documento 'returns-03-refunds' (añádelo a la tabla)`. Código
de salida `0` sin errores, `1` con al menos uno. Al final: `N documentos, M PDF, E errores`.

Patrones de datos personales: correo `[\w.+-]+@[\w-]+\.[\w.-]+` cuyo dominio no sea `shoplab.example`; teléfono
= secuencia de 9 o más dígitos con separadores opcionales (`+ ( ) - .` y espacios) que no esté precedida por `R$`;
documentos tipo CPF `\d{3}\.\d{3}\.\d{3}-\d{2}`.

## Integración

- `uv run pytest` ejecuta las pruebas de la herramienta y una prueba que valida el `data/kb/` real con
  `--require-reviewed`; el CI la corre en la matriz Python existente.
- `uv run ruff check .` con la configuración del resto de proyectos Python (línea de 121 caracteres).
