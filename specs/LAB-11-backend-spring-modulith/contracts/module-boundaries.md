# Contrato: fronteras de módulos

El backend no expone endpoints de negocio en esta historia (solo los de Actuator). El contrato
relevante es el que los módulos ofrecen a quienes los desarrollen después.

## Módulos

| Módulo | Paquete | Responsabilidad futura |
|---|---|---|
| `chat` | `com.shoplab.helpdesk.chat` | Conversación y streaming |
| `retrieval` | `com.shoplab.helpdesk.retrieval` | Búsqueda y recuperación de contexto (RAG) |
| `tools` | `com.shoplab.helpdesk.tools` | Herramientas invocables por el agente |
| `tickets` | `com.shoplab.helpdesk.tickets` | Gestión de tickets |

## Reglas

1. Un módulo solo expone lo que está en su paquete raíz; lo demás va en subpaquetes internos
   (`domain`, `application`, `infrastructure`) creados cuando exista código que los necesite.
2. La comunicación entre módulos es por API del paquete raíz o eventos de dominio en proceso;
   nunca por acceso a internos ni tablas compartidas.
3. Una dependencia nueva entre módulos se declara explícitamente (`allowedDependencies`) y la
   revisa el test de arquitectura.
4. Sin ciclos.

## Endpoints disponibles

| Endpoint | Propósito |
|---|---|
| `GET /actuator/health` | Comprobar que la aplicación arrancó (`{"status":"UP"}`) |

No se genera `requests.http`: no se añaden endpoints de negocio.
