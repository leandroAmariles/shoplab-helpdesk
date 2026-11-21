# Revisión manual del dataset de intenciones

> **Estado: BORRADOR, pendiente de la persona revisora.** Ningún modelo puede firmar estas revisiones (como en
> `eval/REVIEW.md`, LAB-27). Lo que figura como «pendiente» lo debe completar una persona leyendo los ejemplos. El asistente solo
> dejó preparado el esqueleto. **Mientras esto siga pendiente, el dataset es un borrador y LAB-117 no se da por terminada.**

- **Revisor**: _pendiente_
- **Fecha**: _pendiente_
- **Dataset revisado**: `ml/data/intents/intents.jsonl` (_pendiente de construir: ver `tasks.md` T029–T035_)
- **Guía usada**: `LABELING-GUIDE.md` v1 (_pendiente de lectura por la persona_)

## 1. Nombres de persona en comentarios de Olist (`pii_checked`)

El enmascarado por patrón no reconoce nombres. La persona lee **cada** comentario de origen `olist_review`, sustituye por
`[NAME]` los que aparezcan y marca `pii_checked = si`. `build` rechaza las filas de Olist sin esa marca.

| Lote | Comentarios leídos | Nombres encontrados | Revisor | Fecha | Estado |
|---|---|---|---|---|---|
| Primera tanda de candidatos | pendiente | pendiente | pendiente | pendiente | pendiente |

## 2. Paráfrasis generadas con el modelo local

Por cada variante: conserva la intención de su padre, es natural en su idioma y no inventa datos personales. Las que cambian de
clase se rechazan. `build` solo admite las aceptadas.

| Lote | Variantes revisadas | Aceptadas | Rechazadas | Revisor | Fecha | Estado |
|---|---|---|---|---|---|---|
| Paráfrasis y traducciones | pendiente | pendiente | pendiente | pendiente | pendiente | pendiente |

Calidad de las paráfrasis en portugués (tasa de descarte y de rechazo): _pendiente_.

## 3. Ningún `charla` contiene una petición real (FR-009)

La persona recorre **todos** los ejemplos `charla` y comprueba que ninguno pide información o una acción sobre la tienda
(`charla` incluye lo ajeno a la tienda y la inyección, pero no una pregunta real con un saludo delante).

| Alcance | Ejemplos `charla` revisados | Reetiquetados | Revisor | Fecha | Estado |
|---|---|---|---|---|---|
| Todo el dataset | pendiente | pendiente | pendiente | pendiente | pendiente |

## 4. Etiquetas del golden

Se confirma o corrige caso a caso la etiqueta propuesta por categoría (`politica` / `charla`): una pregunta sobre un pedido
o producto concretos, o que pide una persona, no es `politica`.

| Casos | Confirmados | Corregidos | Revisor | Fecha | Estado |
|---|---|---|---|---|---|
| `G001`–`G120` | pendiente | pendiente | pendiente | pendiente | pendiente |

## 5. Segundo anotador y acuerdo

- **Anotador B**: _pendiente_ (`person` o `local_model`; si es un modelo, **no** es un acuerdo entre personas y la ficha lo
  declara).
- **Kappa de Cohen inicial** (antes de resolver) y número de casos: _pendiente_.
- **Desacuerdos resueltos** (con `final` y `reason` en `annotations/agreement-sample.jsonl`): _pendiente_.
- **Guía releída entera tras resolver** y reglas añadidas: _pendiente_.

## 6. Cierre

- [ ] Todos los apartados anteriores completados por una persona
- [ ] `python -m intent_dataset validate` devuelve 0 con guía, ficha y golden
- [ ] La ficha (`DATASHEET.md`) declara quién fue el anotador B

**Firma**: _pendiente_ · **Fecha**: _pendiente_
