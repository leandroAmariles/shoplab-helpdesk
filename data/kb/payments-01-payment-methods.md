---
title: Métodos de pago
topic: payments
type: policy
language: es
scope: Métodos de pago aceptados en ShopLab, cuándo se aprueba cada uno y cómo se protegen los datos de tarjeta.
---
# Métodos de pago

## Alcance
Este documento lista los métodos de pago que acepta ShopLab y cómo funciona cada uno. Todos los importes están en reales
brasileños (R$).

## Cómo funciona
ShopLab acepta cuatro métodos de pago. Entre paréntesis figura el nombre con el que aparece en los datos del pedido.

| Método | Nombre en el pedido | Cómo se paga |
|---|---|---|
| Tarjeta de crédito | `credit_card` | en un pago o en cuotas (ver "Pago en cuotas") |
| Tarjeta de débito | `debit_card` | pago único |
| Boleto bancario | `boleto` | se paga en un banco o en línea antes del vencimiento (ver "Boleto y vales") |
| Vale | `voucher` | crédito en la tienda, combinable con otro método (ver "Boleto y vales") |

## Plazos y costos
| Concepto | Valor |
|---|---|
| Aprobación de tarjeta | inmediata o hasta 30 minutos |
| Aprobación de boleto | hasta 2 días hábiles tras el pago |
| Pago con débito | pago único, sin cuotas |

Mientras el pago no se apruebe, el pedido permanece en `created`; al aprobarse pasa a `approved`.

## Casos especiales
- ShopLab no almacena el número completo de la tarjeta ni el código de seguridad.
- Un pedido puede pagarse combinando un vale con otro método.
- Si el pago falla, consulta "Problemas con el pago".

## Contacto
Escribe a soporte@shoplab.example o usa el formulario de contacto del sitio. Atención de lunes a viernes, de 9:00 a 18:00
(hora de Brasilia); respondemos dentro de 2 días hábiles.
