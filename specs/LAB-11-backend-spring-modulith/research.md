# Research: Backend modular con Spring Modulith y Spring AI

Versiones consultadas en Maven Central el 2026-10-23. Se confirman de nuevo al implementar
(`./mvnw dependency:tree` y construcción verde).

## D1 - Spring Boot

- **Decisión**: Spring Boot `4.1.2` como parent (última GA de la línea 4.1; existen 4.0.9 y
  4.2.0-RC1).
- **Razón**: criterio de LAB-11 (4.x); 4.1.x es estable y soportada. Boot 4 usa Spring Framework 7 y
  JUnit 6.
- **Alternativas**: 4.0.9 (línea anterior, menos reciente); 4.2.0-RC1 (pre-release, descartada para
  una base).

## D2 - Spring AI

- **Decisión**: importar `spring-ai-bom` `2.0.2` en `dependencyManagement`; no añadir starters aún.
- **Razón**: el criterio pide el BOM; los starters (p. ej. `spring-ai-starter-model-ollama`, que
  existe en 2.0.2 y usa los starters de Boot 4 `webclient`/`restclient`) se añaden en la historia de
  chat. Evita dependencias sin uso (YAGNI) y no exige Ollama para arrancar (FR-008).
- **Alternativas**: 2.1.0-RC1 (pre-release); añadir ya el starter de Ollama (arrastraría
  autoconfiguración que intenta conectar con un servidor).
- **Riesgo**: compatibilidad Boot 4.1.x con Spring AI 2.0.x; se valida en el build y se anota en el
  ADR.

## D3 - Spring Modulith

- **Decisión**: BOM `spring-modulith-bom` `2.1.2` (Boot no lo gestiona) con
  `spring-modulith-starter-core` y `spring-modulith-starter-test` (test).
- **Razón**: línea 2.1 compatible con Boot 4; el starter de test aporta `ApplicationModules`.
- **Alternativas**: ArchUnit puro (sin modelo de módulos ni documentación); multi-módulo Maven
  (más pesado, contradice ADR-0001).

## D4 - Detección de módulos vacíos

- **Decisión**: cada módulo es un paquete directo de `com.shoplab.helpdesk` con un
  `package-info.java` anotado con `@ApplicationModule(displayName = ...)`.
- **Razón**: Java no permite paquetes sin tipos y Modulith deriva los módulos de los subpaquetes
  con clases; `package-info` cumple "solo la declaración mínima" (spec US2.2).
- **Plan B**: si Modulith no reconoce un módulo solo con `package-info`, usar una clase marcador
  mínima por módulo; el test de módulos lo detecta (fallaría).

## D5 - Web y observabilidad

- **Decisión**: `spring-boot-starter-webmvc` + `spring-boot-starter-actuator`.
- **Razón**: mantiene vivo el proceso (US1.2 "arranca y se detiene limpiamente"), expone salud
  (constitución VI) y MVC soporta streaming SSE para el chat; la decisión MVC vs WebFlux se puede
  revisar con ADR si la historia de chat lo exige.
- **Alternativas**: solo `spring-boot-starter` (el proceso termina al instante); WebFlux (mayor
  complejidad sin necesidad hoy).

## D6 - Java y herramientas de construcción

- **Decisión**: `maven.compiler.release=21`, Maven Wrapper (`mvnw`) y `maven-enforcer-plugin`
  (`requireJavaVersion [21,)`, `requireMavenVersion [3.9,)`).
- **Razón**: FR-002 y el caso borde de Java antiguo (mensaje claro). El wrapper elimina la
  dependencia de una versión global de Maven. El JDK local es 17, así que el desarrollador debe
  instalar JDK 21 (se documenta en el README).
- **Alternativas**: toolchains de Maven (más configuración por máquina).

## D7 - Controles de calidad

- **Decisión**: JaCoCo (`check` en `verify`, mínimo 80 % de líneas, exclusiones de las guías:
  `dto`, `config`, `model`, `util/mapper`, `exception`, más la clase `*Application`), Checkstyle
  (línea 121, `NeedBraces`, `UnusedImports`) y PMD (`UnnecessaryImport`, `UnusedFormalParameter`)
  con configuración mínima en `backend/config/`.
- **Razón**: constitución IV y "style gates"; mejor fijarlos en la base que retro-adaptarlos.
- **Alternativas**: diferirlos a otra historia (las siguientes nacerían ya incumpliendo la
  constitución).

## D8 - Identidad del proyecto

- **Decisión**: `groupId` `com.shoplab`, `artifactId` `helpdesk-backend`, paquete base
  `com.shoplab.helpdesk`, clase `ShopLabHelpdeskApplication`.
- **Razón**: nombres en inglés, alineados con el repositorio. No afecta a la spec y es fácil de
  cambiar ahora.

## D9 - ADR

- **Decisión**: `docs/adr/0004-spring-boot-4-spring-ai-2.md` (0001-0003 ya existen), estado
  `aceptado`, formato de la plantilla (Contexto, Decisión, Consecuencias) y las alternativas
  descartadas dentro de Decisión/Consecuencias: Spring Boot 3.5 + Spring AI 1.x, LangChain4j y
  backend en Python.

## D10 - Conflicto con la constitución

- **Decisión**: enmendar la constitución (PATCH, 1.0.1) y el README, que dicen "Spring Boot 3".
- **Razón**: la constitución prevalece sobre otras fuentes; el criterio de LAB-11 es explícito.

Sin elementos `NEEDS CLARIFICATION` pendientes.
