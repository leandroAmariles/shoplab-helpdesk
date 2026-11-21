# Ficha del dataset de intenciones del cliente

> **Estado: BORRADOR.** El esqueleto y los apartados que no dependen de los datos los dejó preparados el asistente al
> implementar LAB-117. Lo marcado como **pendiente** se completa cuando el dataset exista y una persona lo revise
> (`REVIEW.md`). Las cifras del apartado «Composición» **no se escriben a mano**: las genera
> `uv run python -m intent_dataset stats --write` y `validate` falla si no coinciden con `intents.jsonl`.

Mensajes de clientes de una tienda en línea, en español y portugués, etiquetados con **una** de cinco intenciones, para
entrenar y evaluar un clasificador propio (LAB-118, LAB-126) y, más adelante, auditarlo (LAB-125).

## Origen

Cada ejemplo indica su origen en `source`:

| `source` | De dónde viene | Idioma típico |
|---|---|---|
| `golden` | Preguntas de `eval/golden.jsonl` (LAB-27, 120 casos: 88 de conocimiento y 32 de conversación, fuera de alcance e inyección). La etiqueta propuesta sale de la categoría del caso y una persona la confirma | es |
| `olist_review` | Comentarios de reseñas del dataset público de Olist, leídos de la tabla `orders.order_reviews` (solo lectura), enmascarados al leerlos y revisados a mano | pt |
| `manual_seed` | Mensajes redactados a mano que imitan lo que un cliente escribiría a un chat; cubren las clases que el golden apenas tiene (`pedido`, `producto`, `escalado`) | es, pt |
| `paraphrase` | Variantes y traducciones de los anteriores generadas con el modelo local y aceptadas tras revisión manual | es, pt |

Cada ejemplo lleva un `group_id` (el mensaje de origen y todas sus paráfrasis) y, si desciende de un caso del golden, su
`golden_id`. La procedencia exacta de cada parte, con cifras, está en «Composición».

## Composición

<!-- stats:begin -->
pendiente: este bloque lo genera `stats --write` cuando exista `intents.jsonl`.
<!-- stats:end -->

Las **cinco intenciones** son fijas y se definen en [`LABELING-GUIDE.md`](LABELING-GUIDE.md): `politica`, `producto`, `pedido`,
`escalado` y `charla`. **`charla` es más amplia que la cortesía**: incluye también lo ajeno a la tienda y los intentos de
inyección de instrucciones, porque el chat los resuelve igual, sin recuperar documentos (decisión de LAB-117; ver
«Limitaciones»).

## Licencias

- **Comentarios de Olist** (`olist_review` y todo lo derivado de ellos: paráfrasis y traducciones): proceden del *Brazilian
  E-Commerce Public Dataset by Olist* (Kaggle), publicado bajo **CC BY-NC-SA 4.0**: se debe **atribuir** a Olist, el uso es
  **no comercial** y las obras derivadas se comparten **con la misma licencia**. Aquí solo se versionan los comentarios
  seleccionados, ya enmascarados y revisados, nunca la tabla ni los CSV originales. El uso de este laboratorio es didáctico.
- **Preguntas del golden y semillas redactadas a mano**: material propio del laboratorio, sin datos de clientes reales.
- **Paráfrasis generadas con el modelo local**: texto sintético; heredan las condiciones de su origen.
- Si algún día se quisiera un uso comercial, habría que sustituir los ejemplos `olist_review` y sus derivados; por eso `source`
  los identifica.

## Generación y revisión de paráfrasis

- **Modelo**: el de los pasos de evaluación del gateway de modelos (LAB-84), por defecto `judge` (`qwen2.5:7b`); el valor real
  usado queda registrado aquí cuando se genere. _Pendiente._
- **Instrucciones**: versión `v1` del prompt de `paraphrase.py` (`PARAPHRASE_PROMPT_VERSION`): conservar la intención, cambiar
  palabras y estructura, registro de cliente real y **sin nombres, correos, teléfonos ni números de pedido**.
- **Filtros automáticos** previos a la revisión: JSON inválido, longitud fuera de 3–280, igual al original o repetida, similitud
  de tokens ≥ 0,85 con el original en el mismo idioma, y cualquier variante que el enmascarado cambie (dato personal inventado).
- **Revisión manual**: una persona acepta, edita o rechaza cada variante; las que cambian de clase se rechazan. Solo entran las
  aceptadas (`reviewed: true`). Quién revisó, cuándo, cuántas y con qué tasa de rechazo: **pendiente** (`REVIEW.md`).
- **Reproducibilidad**: el dataset publicado es la fuente de verdad; no se promete regenerar las mismas paráfrasis.

## Acuerdo entre anotadores

- **Muestra**: 50 ejemplos estratificados (10 por clase, ambos idiomas), congelados con la etiqueta del primer anotador y
  etiquetados por el segundo **a ciegas**, con solo la guía.
- **Kappa de Cohen** y desacuerdos: en el bloque de «Composición» y en `annotations/agreement.json` (calculado **antes** de
  resolver). Referencia: κ ≥ 0,70; si no se alcanza, se corrige la guía y se conserva el primer valor.
- **Quién fue el anotador B**: **pendiente**. Si es el modelo local, el bloque lo dice y **no es un acuerdo entre personas**.

## Enmascarado

Todo texto pasa por `intent_dataset.masking.mask_text` antes de escribirse en disco, y `validate` falla si encuentra alguna
coincidencia (cero tolerancia):

| Dato | Marca |
|---|---|
| Enlaces | `[URL]` |
| Correos | `[EMAIL]` |
| Identificadores de 32 hexadecimales (pedido, cliente, reseña) | `[ID]` |
| CNPJ y CPF con formato | `[CNPJ]`, `[CPF]` |
| Código postal con guion (CEP) | `[CEP]` |
| Teléfonos y cualquier racha de 8 o más dígitos | `[PHONE]` |
| **Nombres de persona** | `[NAME]`, **a mano**: ningún patrón fiable los reconoce |

Sobreenmascara a propósito. **No existía un enmascarado «de las trazas»** (LAB-64 lo dejó fuera); este es el mínimo que define
LAB-117 y es reutilizable por otras historias (LAB-124), pero esta historia no lo conecta con las trazas ni con el backend.
Revisión manual de nombres: **pendiente** (`REVIEW.md`).

## Particiones

- **Método**: train/val/test ≈ 70/15/15, estratificado por (clase, idioma), por **grupos** (un mensaje y sus paráfrasis, y los
  textos idénticos una vez normalizados, van siempre juntos), con semilla fija **117**. La partición se **guarda en cada
  ejemplo** (`split`): añadir ejemplos no mueve los existentes.
- **Solape con el golden**: los ejemplos que descienden de un caso del golden llevan `golden_id`. Si LAB-121 compara el
  clasificador **sobre el golden**, debe excluir los casos que están en `train`/`val` o medir solo con los de `test`; el bloque
  de «Composición» publica cuántos casos del golden hay en cada partición.

## Limitaciones

- **Dominio de los comentarios de Olist**: son reseñas posteriores a la compra, escritas en portugués, no mensajes a un chat de
  atención; su estilo y sus temas difieren de los de un cliente que escribe a un asistente.
- **Peso de las paráfrasis sintéticas**: parte del dataset es texto generado por un modelo pequeño; puede ser más limpio y
  uniforme que el real e inflar las métricas. La proporción por origen está en «Composición».
- **Tamaño**: del orden de cientos de ejemplos; val y test son pequeños, y las métricas por clase e idioma tienen mucha
  varianza. Celdas pequeñas: **pendiente** de revisar con las cifras reales.
- **Solape con el golden**: ver «Particiones».
- **`charla` es más amplia que la cortesía** (incluye lo ajeno a la tienda y la inyección): un modelo entrenado así no distingue
  entre un saludo y una pregunta ajena, y LAB-121 debe tenerlo en cuenta al enrutar.
- **Etiquetado de una sola persona** salvo la muestra de 50: el resto no tiene segunda opinión.
- **Borrador hasta la revisión humana** (`REVIEW.md`): el validador comprueba formato, mínimos, fugas, datos personales por
  patrón y kappa; **no** puede comprobar que una etiqueta sea la correcta ni que no se haya colado un nombre.

## Uso previsto y no previsto

- **Previsto**: entrenar y evaluar clasificadores de intención clásicos y de aprendizaje profundo (LAB-118, LAB-126), medir su
  comportamiento por idioma (LAB-125) y servir de base para ampliar el dataset con casos revisados de producción (LAB-124).
- **No previsto**: uso comercial (licencia de Olist); entrenar o evaluar con él algo distinto de la intención del mensaje;
  inferir nada sobre personas reales; presentarlo como representativo de los clientes reales de una tienda.
