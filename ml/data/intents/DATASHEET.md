# Ficha del dataset de intenciones del cliente

> **Estado: etiquetado y revisado por el asistente (un modelo), SIN revisión humana.** Todas las etiquetas, las revisiones de
> reseñas y de paráfrasis y la revisión de nombres de persona las hizo el asistente de IA que implementó LAB-117, por encargo del
> autor, el 2026-11-21. **El segundo anotador de la muestra de acuerdo es el modelo local, no una persona.** Una persona debe
> revisar una muestra y firmar [`REVIEW.md`](REVIEW.md) antes de tratar este dataset como validado. Las cifras de
> «Composición» **no se escriben a mano**: las genera `uv run python -m intent_dataset stats --write` y `validate` falla si no
> coinciden con `intents.jsonl`.

Mensajes de clientes de una tienda en línea, en español y portugués, etiquetados con **una** de cinco intenciones, para
entrenar y evaluar un clasificador propio (LAB-118, LAB-126) y, más adelante, auditarlo (LAB-125).

## Origen

Cada ejemplo indica su origen en `source`:

| `source` | De dónde viene | Idioma típico |
|---|---|---|
| `golden` | Preguntas de `eval/golden.jsonl` (LAB-27, 120 casos: 88 de conocimiento y 32 de conversación, fuera de alcance e inyección). La etiqueta propuesta sale de la categoría del caso y el asistente la confirmó o corrigió caso a caso (quedaron 79 `politica`, 32 `charla` y 9 `pedido`) | es |
| `olist_review` | Comentarios de reseñas del dataset público de Olist, leídos de la tabla `orders.order_reviews` (solo lectura), enmascarados al leerlos; el asistente los leyó todos (240 candidatos: 169 aceptados y 71 descartados) | pt |
| `manual_seed` | Mensajes redactados por el asistente que imitan lo que un cliente escribiría a un chat; cubren las clases que el golden apenas tiene (`pedido`, `producto`, `escalado`) | es, pt |
| `paraphrase` | Variantes y traducciones de los anteriores generadas con el modelo local y aceptadas, editadas o rechazadas una a una por el asistente | es, pt |

Cada ejemplo lleva un `group_id` (el mensaje de origen y todas sus paráfrasis) y, si desciende de un caso del golden, su
`golden_id`. La procedencia exacta de cada parte, con cifras, está en «Composición».

## Composición

<!-- stats:begin -->
**Total: 685 ejemplos**

| Clase | es | pt | total |
|---|---|---|---|
| politica | 85 | 50 | 135 |
| producto | 56 | 87 | 143 |
| pedido | 60 | 136 | 196 |
| escalado | 64 | 67 | 131 |
| charla | 40 | 40 | 80 |

| Partición | politica | producto | pedido | escalado | charla | total |
|---|---|---|---|---|---|---|
| train | 95 | 101 | 138 | 91 | 56 | 481 |
| val | 20 | 21 | 29 | 20 | 12 | 102 |
| test | 20 | 21 | 29 | 20 | 12 | 102 |

| Origen | ejemplos |
|---|---|
| golden | 120 |
| olist_review | 169 |
| manual_seed | 114 |
| paraphrase | 282 |

Casos del golden por partición: train 84, val 19, test 17.

Acuerdo entre anotadores: κ de Cohen = 0.85 sobre 50 casos (6 desacuerdos antes de resolver). Anotador B: **modelo local** (judge); **no es un acuerdo entre personas**.
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

- **Modelo**: `judge` del gateway de modelos (LAB-84), que corresponde a `qwen2.5:7b` en Ollama, en CPU, con la clave virtual
  `eval`. Generado el 2026-11-21: 354 variantes (5 tandas por clase), 3 descartadas por el filtro (JSON inválido o longitud).
- **Instrucciones**: versión `v1` del prompt de `paraphrase.py` (`PARAPHRASE_PROMPT_VERSION`): conservar la intención, cambiar
  palabras y estructura, registro de cliente real y **sin nombres, correos, teléfonos ni números de pedido**.
- **Filtros automáticos** previos a la revisión: JSON inválido, longitud fuera de 3–280, igual al original o repetida, similitud
  de tokens ≥ 0,85 con el original en el mismo idioma, y cualquier variante que el enmascarado cambie (dato personal inventado).
- **Revisión**: la hizo el asistente, **no una persona**: aceptar, editar o rechazar cada variante; las que cambian de clase o de
  idioma, mezclan idiomas o inventan datos se rechazan. De 354 variantes se rechazaron **71 (20 %)** y se editaron **60** de las
  283 aceptadas (282 entraron: una repetía un texto existente). **La calidad en portugués fue claramente peor que en
  español**: 26 % de rechazo (55 de 215) frente a 12 % (16 de 139). Solo entran las aceptadas (`reviewed: true`).
- **Reproducibilidad**: el dataset publicado es la fuente de verdad; no se promete regenerar las mismas paráfrasis.

## Acuerdo entre anotadores

- **Muestra**: 50 ejemplos estratificados (10 por clase, ambos idiomas), congelados con la etiqueta del primer anotador y
  etiquetados por el segundo **a ciegas**, con solo la guía.
- **Kappa de Cohen** y desacuerdos: en el bloque de «Composición» y en `annotations/agreement.json` (calculado **antes** de
  resolver). Referencia: κ ≥ 0,70; si no se alcanza, se corrige la guía y se conserva el primer valor.
- **Anotadores**: el primero es **el asistente** (autor de las etiquetas) y el segundo es **el modelo local `judge`
  (`qwen2.5:7b`)**, que etiqueta a ciegas con la guía como instrucción y recibe el mensaje delimitado como dato. **No es un
  acuerdo entre personas**: mide cuánto coinciden dos modelos al aplicar la guía, no la fiabilidad humana. La muestra prefiere
  texto real (16 reseñas, 15 casos del golden) sobre las semillas (19) y excluye las paráfrasis.
- **Resultado**: κ = 0,85 (acuerdo observado 0,88) y 6 desacuerdos, resueltos uno a uno en
  `annotations/agreement-sample.jsonl` con su motivo; solo cambió una etiqueta (`I0007`, y su paráfrasis). Superó la referencia
  (0,70), así que no hubo que reetiquetar. **Un fallo del anotador B** obligó a corregir el prompt: ejecutó una petición de
  traducción en lugar de clasificarla, y la solución fue entregarle el mensaje como dato, no como instrucción.

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
Revisión de nombres de persona: el asistente leyó los 169 comentarios aceptados y sustituyó los que encontró (2 casos: un nombre
de pila y una razón social). **Lo hizo un modelo, no una persona**: un nombre que se le escapara no lo detectaría ningún
patrón, así que esta comprobación necesita una lectura humana.

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
- **Tamaño**: 685 ejemplos; val y test tienen 102 cada una, y la clase más pequeña (`charla`) solo 12 en cada una. Las métricas
  por clase e idioma tienen mucha varianza: una diferencia de unos puntos de F1 puede ser ruido.
- **Las etiquetas las puso un modelo**: el asistente aplicó su propia guía, que fue redactando mientras etiquetaba (v2). Sus
  criterios pueden no coincidir con los de una persona, sobre todo en las fronteras `politica`/`pedido` y `producto`/`pedido`.
- **Solape con el golden**: ver «Particiones».
- **`charla` es más amplia que la cortesía** (incluye lo ajeno a la tienda y la inyección): un modelo entrenado así no distingue
  entre un saludo y una pregunta ajena, y LAB-121 debe tenerlo en cuenta al enrutar.
- **Una sola etiquetadora fuera de la muestra de 50**: el resto de los 685 ejemplos no tiene segunda opinión, ni humana ni de
  modelo; y el segundo anotador de la muestra es un modelo.
- **Sin revisión humana** (`REVIEW.md`): el validador comprueba formato, mínimos, fugas, datos personales por patrón y kappa;
  **no** puede comprobar que una etiqueta sea la correcta ni que no se haya colado un nombre.
- **Paráfrasis en portugués de peor calidad**: tras la revisión quedan algunas con registro poco natural; el 26 % se rechazó.

## Uso previsto y no previsto

- **Previsto**: entrenar y evaluar clasificadores de intención clásicos y de aprendizaje profundo (LAB-118, LAB-126), medir su
  comportamiento por idioma (LAB-125) y servir de base para ampliar el dataset con casos revisados de producción (LAB-124).
- **No previsto**: uso comercial (licencia de Olist); entrenar o evaluar con él algo distinto de la intención del mensaje;
  inferir nada sobre personas reales; presentarlo como representativo de los clientes reales de una tienda.
