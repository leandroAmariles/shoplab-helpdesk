# Contrato: estructura de módulo

## Mapa esperado (`ModularityTests`)

`chat`, `retrieval`, `orders`, `catalog`, `tickets`, `shared`. Cualquier otro módulo detectado (en particular `tools`)
hace fallar la prueba.

## Subpaquetes permitidos por módulo de negocio

| Paquete | Contenido | Puede depender de |
|---|---|---|
| `api` | Interfaz nombrada de Modulith: herramientas `@Tool`, `ToolCallbackProvider`, tipos públicos | `application`, `domain`, `shared` |
| `domain` | Modelo, constantes, enums, excepciones; sin frameworks de IA, mensajería, JPA ni Reactor | solo JDK y `shared` |
| `application` | Puertos `in`/`out` y casos de uso; admite `Mono`/`Flux` | `domain`, `config`, `shared` |
| `infrastructure` | Adaptadores `in`/`out` (REST, Ollama, JDBC, métricas); aísla lo bloqueante | todo lo anterior y librerías |
| `config` | Cableado y propiedades; sin lógica | todo lo anterior |

`shared` (módulo abierto, ADR-0010) y la clase raíz de la aplicación están exentos.

## Publicación de herramientas

```java
// <modulo>/api: único lugar permitido para @Tool
@Configuration
class OrdersToolsConfig {
    @Bean
    ToolCallbackProvider ordersTools(OrdersTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

`chat` compone sin conocer a los demás módulos:

```java
// chat/application
List<ToolCallback> tools = providers.orderedStream()
        .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
        .toList();
```

Un módulo sin herramientas no publica nada; `chat` funciona con la lista vacía (estado actual).

## Verificación

`ApplicationModules.of(ShopLabHelpdeskApplication.class).verify()` en verde: sin ciclos ni accesos a elementos
internos de otro módulo. Documenter escribe en `docs/modulos/`.
