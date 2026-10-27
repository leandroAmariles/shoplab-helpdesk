# Contrato: valores canónicos del corpus

Única fuente de plazos, importes y condiciones. Los documentos los copian sin variar (FR-007). Tienda ficticia ShopLab;
moneda reales brasileños (`R$`). «Días hábiles» = lunes a viernes, sin festivos. Cualquier cambio aquí obliga a revisar
los documentos de la columna «Documentos».

## Envíos

| Hecho | Valor | Documentos |
|---|---|---|
| Despacho tras aprobación del pago | hasta 2 días hábiles | shipping-01, 05 |
| Envío estándar | 5 a 10 días hábiles desde el despacho; R$ 19,90 | shipping-01, 02, 05 |
| Envío exprés | 2 a 4 días hábiles desde el despacho; R$ 39,90 | shipping-01, 02, 05 |
| Envío gratis | envío estándar gratis en compras desde R$ 200,00 | shipping-02, 05 |
| Código de seguimiento | se envía por correo dentro de 24 horas tras el despacho | shipping-03, 05 |
| Pedido retrasado | si pasa de la fecha estimada, se abre una investigación tras 10 días hábiles de retraso | shipping-04 |
| Producto dañado o incorrecto | avisar dentro de 7 días corridos desde la entrega | shipping-04, 05 |
| Pedido extraviado | reenvío o reembolso total; resolución en 10 días hábiles desde la apertura del caso | shipping-04 |

## Devoluciones, cancelaciones y reembolsos

| Hecho | Valor | Documentos |
|---|---|---|
| Derecho de arrepentimiento | 7 días corridos desde la entrega; devolución gratis y reembolso total, flete incluido | returns-01, 02, 03, 05 |
| Devolución por cambio de opinión | hasta 30 días corridos desde la entrega; del día 8 al 30 el flete de devolución (R$ 19,90) lo paga el cliente | returns-01, 02, 05 |
| Producto defectuoso o distinto | devolución gratis dentro de 30 días corridos | returns-01, 02 |
| No devolvibles | productos personalizados, de higiene personal abiertos y contenido digital descargado | returns-01, 05 |
| Estado del producto | sin uso indebido, con embalaje y accesorios originales | returns-01, 02 |
| Inspección | hasta 3 días hábiles desde la recepción | returns-02, 03 |
| Reembolso | hasta 10 días hábiles tras aprobar la inspección, al medio de pago original; boleto: transferencia bancaria | returns-03, 05 |
| Importe pagado con vale | se reembolsa como un nuevo vale, no en dinero | returns-03, 04, payments-03 |
| Cambio de producto | dentro de 30 días corridos, sujeto a stock; si no hay stock, reembolso | returns-04 |
| Cancelación sin costo | antes del despacho (estados `created`, `approved`, `invoiced`, `processing`) | returns-04, 05 |
| Cancelación con pedido `shipped` | no se cancela: se rechaza la entrega o se devuelve al recibirlo | returns-04 |
| Pedido `unavailable` | sin stock tras la compra: cancelación y reembolso automáticos | returns-04 |

## Garantías

| Hecho | Valor | Documentos |
|---|---|---|
| Garantía legal | 90 días corridos desde la entrega | warranty-01, 03, 04 |
| Garantía extendida | opcional, +12 meses tras la legal; costo 10 % del precio del producto; solo productos desde R$ 300,00 | warranty-02, 04 |
| Contratación de la extendida | solo al comprar, hasta el pago del pedido | warranty-02 |
| Reclamación | por el formulario de contacto, con número de pedido y descripción | warranty-03, 04 |
| Resolución del defecto | reparación en hasta 30 días corridos; si no se logra, cambio o reembolso | warranty-01, 03, 04 |
| Diagnóstico | gratuito | warranty-03 |
| Exclusiones | daños por mal uso, caídas, líquidos, reparaciones de terceros y desgaste normal | warranty-01, 02 |

## Pagos

| Hecho | Valor | Documentos |
|---|---|---|
| Métodos aceptados | tarjeta de crédito (`credit_card`), tarjeta de débito (`debit_card`), boleto (`boleto`) y vale (`voucher`) | payments-01, 05 |
| Valor `not_defined` | existe en los datos (3 pagos de 103.886); no es un método ofrecido y no se menciona en los documentos | (ninguno) |
| Crédito en cuotas | hasta 12 cuotas; sin interés hasta 3 cuotas; cuota mínima R$ 30,00 | payments-01, 02, 05 |
| Débito | pago único | payments-01, 05 |
| Boleto | vence a los 3 días corridos; se aprueba hasta 2 días hábiles tras el pago; sin pago al vencer, el pedido pasa a `canceled` | payments-03, 05 |
| Vale | vigencia 12 meses desde la emisión; combinable con otro método; no canjeable por dinero | payments-03, 05 |
| Aprobación de tarjeta | inmediata o hasta 30 minutos | payments-01, 04 |
| Pago rechazado | hasta 3 intentos en 24 horas con el mismo pedido | payments-04, 05 |
| Cobro duplicado | reembolso en 10 días hábiles tras confirmarlo | payments-04 |
| Datos de tarjeta | ShopLab no almacena el número completo ni el código de seguridad | payments-01, 04 |

## Estados de pedido (los del dataset)

| Estado | Significado en el corpus |
|---|---|
| `created` | pedido creado, pago pendiente |
| `approved` | pago aprobado |
| `invoiced` | factura emitida |
| `processing` | preparación en almacén |
| `shipped` | entregado al transportista |
| `delivered` | entregado al cliente |
| `unavailable` | sin stock tras la compra |
| `canceled` | cancelado |

## Privacidad

| Hecho | Valor | Documentos |
|---|---|---|
| Datos que se recogen | identificación y contacto, dirección de entrega, historial de pedidos, datos de pago tokenizados y valoraciones | privacy-01, 02, 05 |
| Finalidades | gestionar pedidos y envíos, pagos, soporte y mejora del servicio | privacy-01, 02 |
| Venta de datos | nunca se venden a terceros | privacy-01, 05 |
| Conservación | datos fiscales 5 años; datos de la cuenta hasta que se pida su eliminación | privacy-01, 02, 03 |
| Derechos | acceso, corrección, portabilidad, oposición y eliminación | privacy-03, 05 |
| Plazo de respuesta a una solicitud | 15 días corridos | privacy-03, 05 |
| Canal de privacidad | privacidad@shoplab.example | privacy-01, 03, 05 |
| Cookies | esenciales (siempre activas), de analítica y de marketing (requieren consentimiento) | privacy-04, 05 |
| Asistente | las conversaciones con el asistente se usan para responder y mejorar el servicio; los datos de ejemplo del sistema son anonimizados | privacy-02, 05 |

## Contacto general

| Hecho | Valor |
|---|---|
| Correo de soporte | soporte@shoplab.example |
| Correo de privacidad | privacidad@shoplab.example |
| Horario de atención | lunes a viernes, 9:00 a 18:00 (hora de Brasilia) |
| Respuesta de soporte | dentro de 2 días hábiles |
| Teléfonos | no se publican (el validador rechaza números de teléfono) |
