# Revisión del dataset de intenciones

> **Estado: revisado por el asistente (un modelo de IA); PENDIENTE de revisión y firma de una persona.** Lo que sigue registra
> **lo que el asistente hizo el 2026-11-21 por encargo del autor** («termina la US»), con sus límites. En el golden de LAB-27 se
> acordó que ningún modelo firma estas revisiones (`eval/REVIEW.md`); aquí se mantiene: **el asistente no firma**. El campo
> `reviewed: true` de cada ejemplo significa «pasó por este proceso de revisión», **no** «lo ha leído una persona».

- **Revisor**: el asistente (Claude, modelo de IA). **Persona revisora**: _pendiente_
- **Fecha de la revisión del asistente**: 2026-11-21
- **Dataset revisado**: `ml/data/intents/intents.jsonl`, 685 ejemplos (`I0001`–`I0685`)
- **Guía usada**: `LABELING-GUIDE.md` v2, **redactada por el propio asistente** mientras etiquetaba

## 1. Qué hizo el asistente

| Apartado | Hecho | Cifras |
|---|---|---|
| Etiquetas del golden | Confirmó o corrigió la etiqueta propuesta de cada caso | 120 casos: 79 `politica`, 32 `charla` y 9 `pedido` (10 lo fueron al revisar; `G007` pasó a `politica` al resolver el acuerdo) |
| Reseñas de Olist | Leyó los 240 candidatos y decidió etiqueta o descarte | 169 aceptados, 71 descartados (elogios sin objeto, dos temas, textos cortados) |
| Nombres de persona | Leyó los 169 aceptados y enmascaró los que vio | 2 sustituidos por `[NAME]` (un nombre de pila y una razón social) |
| Semillas | Redactó los mensajes de las clases débiles, en ambos idiomas | 114 |
| Paráfrasis | Aceptó, editó o rechazó cada variante del modelo local | 354 generadas: 71 rechazadas (20 %), 60 editadas, 282 incorporadas |
| `charla` sin petición real | Comprobó al etiquetar y al revisar que ningún `charla` pide algo de la tienda | sin revisión posterior independiente |
| Segundo anotador | **Lo hizo el modelo local `judge`**, a ciegas, con la guía | κ = 0,85 sobre 50 casos, 6 desacuerdos (ver 2) |

## 2. Muestra de acuerdo (50 casos)

- **Anotador A**: el asistente. **Anotador B**: el modelo local `judge` (`qwen2.5:7b`). **No es un acuerdo entre personas.**
- **Kappa de Cohen inicial** (antes de resolver): **0,85**; acuerdo observado 0,88; 6 desacuerdos.
- **Fallo del anotador B detectado y corregido**: en un caso de inyección el modelo **ejecutó** la petición («traduce…») en lugar
  de clasificarla. Se cambió el prompt para entregarle el mensaje como dato delimitado y se repitió la anotación completa; el kappa
  de arriba es el de la segunda ejecución. La primera no llegó a calcularse.
- **Desacuerdos y resolución** (resueltos por el asistente; ver `annotations/agreement-sample.jsonl`):

| Ejemplo | A | B | Final | Motivo |
|---|---|---|---|---|
| `I0007` | pedido | politica | politica | Una tarjeta rechazada se resuelve con la política de pagos; no hay un caso que gestionar. **Cambió** (y su paráfrasis `I0540`) |
| `I0035` | politica | pedido | politica | Plazo general de revisión de devoluciones |
| `I0049` | politica | pedido | politica | Cuándo se envía el código de rastreo y dónde se consulta: regla general |
| `I0131` | producto | charla | producto | Opinión sobre el artículo y su precio |
| `I0187` | pedido | producto | pedido | Contenido distinto del comprado: gestión de la compra |
| `I0261` | escalado | pedido | escalado | «No he podido contactar con nadie»: caso límite, criterio 3 ampliado |

La guía se actualizó con estos casos (criterios 3 y 4 de «Criterios añadidos al etiquetar»).

## 3. Lo que NO se ha hecho (y debe hacer una persona)

- [ ] **Leer y ajustar la guía** `LABELING-GUIDE.md` v2: la escribió el asistente y puede no coincidir con tu criterio, sobre todo en
  `politica`/`pedido` y `producto`/`pedido`.
- [ ] **Revisar una muestra de las etiquetas**: al menos los 120 casos del golden, 30 reseñas y 30 paráfrasis, y los ejemplos
  `charla` (confirmar que ninguno contiene una petición real).
- [ ] **Leer los comentarios de Olist buscando nombres de persona** que el asistente no viera.
- [ ] **Segundo anotador humano**: repetir la muestra de 50 con una persona y reportar su kappa; el actual es entre modelos.
- [ ] Decidir si se acepta que **`charla` incluya lo ajeno a la tienda y la inyección** (opción recomendada; la pregunta quedó
  sin respuesta explícita).
- [ ] Decidir si se acepta **versionar comentarios de Olist enmascarados** bajo CC BY-NC-SA 4.0 (ver la ficha).

## 4. Cierre

- [x] `python -m intent_dataset validate --golden ../eval/golden.jsonl` devuelve 0 con guía, ficha y acuerdo
- [x] La ficha (`DATASHEET.md`) declara que el etiquetado y el segundo anotador son modelos
- [ ] Una persona completó el apartado 3

**Firma de la persona revisora**: _pendiente_ · **Fecha**: _pendiente_
