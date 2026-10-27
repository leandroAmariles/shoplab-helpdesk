# Contrato: reglas de arquitectura (ArchUnit)

Patrón heredado de LAB-111 (`ReactiveArchitectureRules`): cada regla es un método estático que devuelve un
`ArchRule`; `ModuleArchitectureTests` comprueba (a) que el código de producción las cumple y (b) que cada regla **falla**
contra su fixture en `architecture/violations/`.

| ID | Regla | Paquetes afectados | Fixture de violación |
|---|---|---|---|
| D1 | `domain` no depende de `org.springframework.ai..`, `org.springframework.kafka..`, `org.apache.kafka..`, `jakarta.persistence..`, `org.springframework.data.jpa..`, `reactor..` | `..domain..` | `violations/domain/SpringAiInDomain`, `KafkaInDomain`, `JpaInDomain`, `ReactorInDomain` |
| D2 | `domain` no depende de `application`, `infrastructure`, `api`, `config`; `application` no depende de `infrastructure` | `..domain..`, `..application..` | `violations/layers/DomainUsesInfrastructure`, `ApplicationUsesInfrastructure` |
| D3 | Las clases de un módulo de negocio viven solo en `api`, `domain`, `application`, `infrastructure` o `config` | `com.shoplab.helpdesk.{chat,retrieval,orders,catalog,tickets}..` | `violations/layers/ClassOutsideLayers` |
| D4 | Métodos `@Tool` solo en `..api..` | todo | `violations/tools/ToolOutsideApi` |
| D5 | Un módulo solo usa de otro su `api` (no `domain`/`application`/`infrastructure`) | módulos de negocio | `violations/modules/CrossModuleInternals` |

## Notas

- `Mono`/`Flux` no se restringen en `application` ni `infrastructure`; solo D1 los prohíbe en `domain`.
- Las reglas de LAB-111 (A1–A6) siguen ejecutándose sin cambios.
- Módulo sin clases (`orders`, `catalog` vacíos): las reglas usan `allowEmptyShould(true)` para no dar falsos fallos.
- Mensaje de fallo: clase infractora, dependencia prohibida y razón (`.because(...)`).
- Los fixtures viven en `src/test` bajo paquetes con la forma `…<modulo-ficticio>.domain` para que casen con los
  patrones de paquete y se excluyan de la comprobación de producción (`DO_NOT_INCLUDE_TESTS`).
