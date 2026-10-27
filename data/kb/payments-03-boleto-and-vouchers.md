---
title: Boleto y vales
topic: payments
type: policy
language: es
scope: Cómo funcionan el pago con boleto bancario y los vales de ShopLab, con sus plazos de vencimiento y vigencia.
---
# Boleto y vales

## Alcance
Este documento explica el pago con boleto bancario (`boleto`) y el uso de vales (`voucher`) como medio de pago.

## Cómo funciona
**Boleto.** Al elegir boleto, ShopLab genera un documento de pago que puedes pagar en un banco o por la aplicación de tu
banco. El pedido queda en `created` hasta que el pago se apruebe; entonces pasa a `approved`.

**Vale.** Es un crédito en la tienda. Puedes usarlo solo o combinado con otro método de pago, y se descuenta del total
del pedido.

## Plazos y costos
| Concepto | Valor |
|---|---|
| Vencimiento del boleto | 3 días corridos |
| Aprobación del pago del boleto | hasta 2 días hábiles tras el pago |
| Si el boleto no se paga al vencer | el pedido pasa a `canceled` |
| Vigencia del vale | 12 meses desde la emisión |
| Vale canjeable por dinero | no |

## Casos especiales
- El plazo de despacho del pedido empieza cuando el pago del boleto se aprueba.
- El importe pagado con vale se reembolsa como un nuevo vale, no en dinero.
- Un vale vencido no se puede usar.
- Si el boleto vence sin pago, puedes volver a hacer el pedido y elegir otro método.

## Contacto
Escribe a soporte@shoplab.example o usa el formulario de contacto del sitio. Atención de lunes a viernes, de 9:00 a 18:00
(hora de Brasilia); respondemos dentro de 2 días hábiles.
