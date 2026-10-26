# Contrato de aislamiento de operaciones bloqueantes

**Feature**: LAB-111 | Regla de convivencia que fija el ADR-0011: web y proveedor del modelo reactivos, persistencia
bloqueante (JDBC) aislada en `infrastructure`.

## `BlockingCalls` (`shared/infrastructure/out/blocking`)

Ayudante sin estado y sin dependencias de negocio. Es el **único** sitio de `src/main` que nombra
`Schedulers.boundedElastic()`.

| Método | Entrada | Salida | Comportamiento |
|--------|---------|--------|----------------|
| `call(Callable<T>)` | código bloqueante que devuelve un valor | `Mono<T>` | Ejecuta al suscribirse, en `boundedElastic`; una excepción se emite como señal de error; `null` produce `Mono` vacío |
| `run(Runnable)` | código bloqueante sin resultado | `Mono<Void>` | Igual, completa sin elemento |
| `stream(Callable<? extends Iterable<T>>)` | consulta bloqueante que devuelve una colección | `Flux<T>` | Ejecuta la consulta en `boundedElastic` y emite sus elementos |

Garantías:

- Nada se ejecuta al construir el `Mono`/`Flux`, solo al suscribirse (perezoso).
- El código bloqueante nunca corre en un hilo del bucle de eventos ni en `parallel`/`single`.
- Cancelar la suscripción interrumpe el hilo; si el código ignora la interrupción (JDBC), su resultado se descarta.
- El contexto de Reactor (`requestId`) sigue disponible en los operadores posteriores.

## Cómo lo usa un adaptador (ejemplo abstracto, no se implementa en esta historia)

```java
// infrastructure/out/jdbc/JdbcThingAdapter.java  (futuro)
public Mono<Thing> findById(ThingId id) {
    return BlockingCalls.call(() -> jdbcTemplate.queryForObject(SQL, mapper, id.value()));
}
```

El puerto de aplicación expone `Mono<Thing>`; el caso de uso lo compone sin saber que detrás hay JDBC.

## Reglas de arquitectura (ArchUnit, `ReactiveArchitectureTests`)

| # | Regla | Requisito |
|---|-------|-----------|
| A1 | Clases en `..domain..`, `..application..` y `..in.rest..` no dependen de `reactor.core.scheduler.Schedulers` | FR-007 |
| A2 | Clases en `..domain..` y `..application..` no dependen de `java.sql`, `javax.sql` ni `org.springframework.jdbc` | FR-007, FR-008 |
| A3 | `Schedulers` solo se usa desde `..infrastructure..` | FR-007 |
| A4 | Ninguna clase de `src/main` usa `RestTemplate`, `RestClient`, `HttpURLConnection` ni `java.net.http.HttpClient` | FR-005 |
| A5 | Ninguna clase de `src/main` llama `Mono#block`, `Flux#blockFirst`, `Flux#blockLast` ni `Mono#toFuture().get()` | Constraints del plan |

## Detección en ejecución (BlockHound)

- Instalado una vez por sesión de pruebas (`LauncherSessionListener`); `-XX:+AllowRedefinitionToAddDeleteMethods`
  en Surefire.
- Allowances solo en `HelpdeskBlockHoundIntegration`, uno por línea, con el motivo; sin comodines de paquete.
- Prueba negativa (`BlockHoundDetectionTests`): bloquear en `Schedulers.parallel()` produce `BlockingOperationError`;
  el mismo código vía `BlockingCalls` (`boundedElastic`) pasa.
