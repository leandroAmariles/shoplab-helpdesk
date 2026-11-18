# Revisión manual del dataset golden

> **Estado: BORRADOR, pendiente de la persona revisora.** Lo que está marcado como «pendiente» lo debe completar una
> persona leyendo los documentos; ningún modelo puede firmar estas revisiones (plan R7, SC-005). El borrador lo dejó
> preparado el asistente al implementar LAB-27: las listas de casos, la semilla de la muestra y las búsquedas ya hechas.

- **Revisor**: _pendiente_
- **Fecha**: _pendiente_
- **Dataset revisado**: `eval/golden.jsonl`, 90 casos (`G001`–`G090`)
- **Corpus**: `data/kb`, 24 documentos

## 1. Revisión caso a caso por tema

La persona revisora lee **cada** caso del tema contra sus documentos esperados y confirma que la respuesta de referencia es
cierta y completa, que la pregunta es autocontenida y está en español, y que no hay datos inventados.

| Tema | Casos | Revisor | Fecha | Estado |
|---|---|---|---|---|
| `payments` (con respuesta) | `G001`–`G014` | pendiente | pendiente | pendiente |
| `privacy` (con respuesta) | `G015`–`G028` | pendiente | pendiente | pendiente |
| `returns` (con respuesta) | `G029`–`G042` | pendiente | pendiente | pendiente |
| `shipping` (con respuesta) | `G043`–`G056` | pendiente | pendiente | pendiente |
| `warranty` (con respuesta) | `G057`–`G070` | pendiente | pendiente | pendiente |
| Sin respuesta del tema | `G071`–`G082` | pendiente | pendiente | pendiente |
| `off_topic` | `G083`–`G090` | pendiente | pendiente | pendiente |

## 2. Muestra al azar de 15 casos con respuesta (SC-005)

Semilla fija y reproducible: `random.Random(27).sample(range(1, 71), 15)`, ordenada, sobre los ids `G001`–`G070`.

| Caso | Documentos esperados (leídos) | ¿Correcta y completa? | Observación |
|---|---|---|---|
| `G009` | `payments-02-installments` | pendiente | |
| `G010` | `payments-02-installments` | pendiente | |
| `G017` | `privacy-01-privacy-policy` | pendiente | |
| `G022` | `privacy-03-data-rights` | pendiente | |
| `G024` | `privacy-02-data-collection-and-use` | pendiente | |
| `G026` | `privacy-01-privacy-policy`, `privacy-04-cookies` | pendiente | |
| `G033` | `returns-02-return-process` | pendiente | |
| `G035` | `returns-02-return-process` | pendiente | |
| `G036` | `returns-03-refunds` | pendiente | |
| `G037` | `returns-03-refunds` | pendiente | |
| `G053` | `shipping-04-delivery-issues` | pendiente | |
| `G054` | `shipping-04-delivery-issues`, `shipping-05-faq-shipping` | pendiente | |
| `G062` | `warranty-02-extended-warranty` | pendiente | |
| `G063` | `warranty-03-warranty-claims` | pendiente | |
| `G067` | `warranty-01-legal-warranty`, `returns-01-return-policy` | pendiente | |

**Resultado esperado**: 15 de 15 correctos. Si falla uno, se revisan todos los casos de su tema y se anota aquí.

**Resultado**: _pendiente_

## 3. Casos sin respuesta del tema: búsquedas en el corpus

Para cada caso se buscó en `data/kb/*.md` (sin distinguir mayúsculas, y con y sin tilde) y no apareció ningún documento que
permita contestar la pregunta, ni siquiera en parte. La búsqueda la hizo el asistente al redactar los casos; la persona
revisora debe repetirla (por ejemplo con `grep -il -E "<término>" data/kb/*.md`) y confirmarla.

| Caso | Dato que se pide | Términos buscados | Resultado | Confirmado por la persona |
|---|---|---|---|---|
| `G071` | porcentaje de interés de las cuotas con recargo | `tasa`, `porcentaje` | sin coincidencias; el corpus solo dice «con interés» sin cifra | pendiente |
| `G072` | pagar con la tarjeta de otra persona | `titular`, `nombre de`, `otra persona` | sin coincidencias | pendiente |
| `G073` | repartir un pedido entre dos tarjetas | `dos tarjetas`, `otra tarjeta`, `varias tarjetas` | sin coincidencias | pendiente |
| `G074` | servidores en otro país | `servidor`, `país`, `fuera de`, `nube` | sin coincidencias | pendiente |
| `G075` | delegado de protección de datos | `delegado`, `DPO` | sin coincidencias | pendiente |
| `G076` | devolver en una tienda física | `tienda física`, `sucursal`, `punto de` | sin coincidencias | pendiente |
| `G077` | cupón de descuento en una devolución | `cupón`, `descuento` | sin coincidencias | pendiente |
| `G078` | recogida a domicilio del paquete a devolver | `recogida`, `recoger`, `recolección`, `domicilio` | sin coincidencias | pendiente |
| `G079` | envíos a otros países | `internacional`, `extranjero`, `otro país` | sin coincidencias | pendiente |
| `G080` | elegir franja horaria de entrega | `franja`, `horario`, `reprogram` | sin coincidencias | pendiente |
| `G081` | producto de reemplazo durante la reparación | `reemplazo`, `préstamo`, `prestan`, `sustituto` | sin coincidencias | pendiente |
| `G082` | pasar la garantía a otra persona | `transferir`, `regalo`, `cesión` | sin coincidencias (`transfer` solo aparece en reembolsos por transferencia bancaria) | pendiente |

## 4. Respuestas de los 20 casos sin respuesta

Confirmar que ninguna escribe plazos, importes o cantidades **en palabras** («siete días», «una semana»). La validación
solo detecta dígitos; la prueba `test_unanswered_reference_answers_do_not_state_periods_or_amounts_in_words` busca además
días, semanas, meses, horas, años y reales, pero no sustituye la lectura.

**Resultado**: _pendiente_

## 5. Indicador léxico (FR-013)

Valor de `uv run python -m shoplab_eval validate` al implementar: **78,6 %** de las preguntas con respuesta tienen poco
solapamiento léxico con sus documentos (objetivo: al menos el 30 %). La persona revisora lo confirma tras cualquier cambio de
preguntas: **pendiente**.
