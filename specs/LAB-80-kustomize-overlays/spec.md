# Feature Specification: Manifiestos con Kustomize y overlays dev, staging y prod

**Feature Branch**: `feature/LAB-80-kustomize-overlays`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "LAB-80 (F0-17) Como desarrollador, quiero describir el despliegue de forma declarativa y por ambiente, empezando por el monolito y creciendo con cada servicio. Criterios: en `shoplab-infra/k8s/`, `base/` con backend, frontend, ingest, Postgres con pgvector, Ollama e ingress (Kafka se añade en F4); overlays `dev`, `staging` y `prod` con réplicas, recursos, HPA y configuración por ambiente; probes de liveness y readiness con Actuator en el backend; todas las imágenes con versión fija o digest, ninguna `:latest`; secretos con ExternalSecret que leen de Secrets Manager (LAB-98), nunca en claro en git; `kustomize build` de cada overlay valida en CI (LAB-99); desde F4, cada servicio nuevo incluye su base y sus overlays como parte de su Definition of Done. Depende de: LAB-98."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Base declarativa del monolito (Priority: P1)

Como desarrollador, quiero una base de manifiestos que describa todos los componentes de la
plataforma (backend, frontend, ingest, Postgres con pgvector, Ollama e ingress), para tener una
única descripción declarativa del despliegue de la que parten todos los ambientes.

**Why this priority**: Sin la base no existe nada que parametrizar; es el cimiento de las demás
historias y por sí sola ya permite construir un despliegue completo.

**Independent Test**: Construir la base y comprobar que produce los recursos de los seis
componentes, que cada uno tiene sus servicios y configuración mínima, y que el resultado es
aceptado por el esquema de Kubernetes.

**Acceptance Scenarios**:

1. **Given** el directorio `k8s/base/` de `shoplab-infra`, **When** se construye, **Then** produce
   los recursos de backend, frontend, ingest, Postgres con pgvector, Ollama e ingress sin errores.
2. **Given** la base construida, **When** se inspeccionan las imágenes, **Then** cada una tiene
   versión fija o digest y ninguna usa `:latest` ni carece de tag.
3. **Given** la base construida, **When** se valida contra el esquema de Kubernetes, **Then** todos
   los recursos son válidos.
4. **Given** el backend desplegado, **When** Kubernetes consulta sus sondas de liveness y
   readiness, **Then** estas apuntan a los endpoints de salud del backend y distinguen "proceso
   vivo" de "listo para recibir tráfico".
5. **Given** la base, **When** se busca la configuración de Kafka, **Then** no existe (se añade en
   F4) y su ausencia no rompe la construcción.

---

### User Story 2 - Overlays por ambiente (Priority: P1)

Como desarrollador, quiero overlays `dev`, `staging` y `prod` que ajusten réplicas, recursos,
autoescalado y configuración por ambiente sobre la misma base, para desplegar el mismo sistema con
el tamaño y los parámetros adecuados a cada ambiente sin duplicar manifiestos.

**Why this priority**: Es el objetivo central de la historia: describir el despliegue "por
ambiente". Sin overlays solo hay un despliegue genérico.

**Independent Test**: Construir cada overlay y comparar los resultados: las réplicas, los recursos,
el autoescalado y la configuración difieren entre ambientes según lo definido, mientras que la
estructura de componentes es la misma.

**Acceptance Scenarios**:

1. **Given** los overlays `dev`, `staging` y `prod`, **When** se construye cada uno, **Then** cada
   uno produce el conjunto completo de componentes de la base con sus ajustes propios.
2. **Given** el overlay `prod`, **When** se compara con `dev`, **Then** tiene más réplicas y
   mayores solicitudes y límites de recursos en los componentes con estado de carga.
3. **Given** los overlays `staging` y `prod`, **When** se construyen, **Then** los componentes
   escalables del monolito incluyen autoescalado horizontal con mínimo y máximo de réplicas
   propios de cada ambiente.
4. **Given** cualquier overlay, **When** se construye, **Then** la configuración no secreta
   (URLs, nombres de host, niveles de log) corresponde al ambiente y no aparece la de otro.
5. **Given** un cambio en la base, **When** se construyen los tres overlays, **Then** el cambio se
   refleja en todos sin editar cada overlay.

---

### User Story 3 - Secretos fuera de git con ExternalSecret (Priority: P1)

Como desarrollador, quiero que los secretos (credenciales de Postgres, claves de proveedores y
similares) se declaren como ExternalSecret que leen de Secrets Manager, para que ningún valor
sensible esté en claro en el repositorio y el despliegue los obtenga en tiempo de ejecución.

**Why this priority**: Es un requisito de seguridad innegociable: una credencial en git obliga a
rotarla y compromete el repositorio.

**Independent Test**: Buscar en los manifiestos construidos de los tres overlays cualquier recurso
`Secret` con datos en claro y comprobar que no existe; comprobar que cada secreto necesario está
declarado como ExternalSecret con la referencia a su entrada en Secrets Manager.

**Acceptance Scenarios**:

1. **Given** cualquier overlay construido, **When** se buscan recursos `Secret` con valores,
   **Then** no hay ninguno; los secretos se obtienen vía ExternalSecret.
2. **Given** un componente que necesita una credencial, **When** se inspecciona su manifiesto,
   **Then** la consume desde el secreto generado por su ExternalSecret.
3. **Given** un ExternalSecret, **When** se inspecciona, **Then** referencia por nombre una entrada
   de Secrets Manager existente en el ambiente definido por LAB-98 y no contiene el valor.
4. **Given** ambientes distintos, **When** se comparan, **Then** cada uno referencia sus propias
   entradas de Secrets Manager y no comparten secretos.

---

### User Story 4 - Validación de cada overlay en CI (Priority: P2)

Como desarrollador, quiero que `kustomize build` de cada overlay se valide en el pipeline de
LAB-99, para que un overlay roto o que incumpla las reglas no llegue a `main`.

**Why this priority**: Protege lo entregado en las historias anteriores, pero la validación en sí
la proporciona el pipeline de LAB-99; esta historia garantiza que los overlays son compatibles
con él.

**Independent Test**: Ejecutar localmente y en CI la construcción y validación de los tres
overlays y comprobar que pasan; introducir un `:latest` o un `Secret` en claro y comprobar que
falla.

**Acceptance Scenarios**:

1. **Given** los tres overlays, **When** corre el pipeline de LAB-99, **Then** cada overlay
   construye y valida en verde.
2. **Given** un overlay con una imagen `:latest`, **When** corre el pipeline, **Then** falla
   indicando la imagen y el archivo.
3. **Given** un overlay que no construye, **When** corre el pipeline, **Then** falla indicando el
   overlay afectado.

---

### User Story 5 - Convención para nuevos servicios desde F4 (Priority: P3)

Como desarrollador, quiero que quede documentado que cada servicio nuevo a partir de F4 incluye
su base y sus overlays como parte de su Definition of Done, y que existe una estructura clara para
añadirlo, para que el despliegue crezca de forma consistente con cada servicio.

**Why this priority**: No entrega manifiestos nuevos hoy, pero evita que los servicios futuros
queden sin despliegue declarativo.

**Independent Test**: Un desarrollador que no participó en la historia puede añadir un servicio de
ejemplo (base y los tres overlays) siguiendo solo la documentación, y la construcción de los tres
overlays sigue pasando.

**Acceptance Scenarios**:

1. **Given** la documentación de `shoplab-infra`, **When** se busca cómo añadir un servicio,
   **Then** describe los archivos a crear en la base y en cada overlay.
2. **Given** la Definition of Done del proyecto, **When** se revisa, **Then** exige base y overlays
   para cada servicio nuevo desde F4.

---

### Edge Cases

- Un overlay omite un componente que existe en la base: se detecta porque la construcción no
  produce el conjunto completo esperado.
- Imagen referenciada por digest en lugar de tag: es válida; solo se rechazan `:latest` y la
  ausencia de tag.
- Un `Secret` con valores en claro añadido por error en un overlay: debe ser detectado y rechazado
  antes de fusionar.
- Un ExternalSecret que apunta a una entrada de Secrets Manager inexistente: el pod dependiente no
  arranca y el fallo es visible en el estado del recurso, sin exponer valores.
- Componente con estado (Postgres, Ollama) con réplicas: no se escala horizontalmente por defecto;
  el autoescalado se aplica solo a componentes sin estado.
- Ollama requiere más memoria o aceleración que el resto: sus recursos se definen por ambiente y
  `dev` admite valores reducidos.
- Ambiente `dev` sin ingress con dominio real: usa un host local o de prueba, nunca uno de
  producción.
- Servicios que aún no existen (Kafka y servicios de F4+): no deben aparecer como referencias
  rotas en la base.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `shoplab-infra/k8s/base/` MUST describir backend, frontend, ingest, Postgres con
  pgvector, Ollama e ingress, cada uno con los recursos necesarios para ejecutarse y ser accesible.
- **FR-002**: La base MUST NOT incluir Kafka ni servicios de fases posteriores; su incorporación
  es posterior (F4).
- **FR-003**: Los overlays `dev`, `staging` y `prod` MUST existir bajo `shoplab-infra/k8s/`, cada
  uno construible por separado a partir de la base.
- **FR-004**: Cada overlay MUST definir réplicas y solicitudes y límites de recursos por
  componente, con valores distintos según el ambiente.
- **FR-005**: Los overlays MUST definir autoescalado horizontal (HPA) para los componentes sin
  estado, con mínimo y máximo de réplicas propios de cada ambiente.
- **FR-006**: Cada overlay MUST aportar la configuración no secreta específica del ambiente
  (host del ingress, URLs, nivel de log y equivalentes) sin duplicar los manifiestos de la base.
- **FR-007**: El backend MUST declarar probes de liveness y readiness apoyadas en los endpoints de
  salud de Actuator, de modo que readiness refleje la disponibilidad de sus dependencias.
- **FR-008**: Todas las imágenes de los manifiestos construidos MUST usar versión fija o digest;
  `:latest` y la ausencia de tag MUST NOT aparecer en ningún overlay.
- **FR-009**: Los secretos MUST declararse como ExternalSecret que leen de Secrets Manager
  (definido en LAB-98); ningún recurso `Secret` con valores en claro MUST existir en git ni en la
  salida de ningún overlay.
- **FR-010**: Cada ambiente MUST referenciar sus propias entradas de Secrets Manager, sin compartir
  secretos entre ambientes.
- **FR-011**: La construcción de cada overlay MUST validarse en el pipeline de LAB-99 y fallar el
  PR si algún overlay no construye, produce cero recursos o incumple FR-008 o FR-009.
- **FR-012**: Los manifiestos construidos MUST ser válidos contra el esquema de Kubernetes.
- **FR-013**: La documentación de `shoplab-infra` MUST explicar la estructura base/overlays, cómo
  construir un overlay en local y cómo añadir un servicio nuevo.
- **FR-014**: La Definition of Done del proyecto MUST exigir que, desde F4, cada servicio nuevo
  incluya su base y sus overlays.
- **FR-015**: La base y los overlays MUST ser coherentes con la infraestructura de LAB-98 (cluster
  local sobre Floci, Secrets Manager y operador de secretos disponibles) y desplegables por Argo CD
  desde `main`.

### Key Entities *(include if feature involves data)*

- **Base**: descripción común de todos los componentes de la plataforma, independiente del
  ambiente; punto único de cambio.
- **Overlay de ambiente** (`dev`, `staging`, `prod`): ajustes de réplicas, recursos, autoescalado
  y configuración aplicados sobre la base.
- **Componente**: unidad desplegable (backend, frontend, ingest, Postgres con pgvector, Ollama,
  ingress) con su imagen, sondas, servicio y configuración.
- **ExternalSecret**: declaración de un secreto que se obtiene de Secrets Manager; contiene la
  referencia, nunca el valor.
- **Autoescalado (HPA)**: regla de escalado horizontal con mínimo, máximo y métrica objetivo por
  ambiente.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Los 3 overlays (`dev`, `staging`, `prod`) construyen sin errores y producen el 100%
  de los componentes definidos para la fase (6 componentes).
- **SC-002**: 0 imágenes con `:latest` o sin tag en la salida de los 3 overlays.
- **SC-003**: 0 recursos `Secret` con valores en claro en el repositorio y en la salida de los 3
  overlays; el 100% de los secretos necesarios se declaran como ExternalSecret.
- **SC-004**: Un cambio en la base se refleja en los 3 ambientes editando un solo lugar, sin
  tocar los overlays en el 100% de los casos que no requieran ajustes específicos.
- **SC-005**: La validación de los 3 overlays en CI entrega resultado en menos de 5 minutos desde
  el push.
- **SC-006**: Un desarrollador que no participó en la historia añade un servicio de ejemplo
  siguiendo solo la documentación en menos de 30 minutos.
- **SC-007**: Una violación introducida a propósito (imagen `:latest`, `Secret` en claro, overlay
  roto) hace fallar el PR en el 100% de los intentos con un mensaje que identifica el archivo.

## Assumptions

- LAB-98 está completada: existe el cluster local sobre Floci, Secrets Manager y el operador de
  External Secrets disponible para resolver los ExternalSecret.
- LAB-99 está completada o en curso y aporta el pipeline que construye y valida los overlays; esta
  historia solo garantiza que los overlays sean compatibles con él.
- `shoplab-infra` es el repositorio destino de los manifiestos, bajo `k8s/`; este repositorio
  conserva únicamente la especificación y el plan.
- Las imágenes de backend, frontend e ingest provienen del registro definido en LAB-78 y se
  fijan por versión liberada o digest; las de Postgres con pgvector y Ollama son imágenes de
  terceros con versión fija.
- Postgres con pgvector y Ollama se despliegan como componentes con estado de una sola réplica
  (sin alta disponibilidad) en esta fase; la alta disponibilidad de datos queda fuera de alcance.
- El HPA aplica a backend, frontend e ingest; `dev` puede fijar un mínimo igual a un máximo bajo.
- Los valores concretos de réplicas, recursos y umbrales de HPA se fijan en el plan, partiendo de
  `dev` mínimo, `staging` intermedio y `prod` mayor.
- El nombre de las entradas de Secrets Manager sigue la convención de LAB-98.
- Fuera de alcance: Kafka y servicios de F4+, observabilidad en el cluster, certificados TLS
  gestionados, despliegue a un cloud real y gestión de estado remoto.
