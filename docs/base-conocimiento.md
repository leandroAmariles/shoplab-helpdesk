# Base de conocimiento: políticas y FAQs

El corpus de políticas y preguntas frecuentes de ShopLab vive en [`data/kb/`](../data/kb). Es el contenido sobre el que
el asistente responderá a los clientes; la ingesta y la búsqueda llegan en historias posteriores de F1. ShopLab es una
tienda ficticia: las políticas son inventadas pero verosímiles y no son asesoramiento legal.

## Contenido

24 documentos en español, repartidos en cinco temas. En cada tema, un documento es la FAQ y el resto son políticas.

| Tema (`topic`) | Documentos | Con PDF |
|---|---|---|
| `shipping` (envíos) | 5 | 01, 02 |
| `returns` (devoluciones) | 5 | 01, 03 |
| `warranty` (garantías) | 4 | 01, 02 |
| `payments` (pagos) | 5 | 01, 02 |
| `privacy` (privacidad) | 5 | 01, 03 |

- `INDEX.md` lista cada documento con su tema, título, tipo y formatos.
- `REVIEW.md` registra la revisión manual de cada documento (revisor y fecha).
- El Markdown es la fuente; el PDF se genera a partir de él y, si difieren, prevalece el Markdown.

## Convención

- **Nombre**: `<tema>-NN-<slug>.md`, con tema y slug en inglés (`returns-01-return-policy.md`). El PDF comparte nombre.
- **Front matter**: `title`, `topic`, `type` (`policy` o `faq`), `language: es` y `scope`.
- **Secciones** de una política: `Alcance`, `Cómo funciona`, `Plazos y costos`, `Casos especiales`, `Contacto`. Una FAQ
  tiene `Alcance`, una sección `## ¿Pregunta?` por entrada y `Contacto`.
- **Caracteres**: solo latin-1 (sin comillas tipográficas, guiones largos ni emojis). Importes con formato `R$ 19,90`.
- **Datos personales**: ninguno. Los correos son solo `@shoplab.example` y no se publican teléfonos.
- **Valores**: plazos, importes y condiciones salen de una única tabla de valores canónicos
  ([`corpus-facts.md`](../specs/LAB-18-kb-policies-faqs-corpus/contracts/corpus-facts.md)) y se copian sin variar; los
  métodos de pago y los estados de pedido son los del dataset de Olist.

El contrato completo del formato está en
[`corpus-format.md`](../specs/LAB-18-kb-policies-faqs-corpus/contracts/corpus-format.md).

## Añadir o cambiar una política

1. Crea o edita el `.md` en `data/kb/` siguiendo la convención. Si cambias un plazo o un importe, cámbialo antes en
   `corpus-facts.md` y revisa todos los documentos que lo citan.
2. Añade o actualiza su fila en `data/kb/INDEX.md` y en `data/kb/REVIEW.md` (estado `pending`).
3. Si el documento tiene PDF, regéneralo (ver abajo).
4. Ejecuta la validación y pide la revisión manual; al revisarlo, pasa su fila de `REVIEW.md` a `reviewed` con revisor y
   fecha (`YYYY-MM-DD`).

El corpus admite entre 20 y 30 documentos. Cualquier cambio en un documento ya revisado devuelve su fila a `pending`.

## Herramientas

Desde `data/kb-tools/` (requiere [`uv`](https://docs.astral.sh/uv/)):

```bash
uv sync
uv run kb-tools build-pdf             # genera los PDF desde el Markdown (reproducible)
uv run kb-tools build-pdf --check     # falla si algún PDF está desactualizado
uv run kb-tools validate              # valida el corpus
uv run kb-tools validate --require-reviewed   # además exige la revisión manual de todos los documentos
```

El CI ejecuta `ruff` y `pytest` en `data/kb-tools`, y una de las pruebas valida el corpus real con
`--require-reviewed`: un PR que toque el corpus no pasa hasta que todos los documentos estén revisados. Las reglas
(`KB001` a `KB011`) están en [`data/kb-tools/README.md`](../data/kb-tools/README.md).

## Qué no incluye

Ingesta, troceado, embeddings y búsqueda sobre el corpus; respuestas del asistente con citas; varios idiomas; y una
interfaz de administración de políticas.
