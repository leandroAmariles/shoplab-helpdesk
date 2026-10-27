---
title: Cambios y cancelaciones
topic: returns
type: policy
language: es
scope: Cómo cambiar un producto y cómo cancelar un pedido según su estado.
---
# Cambios y cancelaciones

## Alcance
Este documento cubre el cambio de un producto por otro y la cancelación de pedidos, según el estado en que estén.

## Cómo funciona
**Cambios.** Puedes pedir el cambio de un producto dentro de 30 días corridos desde la entrega, siguiendo el proceso de
"Cómo devolver un producto". El cambio está sujeto a stock; si el producto que quieres no está disponible, se hace el
reembolso.

**Cancelaciones.** Lo que puedes hacer depende del estado del pedido:

| Estado del pedido | Qué puedes hacer |
|---|---|
| `created`, `approved`, `invoiced`, `processing` | cancelar sin costo |
| `shipped` | no se cancela: rechaza la entrega o devuelve el pedido al recibirlo |
| `delivered` | seguir la "Política de devoluciones" |
| `unavailable` | cancelación y reembolso automáticos (no había stock tras la compra) |
| `canceled` | el pedido ya está cancelado |

## Plazos y costos
- Cambio: dentro de 30 días corridos desde la entrega, sujeto a stock.
- Cancelación antes del despacho: sin costo.
- Reembolso de una cancelación: hasta 10 días hábiles (ver "Reembolsos").

## Casos especiales
- Un pedido pagado con boleto que no se paga al vencer pasa a `canceled` (ver "Boleto y vales").
- Si cancelas un pedido que usó un vale, el importe vuelve como vale.

## Contacto
Escribe a soporte@shoplab.example o usa el formulario de contacto del sitio. Atención de lunes a viernes, de 9:00 a 18:00
(hora de Brasilia); respondemos dentro de 2 días hábiles.
