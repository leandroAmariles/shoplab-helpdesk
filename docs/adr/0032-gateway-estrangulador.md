# ADR-0032: Gateway estrangulador delante del monolito

- Estado: aceptado
- Fecha: 2026-11-25

## Contexto

ADR-0031 adelanta la extracción de `retrieval`, `chat` y `eval`. Sin un punto único delante del monolito, cada
extracción obligaría a cambiar el frontend y a coordinar despliegues. LAB-107 pide ese punto antes de la primera
extracción (LAB-129).

## Decisión

1. **Spring Cloud Gateway (WebFlux) en su propio repositorio, `shoplab-gateway`**, sin estado, con Spring Boot 4.
2. **Recibe todo `/api`** y lo reenvía por prefijo sin reescribir nada. La web estática la sigue sirviendo el frontend.
3. **La tabla de rutas es configuración**: un mapa `shoplab.gateway.routes.<nombre>` que se sobrescribe o amplía con
   variables de entorno. Mover una ruta no publica versión nueva.
4. **Errores propios `SLHD_GATE_NNNNN`** con el formato de `ApiErrorResponse`; los del destino pasan tal cual.
5. **Local**: el gateway toma el `8080` y el monolito pasa al `8081`.
6. **Fuera de alcance**: identidad y límite de peticiones (LAB-60), reintentos, cortacircuitos y reparto de tráfico.

Desviaciones respecto a la plantilla de LAB-103: sin base de datos ni Flyway; sin el resource server JWT (exigiría token
en todo el tráfico antes de que exista el login); sin dependencia de `shoplab-contracts` (el CI reutilizable no tiene
credenciales para GitHub Packages; el formato de error se comprueba con pruebas); sin capas de dominio (un proxy no
tiene reglas de negocio); y un flujo de publicación propio.

Alternativas descartadas: seguir enrutando solo con el ingress (no existe en local, y LAB-60 pide este gateway para JWT
y límites); la lista `spring.cloud.gateway.server.webflux.routes` (sobrescribir un elemento por entorno reemplaza la
lista); descubrimiento de servicios (innecesario con nombres de Service fijos).

## Consecuencias

- Las extracciones de LAB-129, LAB-59 y LAB-130 solo cambian configuración del gateway.
- Hay un salto de red más y un componente cuya caída afecta a toda la API. Revertir el cambio del ingress devuelve el
  tráfico directo al monolito.
- Las cabeceras `Forwarded` y `X-Forwarded-*` de un remitente no confiable se descartan (valor por defecto del gateway).
  Ningún destino las lee hoy; LAB-60 lo revisará si limita por dirección.
- Quien arranque el backend desde el IDE usa `SERVER_PORT=8081`.
- La plantilla de LAB-103 tiene fallos que los próximos servicios heredarán (ver
  `specs/LAB-107-strangler-gateway/validation.md`).
