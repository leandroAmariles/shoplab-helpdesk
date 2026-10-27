# Quickstart: validar el corpus de políticas y FAQs

Guía para comprobar de punta a punta que la historia funciona. Contratos: [corpus-format](contracts/corpus-format.md),
[corpus-facts](contracts/corpus-facts.md), [kb-tools-cli](contracts/kb-tools-cli.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/). No hacen falta base de datos ni backend.

## 1. Preparar la herramienta

```bash
cd data/kb-tools
uv sync
```

## 2. Regenerar los PDF desde el Markdown

```bash
uv run kb-tools build-pdf
uv run kb-tools build-pdf --check     # debe terminar con código 0 (los PDF están al día)
```

Esperado: 10 PDF en `data/kb/`, uno o más por tema; una segunda ejecución informa `unchanged` (reproducible).

## 3. Validar el corpus

```bash
uv run kb-tools validate
```

Esperado: `24 documentos, 10 PDF, 0 errores`.

Mientras la revisión manual esté pendiente, `uv run kb-tools validate --require-reviewed` falla con `KB011`; con todos
los documentos en `REVIEW.md` como `reviewed`, termina con código 0.

## 4. Comprobaciones manuales (criterios de la historia)

| Verificación | Cómo | Esperado |
|---|---|---|
| Entre 20 y 30 documentos (FR-001, US1) | `ls data/kb/*.md` sin `INDEX.md` ni `REVIEW.md` | 24 |
| Cinco temas cubiertos (FR-002, US1) | prefijos `shipping`, `returns`, `warranty`, `payments`, `privacy` | ≥ 3 cada uno |
| Markdown y PDF (FR-003, US2) | `ls data/kb/*.pdf` | 10 PDF, ≥ 1 por tema |
| PDF seleccionable (FR-005, US2) | abrir `returns-01-return-policy.pdf` y copiar un párrafo | el texto se copia |
| Coherencia de valores (FR-007, US3) | comparar `returns-01`, `returns-05` y `corpus-facts.md` | mismos plazos e importes |
| Pagos y estados de Olist (FR-008, US3) | contrastar `payments-01` y los estados citados con los valores de `order_payments.payment_type` y `orders.order_status` (tras cargar el dataset: `SELECT DISTINCT ...`) | mismos nombres |
| Sin datos personales (FR-009, US3) | `kb-tools validate` + lectura | sin `KB009` |
| Índice al día (FR-011, US4) | abrir `data/kb/INDEX.md` | lista los 24 documentos |

## 5. Pruebas negativas (las cubre pytest)

```bash
uv run pytest
```

Casos incluidos: eliminar un documento deja el recuento fuera de rango (`KB001`); quitar los documentos de un tema
(`KB002`); PDF distinto de su Markdown (`KB008`); correo externo en un documento (`KB009`); un carácter fuera de latin-1
(`KB010`); índice con un documento faltante (`KB006`).

## 6. Revisión manual (antes de fusionar)

1. Leer cada documento contra `corpus-facts.md`.
2. Anotar revisor y fecha en `data/kb/REVIEW.md` y pasar la fila a `reviewed`.
3. Ejecutar `uv run kb-tools validate --require-reviewed` hasta obtener 0 errores.
