# Feature Specification: Métricas base con Prometheus y Grafana

**Feature Branch**: `feature/LAB-102-base-metrics-prometheus-grafana`

**Created**: 2026-10-26

**Status**: Implemented

**Input**: User description: "LAB-102 (F0-21) Como desarrollador, quiero ver desde el principio cómo se comportan el backend y el clúster, para detectar problemas antes de añadir el LLM. Criterios: backend con Actuator y Micrometer exponiendo `/actuator/prometheus`; en local, Prometheus y Grafana en el perfil `observability` de docker compose, con imágenes de versión fija; en el clúster, kube-prometheus-stack (lo instala Terraform en LAB-98) con un ServiceMonitor para el backend; dashboards versionados como código (JSON en git): JVM, HTTP (latencia p95, errores) y estado de los pods; una alerta de ejemplo (backend caído) en Alertmanager; los paneles de coste de LLM y lag de Kafka llegan después (LAB-65). Funciona en local: sí, en compose y en el clúster local. Depende de: LAB-98."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El backend publica sus métricas (Priority: P1)

Como desarrollador, quiero que el backend publique sus métricas en un punto de lectura estándar, para que cualquier
herramienta de monitorización pueda recogerlas sin tocar el código de negocio.

**Why this priority**: Es la base de todo lo demás: sin métricas publicadas no hay nada que dibujar ni alertar, ni en
compose ni en el clúster.

**Independent Test**: Arrancar el backend, pedir su punto de métricas y comprobar que devuelve métricas de JVM y de
peticiones HTTP en el formato que entiende Prometheus.

**Acceptance Scenarios**:

1. **Given** el backend en ejecución, **When** se consulta `/actuator/prometheus`, **Then** responde con éxito y
   contiene métricas de JVM (memoria, hilos, recolección de basura) y de peticiones HTTP (conteo y duración por ruta y
   estado).
2. **Given** el backend que ya atendió peticiones, **When** se consulta el punto de métricas, **Then** la duración de
   las peticiones se publica con histograma, de modo que se pueda calcular la latencia p95.
3. **Given** que las métricas se identifican por aplicación, **When** se consulta el punto de métricas, **Then** cada
   serie lleva una etiqueta que identifica al backend (`helpdesk-backend`).
4. **Given** el comportamiento actual de salud, **When** se consulta `/actuator/health`, **Then** sigue funcionando
   igual que antes del cambio.
5. **Given** una ruta de la API con variables (por ejemplo, un identificador), **When** se atienden peticiones con
   identificadores distintos, **Then** las métricas agrupan por la plantilla de la ruta y no crean una serie por
   cada identificador.

---

### User Story 2 - Ver métricas en local con Prometheus y Grafana (Priority: P1)

Como desarrollador, quiero activar un perfil `observability` en docker compose y ver las métricas del backend en un
panel, para detectar problemas en mi máquina antes de integrar el LLM.

**Why this priority**: Es el flujo diario de desarrollo; el perfil ya está reservado y vacío desde LAB-10, así que
esta historia es la que le da contenido.

**Independent Test**: Levantar compose con el perfil `observability` y el backend en marcha, abrir Grafana y comprobar
que los paneles muestran datos del backend sin configurar nada a mano.

**Acceptance Scenarios**:

1. **Given** compose sin el perfil `observability`, **When** se levanta el entorno, **Then** no arranca Prometheus ni
   Grafana y el comportamiento es el de antes de la historia.
2. **Given** compose con el perfil `observability`, **When** se levanta, **Then** arrancan Prometheus y Grafana con
   imágenes de versión fija y sus comprobaciones de salud pasan.
3. **Given** el perfil activo y el backend en marcha, **When** se consulta el estado de los objetivos en Prometheus,
   **Then** el backend aparece como objetivo recogido correctamente.
4. **Given** el perfil activo, **When** se abre Grafana por primera vez, **Then** el origen de datos de Prometheus y
   los dashboards ya existen, sin importar ni configurar nada a mano.
5. **Given** el perfil activo y el backend detenido, **When** se consulta Prometheus, **Then** el backend figura como
   caído y los paneles lo reflejan sin que Prometheus ni Grafana fallen.
6. **Given** el perfil activo y un reinicio de los contenedores, **When** vuelven a levantar, **Then** los dashboards
   siguen disponibles porque salen de git y no de una configuración hecha en la interfaz.

---

### User Story 3 - Ver métricas del backend y del clúster en Kubernetes (Priority: P1)

Como desarrollador, quiero que la pila de monitorización del clúster local recoja las métricas del backend y de los
pods, para tener la misma visibilidad que en compose cuando despliego con Argo CD.

**Why this priority**: La historia declara explícitamente que funciona en el clúster local, y el estado de los pods
solo existe allí.

**Independent Test**: Con el clúster de LAB-98 y el backend desplegado, comprobar en Prometheus que el backend es un
objetivo sano y abrir en Grafana los dashboards con datos reales.

**Acceptance Scenarios**:

1. **Given** el clúster local con la plataforma de LAB-98, **When** se despliega el backend, **Then** Prometheus lo
   descubre como objetivo mediante un `ServiceMonitor` y lo recoge sin editar la configuración de Prometheus a mano.
2. **Given** el backend desplegado, **When** se abre Grafana del clúster, **Then** están los dashboards de JVM, HTTP y
   pods con datos del backend.
3. **Given** que los dashboards se versionan como código, **When** se reconstruye el clúster (`destroy` y `apply`),
   **Then** los dashboards reaparecen sin intervención manual.
4. **Given** el `ServiceMonitor` y el backend, **When** el backend se escala a varias réplicas, **Then** cada réplica
   aparece como un objetivo distinto.
5. **Given** que el `ServiceMonitor` se sincroniza por Argo CD, **When** el estado del ambiente `dev` se compara con
   git, **Then** queda `Synced` y `Healthy`.

---

### User Story 4 - Dashboards versionados como código (Priority: P1)

Como desarrollador, quiero que los dashboards de JVM, HTTP y pods vivan como JSON en git, para revisarlos en pull
requests y obtener los mismos en compose y en el clúster.

**Why this priority**: Es un criterio de aceptación explícito y evita que los paneles dependan de clics hechos en una
máquina concreta.

**Independent Test**: Borrar los volúmenes de Grafana, volver a levantar y comprobar que los tres dashboards están
con sus paneles; abrir cada JSON en git y comprobar que es válido.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se revisan los dashboards, **Then** existen como JSON los de JVM, HTTP y estado
   de los pods, cada uno con un identificador y un título estables.
2. **Given** el dashboard de JVM, **When** se abre con datos, **Then** muestra al menos memoria heap, hilos y pausas
   de recolección de basura.
3. **Given** el dashboard de HTTP, **When** se abre con tráfico, **Then** muestra tasa de peticiones, latencia p95 y
   tasa de errores (respuestas 5xx) por ruta.
4. **Given** el dashboard de pods, **When** se abre en el clúster, **Then** muestra el estado de los pods del backend
   (en ejecución, reinicios, listos o no).
5. **Given** un dashboard modificado desde la interfaz de Grafana, **When** se reinicia Grafana, **Then** vuelve a la
   versión de git, y la forma de cambiarlo de verdad es una pull request.
6. **Given** un JSON de dashboard inválido en una pull request, **When** corre la validación del repositorio,
   **Then** la pull request falla antes de fusionarse.

---

### User Story 5 - Alerta de ejemplo: backend caído (Priority: P2)

Como desarrollador, quiero una alerta de ejemplo que avise cuando el backend esté caído, para validar de punta a punta
el camino métrica, regla y Alertmanager antes de añadir alertas más útiles.

**Why this priority**: Demuestra el circuito de alertado, pero el valor diario de la historia (ver métricas) existe sin
ella.

**Independent Test**: Detener el backend en el clúster, esperar el tiempo configurado y comprobar que la alerta pasa a
disparada en Alertmanager; reiniciarlo y comprobar que se resuelve.

**Acceptance Scenarios**:

1. **Given** el clúster con el backend sano, **When** se consulta Alertmanager, **Then** la alerta de backend caído
   existe como regla y no está disparada.
2. **Given** el backend detenido o sin responder durante más del tiempo de espera configurado, **When** se evalúa la
   regla, **Then** la alerta pasa a disparada y aparece en Alertmanager con severidad y descripción comprensibles.
3. **Given** un corte más breve que el tiempo de espera (por ejemplo, un reinicio normal), **When** se evalúa la
   regla, **Then** la alerta no se dispara.
4. **Given** la alerta disparada, **When** el backend vuelve a responder, **Then** la alerta se resuelve sola.
5. **Given** que la regla se versiona en git, **When** se reconstruye el clúster, **Then** la regla reaparece sin
   configuración manual.

---

### Edge Cases

- ¿Qué pasa si el backend aún no ha arrancado cuando Prometheus empieza a recoger? Aparece como caído hasta que
  responda, sin errores ni reinicios de Prometheus.
- ¿Qué pasa si los puertos locales de Prometheus o Grafana ya están ocupados? Los puertos se pueden cambiar con
  variables, como el resto de servicios de compose.
- ¿Qué pasa si se consulta el punto de métricas desde fuera de la red del clúster o de compose? Las métricas no
  deben exponerse fuera de lo necesario: el acceso queda limitado a la red interna (en el clúster, no se publica por
  el ingress).
- ¿Qué pasa si el `ServiceMonitor` se aplica antes de que exista su definición (CRD) en el clúster? La
  sincronización de `dev` no debe quedar rota de forma permanente: se resuelve por reintento o por orden de
  instalación.
- ¿Qué pasa cuando se despliega una versión del backend sin el punto de métricas? El objetivo aparece caído y la
  alerta lo señala; no se ocultan los errores.
- ¿Qué pasa con la cardinalidad de etiquetas? Ninguna métrica debe incluir etiquetas con valores ilimitados
  (identificadores de usuario o de ticket, textos de consulta).
- ¿Qué pasa en una máquina con poca memoria? El perfil `observability` es opcional y no se activa por defecto; el
  clúster local de un solo nodo debe seguir pudiendo alojar el backend con la pila ya instalada por LAB-98.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El backend MUST publicar sus métricas en `/actuator/prometheus` en el formato de exposición de
  Prometheus.
- **FR-002**: Las métricas MUST incluir las de JVM (memoria, hilos, recolección de basura, clases cargadas) y las de
  peticiones HTTP (conteo, duración y estado por ruta).
- **FR-003**: La duración de las peticiones HTTP MUST publicarse con histograma, de modo que la latencia p95 pueda
  calcularse en Prometheus y Grafana.
- **FR-004**: Todas las métricas MUST llevar una etiqueta común que identifique la aplicación (`helpdesk-backend`).
- **FR-005**: Las métricas HTTP MUST agrupar por plantilla de ruta y MUST NOT incluir etiquetas de cardinalidad
  ilimitada.
- **FR-006**: `/actuator/health` MUST seguir funcionando igual; el nuevo punto de métricas MUST exponerse de forma
  explícita, sin habilitar el resto de puntos de Actuator por defecto.
- **FR-007**: El perfil `observability` de docker compose MUST arrancar Prometheus y Grafana, y MUST NOT arrancar
  nada de ellos cuando el perfil no está activo.
- **FR-008**: Las imágenes de Prometheus y Grafana MUST estar fijadas a una versión concreta (nunca `latest`) y ser
  sobrescribibles con variables de entorno, igual que las demás imágenes de compose.
- **FR-009**: Prometheus (compose) MUST recoger el backend sin configuración manual, también cuando el backend corre
  en el host y no en un contenedor.
- **FR-010**: Grafana (compose) MUST arrancar con el origen de datos de Prometheus y los dashboards ya
  aprovisionados desde archivos versionados en git.
- **FR-011**: Los servicios nuevos de compose MUST tener comprobación de salud y respetar las convenciones del
  archivo (volúmenes con nombre, `restart`, puertos configurables).
- **FR-012**: En el clúster, la pila `kube-prometheus-stack` instalada por Terraform en LAB-98 MUST descubrir el
  backend mediante un `ServiceMonitor` versionado en git, sin editar la configuración de Prometheus a mano.
- **FR-013**: El `ServiceMonitor` MUST desplegarse a través de Argo CD como parte del ambiente `dev` de
  `shoplab-infra`, y MUST ser seleccionado por Prometheus (etiquetas y espacio de nombres coherentes con la
  instalación de LAB-98).
- **FR-014**: Los dashboards de JVM, HTTP y estado de los pods MUST existir como JSON versionado en git, con
  identificador y título estables.
- **FR-015**: El dashboard de HTTP MUST mostrar al menos tasa de peticiones, latencia p95 y tasa de errores; el de
  JVM, al menos memoria heap, hilos y pausas de recolección; el de pods, estado, reinicios y disponibilidad de los
  pods del backend.
- **FR-016**: Los mismos JSON de dashboard MUST usarse en compose y en el clúster, sin duplicarlos; las diferencias
  de entorno se resuelven con variables o etiquetas del origen de datos.
- **FR-017**: En el clúster, los dashboards MUST cargarse automáticamente en Grafana desde recursos versionados, y
  reaparecer tras un `destroy` y un `apply`.
- **FR-018**: MUST existir una alerta de ejemplo «backend caído», definida como regla versionada en git, que se
  dispare tras un tiempo de espera configurable sin que el backend responda y se resuelva sola al recuperarse.
- **FR-019**: La alerta MUST llegar a Alertmanager con nombre, severidad y descripción que indiquen qué falló y dónde
  mirar; no se configura ningún canal de notificación externo.
- **FR-020**: El repositorio MUST validar en CI que los JSON de dashboard son válidos y que los manifiestos nuevos
  (`ServiceMonitor`, regla de alerta) se construyen y pasan las políticas existentes.
- **FR-021**: Ninguna imagen ni chart nuevo MUST usar `latest` ni rangos abiertos; las versiones nuevas quedan
  fijadas.
- **FR-022**: Ningún secreto MUST estar versionado; las credenciales de Grafana locales son marcadores de posición
  obvios y solo válidos en local.
- **FR-023**: El procedimiento (activar el perfil, direcciones de Prometheus, Grafana y Alertmanager, cómo añadir un
  dashboard y cómo probar la alerta) MUST estar documentado.
- **FR-024**: Los paneles de coste de LLM y de lag de Kafka MUST NOT incluirse; quedan para LAB-65.

### Key Entities *(include if feature involves data)*

- **Métrica**: medición numérica del backend o del clúster con nombre, etiquetas y valor en el tiempo; se publica en
  el punto de métricas del backend.
- **Objetivo de recogida (target)**: instancia del backend (una por réplica) que Prometheus consulta periódicamente;
  tiene estado sano o caído.
- **ServiceMonitor**: recurso del clúster que declara qué servicio recoger, en qué puerto y ruta; vive en git y lo
  sincroniza Argo CD.
- **Dashboard**: panel de Grafana definido como JSON versionado, con identificador y título estables; el mismo
  archivo sirve en compose y en el clúster.
- **Regla de alerta**: condición versionada («backend caído durante N minutos») que Prometheus evalúa y envía a
  Alertmanager.
- **Alerta**: instancia disparada de una regla, con severidad, descripción y estado (disparada o resuelta).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el perfil `observability` activo y el backend en marcha, el desarrollador ve datos reales del
  backend en los dashboards de JVM y HTTP en menos de 2 minutos desde el arranque, sin pasos manuales.
- **SC-002**: En el clúster local, el backend aparece como objetivo sano en Prometheus y con datos en los 3
  dashboards tras desplegarlo, sin editar configuración a mano.
- **SC-003**: Tras `destroy` y `apply` del clúster (o borrar los volúmenes de compose), el 100 % de los dashboards y
  de las reglas de alerta reaparece sin intervención manual.
- **SC-004**: Con el backend detenido, la alerta de ejemplo se dispara en menos de 5 minutos y se resuelve en menos
  de 5 minutos tras recuperarse; un reinicio normal no la dispara.
- **SC-005**: Sin el perfil `observability`, el arranque de compose consume los mismos recursos y servicios que antes
  de la historia (0 contenedores nuevos).
- **SC-006**: El 100 % de las imágenes y charts nuevos tiene versión fijada; 0 usos de `latest` o rangos abiertos.
- **SC-007**: El 100 % de los dashboards y manifiestos nuevos se valida automáticamente en CI; una pull request con
  un JSON o manifiesto inválido no puede fusionarse.
- **SC-008**: Las métricas del backend mantienen una cardinalidad acotada: ninguna métrica HTTP crece con el número de
  identificadores usados en las rutas.
- **SC-009**: Un desarrollador nuevo activa el perfil, abre los paneles y prueba la alerta siguiendo solo la
  documentación, en una sola sesión.

## Assumptions

- La historia abarca dos repositorios: el backend, compose, dashboards y documentación en `shoplab-helpdesk`, y el
  `ServiceMonitor`, la regla de alerta y la carga de dashboards en el clúster en `shoplab-infra`. La especificación
  vive aquí, donde están las de todas las historias LAB; la parte de `shoplab-infra` se entrega con su propia pull
  request.
- Los dashboards JSON viven en un único lugar de este repositorio (`infra/`) y el clúster los toma de ahí en vez de
  copiarlos, para cumplir FR-016; el mecanismo exacto lo decide el plan.
- `kube-prometheus-stack` ya está instalado por LAB-98 con Prometheus y Grafana, pero con Alertmanager desactivado
  para ahorrar recursos; esta historia no lo reinstala, solo lo configura (activa Alertmanager y abre los
  selectores de descubrimiento).
- Solo existe el ambiente `dev` (ADR-0008); no se definen reglas ni dashboards por ambiente.
- Los umbrales de la alerta (tiempo de espera y severidad) son valores razonables por defecto (p. ej. 1-2 minutos,
  severidad `critical`) y se pueden ajustar sin cambiar la especificación.
- No se configuran receptores externos de Alertmanager (correo, chat): la alerta se comprueba en la interfaz y en la
  API de Alertmanager.
- Las métricas se exponen sin autenticación dentro de la red interna de compose o del clúster; no se publican por el
  ingress. Si el backend añade seguridad en una historia posterior, el punto de métricas se excluye o se protege
  entonces.
- Solo se cubren métricas de JVM, HTTP y pods. Las métricas de negocio, de LLM (coste, tokens) y de Kafka, las trazas
  y los registros centralizados quedan fuera del alcance (LAB-65 y fases posteriores).
- La retención de datos de Prometheus es la de los valores por defecto del laboratorio; no se define almacenamiento
  de largo plazo.
- Se mantiene la convención del repositorio: identificadores en inglés, documentación y ADR en español. No se
  requiere un ADR nuevo salvo que el plan introduzca una decisión arquitectónica relevante.
