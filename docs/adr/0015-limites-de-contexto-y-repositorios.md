# ADR-0015: Límites de contexto y estrategia de repositorios

- Estado: aceptado
- Fecha: 2026-10-27

## Contexto

ADR-0001 fija el monolito modular como punto de partida y anuncia que, a partir de F5, los módulos se extraen a
servicios. Hasta LAB-105 esa promesa era una intención: los módulos eran paquetes casi vacíos (`chat`, `retrieval`,
`tickets`, un módulo técnico `tools`) y el backend no tenía acceso a datos. LAB-17 (carga de Olist) y LAB-19 (base de
conocimiento) van a crear las primeras tablas; si caen en un esquema común con claves foráneas y `JOIN` entre
contextos, extraer un servicio en F5 significaría desenredar tablas en lugar de mover un módulo.

Hay que decidir ahora, antes de que existan datos, quién es dueño de cada dato, cómo se verifica y cómo se repartirán
los repositorios cuando los servicios salgan del monolito.

## Decisión

**Un dueño por dato.** El backend se divide por contexto de negocio en cinco módulos: `chat`, `retrieval`, `orders`,
`catalog` y `tickets` (más el módulo abierto técnico `shared`, ADR-0010, que no posee datos). El módulo técnico `tools`
desaparece: cada módulo publica sus herramientas `@Tool` en su subpaquete `api` y `chat` solo las compone.

- **Un esquema de PostgreSQL por módulo**, con el nombre del módulo, y migraciones de Flyway en
  `db/migration/<modulo>`. La convención no tiene excepciones: módulo = esquema = carpeta de migraciones, con su propia
  tabla `<modulo>.flyway_schema_history`. Un migrador ejecuta un Flyway por módulo al arrancar; sin base de datos o con
  una migración fallida, el arranque aborta nombrando el módulo.
- **Sin claves foráneas ni `JOIN` entre esquemas.** Las referencias entre módulos se guardan como identificadores y
  se resuelven por la API del módulo dueño (consulta, o evento en proceso). Un identificador puede quedar huérfano
  si el dueño borra el dato; el módulo que referencia lo trata como «no encontrado», no como corrupción. Un dato que
  parece compartido (p. ej. el cliente en pedidos y tickets) se modela como identificador más una vista propia de cada
  módulo, no como tabla común.
- **Capas por módulo**: `domain`, `application`, `infrastructure` (más `api` y `config`). Reglas ejecutables en CI:
  ArchUnit impide que `domain` importe Spring AI, Kafka, JPA o Reactor (`Mono` y `Flux` viven en `application` e
  `infrastructure`); impide `@Tool` fuera de `api`; y Modulith verifica los límites entre módulos. Un lint de SQL y una
  consulta al catálogo de PostgreSQL verifican que ninguna clave foránea cruza esquemas.
- **Documentación generada**: `ApplicationModules` y el `Documenter` de Modulith escriben el diagrama y una ficha por
  módulo en `docs/modulos/`, y CI falla si lo versionado difiere del código.

**Repositorios.**

- **Hasta F6-01 el monolito vive en `shoplab-helpdesk`.** Desde F5 se aplica **un repositorio por servicio**: extraer un
  módulo es mover su código y su esquema, y desde ese momento deja de compartir el repositorio, el ciclo de versiones
  y el pipeline con el resto.
- **Tres repositorios de soporte**, que no son servicios: `shoplab-contracts` (contratos), `shoplab-ci` (flujos y
  acciones reutilizables) y `shoplab-infra` (infraestructura y GitOps, ADR-0006).
- **Qué se comparte y qué no.** Se comparten **paquetes de contratos** versionados, solo con la forma de los datos que
  cruzan una frontera (DTO, esquemas de eventos, descripciones de API), sin lógica. **No** se comparten librerías con
  dominio (entidades, reglas, repositorios): acoplan los despliegues de los servicios y filtran el modelo de un
  contexto en otro. Si dos servicios necesitan la misma regla de negocio, uno es dueño y el otro la consulta.

Alternativas descartadas:

- **Esquema común con tablas por prefijo**: más simple de arrancar, pero nada impide una clave foránea o un `JOIN`
  entre contextos; el acoplamiento aparecería justo cuando se quiera extraer un servicio.
- **Roles de base de datos por módulo con permisos solo sobre su esquema**: es la garantía más fuerte, porque la base
  de datos misma rechazaría un acceso ajeno. Se difiere a F5, cuando cada servicio tenga su propia credencial de todos
  modos; hoy exigiría gestionar cinco credenciales dentro de un único proceso.
- **Un monorepo para todos los servicios**: compartiría pipeline y versiones, pero reintroduce el acoplamiento de
  despliegue que la extracción quiere evitar y complica la propiedad por equipo.
- **Una librería común de dominio**: evita duplicar código, pero es la vía más habitual para acabar con un
  monolito distribuido.

## Consecuencias

- LAB-17 y LAB-19 crean sus tablas en los esquemas `orders`, `catalog` y `retrieval` sin crear módulos ni mover
  estructura. LAB-19 ajusta su texto de «esquema `kb`» a «esquema `retrieval`».
- El backend **requiere PostgreSQL para arrancar** (no hay modo sin base de datos). En dev ya existe y recibe
  `SPRING_DATASOURCE_*` (`shoplab-infra`); las pruebas que levantan la aplicación usan una PostgreSQL desechable con
  Testcontainers, de modo que necesitan Docker.
- La integridad entre módulos ya no la garantiza la base de datos sino la capa `application` consultando la API del
  dueño. Es un coste asumido a cambio de poder extraer.
- Un `JOIN` armado concatenando cadenas dinámicas en Java no lo detecta el lint (solo ve SQL literal en scripts y
  bloques de texto); lo cubre la revisión de código y, a partir de F5, los roles por esquema.
- Cada módulo nuevo debe declarar su `ModuleSchema` en `<modulo>/config` y su carpeta `db/migration/<modulo>`, y una
  prueba de arquitectura comprueba que ambas coinciden con el paquete.
- Extraer un servicio en F5 consiste en: mover el paquete del módulo y su carpeta de migraciones a un repositorio
  nuevo, sustituir las llamadas en proceso por contratos de `shoplab-contracts`, y apuntar el servicio a su propia
  base de datos con el esquema que ya posee.
