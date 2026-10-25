# Feature Specification: Infraestructura como código sobre un AWS emulado en local (EKS local)

**Feature Branch**: `feature/LAB-98-terraform-floci-eks`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-98 (F0-15) Como desarrollador, quiero crear la infraestructura con Terraform contra un AWS emulado en local, para practicar el mismo flujo que en un EKS real sin pagar nube y reconstruir el entorno con un solo comando. Criterios: repo `shoplab-infra`, carpeta `terraform/`, módulos `state`, `eks`, `secrets`, `platform` y un entorno raíz por ambiente; Floci (MIT) en docker compose con imagen fijada; estado remoto en S3 de Floci con bloqueo nativo (`use_lockfile`); clúster EKS emulado con kubeconfig como output; secretos en Secrets Manager y parámetros en SSM sin valores en git; plataforma con ingress-nginx, External Secrets Operator, Argo CD y kube-prometheus-stack; versiones fijadas y `.terraform.lock.hcl` en git; `apply` de cero a Argo CD sincronizando y `destroy` que lo borra todo, documentado en `docs/`; tests con `terraform test`; ADR Terraform + Floci frente a kind/k3d o LocalStack, con kind como respaldo del módulo `eks`. Depende de: LAB-78."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Levantar el entorno completo con un solo comando (Priority: P1)

Como desarrollador, quiero partir de una máquina sin nada desplegado y, con un único flujo de aplicar, obtener
un clúster Kubernetes local con la plataforma base instalada y Argo CD sincronizando, para practicar el flujo
de un EKS real sin pagar nube.

**Why this priority**: Es el objetivo de la historia: sin un entorno reproducible de cero a Argo CD no hay
nada que practicar ni que las historias posteriores (despliegues GitOps, políticas, observabilidad) puedan usar.

**Independent Test**: En una máquina con solo Docker y las herramientas documentadas, ejecutar el flujo de
aplicar del entorno local y comprobar que el clúster responde, que los componentes de plataforma están sanos y
que Argo CD muestra sus aplicaciones sincronizadas.

**Acceptance Scenarios**:

1. **Given** una máquina sin emulador ni clúster en ejecución, **When** el desarrollador ejecuta el flujo de
   aplicar del entorno, **Then** se levanta el emulador de AWS, se crea el clúster y se instalan ingress,
   gestión de secretos externos, Argo CD y monitorización sin pasos manuales intermedios.
2. **Given** el flujo de aplicar terminado, **When** se consulta Argo CD, **Then** está accesible y sincroniza
   su configuración sin errores.
3. **Given** el entorno ya aplicado y sin cambios, **When** se vuelve a ejecutar el flujo de aplicar,
   **Then** no propone ni realiza ningún cambio.
4. **Given** el flujo de aplicar interrumpido a medias, **When** se vuelve a ejecutar, **Then** continúa
   hasta completar el entorno sin necesidad de limpiar a mano.
5. **Given** que no existe ninguna cuenta ni credencial real de AWS en la máquina, **When** se ejecuta el
   flujo, **Then** funciona igualmente y no contacta con la nube real.

---

### User Story 2 - Destruir y reconstruir el entorno sin residuos (Priority: P1)

Como desarrollador, quiero borrar todo el entorno con un solo comando y poder reconstruirlo igual, para
experimentar sin miedo a dejar el entorno en un estado inconsistente.

**Why this priority**: "Reconstruir el entorno con un solo comando" es la mitad de la propuesta de valor;
sin un borrado limpio, cada experimento deja basura que contamina el siguiente.

**Independent Test**: Aplicar el entorno, ejecutar el flujo de destruir y comprobar que no quedan clúster,
recursos emulados ni contenedores del emulador; volver a aplicar y obtener el mismo resultado.

**Acceptance Scenarios**:

1. **Given** el entorno aplicado, **When** el desarrollador ejecuta el flujo de destruir, **Then** se eliminan
   los componentes de plataforma, el clúster y los recursos emulados creados por la infraestructura.
2. **Given** el entorno destruido, **When** se vuelve a aplicar, **Then** queda en un estado equivalente al
   de la primera vez.
3. **Given** un componente que no se puede borrar a la primera, **When** falla el destruir, **Then** el error
   indica qué recurso falló y volver a ejecutarlo permite terminar el borrado.

---

### User Story 3 - Estado remoto con bloqueo y recursos organizados en módulos (Priority: P1)

Como desarrollador, quiero que el estado de la infraestructura viva en un almacenamiento remoto emulado con
bloqueo, y que el código esté dividido en módulos reutilizables con un entorno raíz por ambiente, para
practicar las mismas prácticas de colaboración y orden que en un entorno real.

**Why this priority**: El estado remoto con bloqueo y la separación en módulos son las prácticas que se
quieren aprender; también condicionan cómo se aplican las demás piezas (el estado debe existir antes que
el resto).

**Independent Test**: Preparar el estado remoto, lanzar dos operaciones simultáneas sobre el mismo entorno
y comprobar que la segunda es rechazada por el bloqueo; revisar que existen los cuatro módulos y que cada
ambiente tiene su propio entorno raíz.

**Acceptance Scenarios**:

1. **Given** un entorno sin estado previo, **When** se prepara el módulo de estado, **Then** se crea el
   almacenamiento remoto emulado que guardará el estado del resto de la infraestructura.
2. **Given** una operación en curso sobre un entorno, **When** se lanza otra operación sobre el mismo
   entorno, **Then** la segunda espera o falla indicando que el estado está bloqueado, sin corromperlo.
3. **Given** dos ambientes distintos, **When** se aplican, **Then** cada uno usa su propio estado y no
   interfiere con el otro.
4. **Given** la estructura del repositorio, **When** se revisa, **Then** existen los módulos `state`, `eks`,
   `secrets` y `platform`, y cada ambiente compone esos módulos desde su entorno raíz.

---

### User Story 4 - Secretos y parámetros de la aplicación sin valores en git (Priority: P2)

Como desarrollador, quiero que los secretos y parámetros de la aplicación se creen en el gestor de secretos
y en el almacén de parámetros emulados, y que la plataforma los lea desde ahí, para practicar el manejo de
secretos sin guardar nunca valores sensibles en el repositorio.

**Why this priority**: Sin valores en git es una exigencia de seguridad y habilita el flujo real de secretos
externos, pero el entorno ya es útil (P1) sin que las aplicaciones consuman secretos todavía.

**Independent Test**: Aplicar el entorno proporcionando los valores fuera del repositorio, comprobar que
los secretos y parámetros existen en el emulador y que un secreto sincronizado aparece en el clúster; revisar
el historial y los archivos versionados sin encontrar ningún valor.

**Acceptance Scenarios**:

1. **Given** valores de secretos proporcionados fuera del repositorio, **When** se aplica el entorno,
   **Then** los secretos existen en el gestor de secretos emulado y los parámetros en el almacén de
   parámetros emulado.
2. **Given** un secreto definido en el gestor emulado, **When** el componente de secretos externos lo
   sincroniza, **Then** queda disponible dentro del clúster para las aplicaciones.
3. **Given** un desarrollador que clona el repositorio sin proporcionar valores, **When** intenta aplicar,
   **Then** el flujo indica qué valores faltan y cómo proporcionarlos, sin usar valores por defecto sensibles.
4. **Given** el repositorio completo, **When** se escanea en busca de credenciales, **Then** no aparece
   ningún valor de secreto real (solo nombres, rutas y marcadores de posición).

---

### User Story 5 - Versiones fijadas, pruebas automáticas y decisión documentada (Priority: P2)

Como desarrollador, quiero que todas las versiones estén fijadas, que el código de infraestructura tenga
pruebas automáticas y que la decisión de usar esta herramienta quede documentada, para que el entorno sea
reproducible, verificable y su elección sea trazable.

**Why this priority**: Garantiza que el entorno de hoy sea el de dentro de seis meses y que los cambios se
puedan validar, pero no bloquea la primera ejecución del entorno.

**Independent Test**: Revisar que no hay versiones flotantes ni la etiqueta `latest`, que el archivo de
bloqueo de proveedores está versionado, ejecutar las pruebas y leer el ADR.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se revisan las versiones de la herramienta de infraestructura, de los
   proveedores, de los charts y de la imagen del emulador, **Then** todas están fijadas explícitamente, nunca
   a `latest` ni a rangos abiertos.
2. **Given** el repositorio, **When** se revisa el control de versiones, **Then** el archivo de bloqueo de
   dependencias de proveedores (`.terraform.lock.hcl`) está versionado.
3. **Given** los módulos, **When** se ejecuta `terraform test`, **Then** las pruebas pasan sin necesidad de
   cuenta de AWS real ni de recursos fuera de la máquina.
4. **Given** un cambio que rompe el contrato de un módulo, **When** se ejecutan las pruebas, **Then** fallan
   indicando qué comportamiento esperado se incumple.
5. **Given** el registro de ADR, **When** se busca esta decisión, **Then** existe un ADR que compara
   Terraform + Floci con kind/k3d y con LocalStack, explica la elección y describe el respaldo con kind.

---

### User Story 6 - Respaldo con kind si el EKS emulado falla (Priority: P3)

Como desarrollador, quiero poder sustituir el clúster emulado por uno de kind sin cambiar el resto de la
infraestructura, para no quedar bloqueado si el soporte de EKS del emulador falla.

**Why this priority**: Es un plan B de riesgo conocido; solo aporta valor si el camino principal falla.

**Independent Test**: Seleccionar el modo de respaldo, aplicar el entorno y comprobar que la plataforma
y Argo CD se instalan igual sobre el clúster de kind.

**Acceptance Scenarios**:

1. **Given** que el clúster emulado de EKS no se puede crear, **When** el desarrollador selecciona el modo
   de respaldo, **Then** el módulo `eks` crea un clúster con kind y devuelve su kubeconfig igual que en el
   camino principal.
2. **Given** el modo de respaldo activo, **When** se aplica el módulo de plataforma, **Then** se instalan los
   mismos componentes con las mismas versiones, sin cambios en el módulo.
3. **Given** el modo por defecto, **When** se aplica el entorno, **Then** se usa el EKS emulado y no se crea
   ningún clúster de kind.

---

### Edge Cases

- ¿Qué pasa si el emulador no está levantado o aún no está listo cuando empieza el flujo de aplicar? El flujo
  debe esperar a que esté listo o fallar con un mensaje claro, nunca dejar recursos a medias sin avisar.
- ¿Qué pasa si el puerto o el nombre de contenedor del emulador ya están ocupados? Debe fallar con un error
  que indique el conflicto.
- ¿Qué pasa si el emulador se reinicia y pierde su contenido mientras el estado remoto aún lo referencia? Debe
  documentarse cómo recuperar el entorno (destruir manualmente y reaplicar desde cero).
- ¿Qué pasa si el bloqueo del estado queda huérfano tras un fallo? Debe documentarse cómo liberarlo de forma
  segura.
- ¿Qué pasa si el clúster aún no es accesible cuando se instalan los componentes de plataforma? La
  instalación debe esperar a que el clúster esté listo en lugar de fallar de forma intermitente.
- ¿Qué pasa si falta una versión fijada (chart, proveedor, imagen) porque dejó de estar disponible? El flujo
  debe fallar de forma explícita en vez de resolver otra versión.
- ¿Qué pasa si el entorno se ejecuta en una máquina con recursos insuficientes? La documentación debe
  indicar los requisitos mínimos para que el fallo sea comprensible.
- ¿Qué pasa si se destruye el entorno mientras Argo CD aún está sincronizando? El borrado debe completarse
  sin dejar recursos huérfanos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El código de infraestructura MUST vivir en la carpeta `terraform/` del repositorio de
  infraestructura `shoplab-infra`, organizado en los módulos `state`, `eks`, `secrets` y `platform`, con un
  entorno raíz por ambiente que los compone.
- **FR-002**: El entorno MUST poder levantarse y apagarse mediante `docker compose` con un emulador de AWS
  de código abierto (licencia MIT) cuya imagen esté fijada a una versión concreta, nunca `latest`.
- **FR-003**: El proveedor de AWS MUST apuntar a los endpoints del emulador, de modo que ninguna operación
  contacte con la nube real y no se requiera cuenta ni credenciales reales de AWS.
- **FR-004**: El estado de la infraestructura MUST guardarse de forma remota en un bucket del almacenamiento
  emulado, con bloqueo nativo (`use_lockfile`) que impida operaciones concurrentes sobre el mismo estado.
- **FR-005**: El módulo `state` MUST crear el bucket de estado necesario antes de que el resto de módulos
  lo utilicen, y cada ambiente MUST tener un estado independiente.
- **FR-006**: El módulo `eks` MUST crear un clúster EKS emulado (el emulador lo levanta sobre k3s) y exponer
  su kubeconfig como output para que otros módulos y el desarrollador lo usen.
- **FR-007**: El módulo `secrets` MUST crear los secretos de la aplicación en Secrets Manager y los parámetros
  en SSM, con los valores recibidos como entrada externa al repositorio.
- **FR-008**: Ningún valor de secreto MUST estar versionado en git; el repositorio solo puede contener nombres,
  rutas y marcadores de posición, y los archivos que pudieran contener valores MUST estar excluidos del
  control de versiones.
- **FR-009**: El módulo `platform` MUST instalar, mediante los proveedores `helm` y `kubernetes`, ingress-nginx,
  External Secrets Operator (configurado para leer de Secrets Manager), Argo CD y kube-prometheus-stack.
- **FR-010**: El módulo `platform` MUST esperar a que el clúster esté listo y respetar el orden de
  dependencias entre componentes, de forma que un `apply` desde cero no falle por condiciones de carrera.
- **FR-011**: Las versiones de Terraform, de los proveedores y de los charts MUST estar fijadas, y el archivo
  `.terraform.lock.hcl` MUST estar versionado en git.
- **FR-012**: Un único `terraform apply` MUST llevar el entorno de cero a Argo CD sincronizando, y un único
  `terraform destroy` MUST eliminar todo lo creado, sin dejar recursos huérfanos.
- **FR-013**: Aplicar de nuevo un entorno ya aplicado y sin cambios MUST ser idempotente (sin cambios
  pendientes).
- **FR-014**: El procedimiento completo (requisitos previos, orden de arranque, apply, destroy, recuperación
  de bloqueos huérfanos y de reinicio del emulador, y selección del respaldo con kind) MUST estar documentado
  en `docs/`.
- **FR-015**: Los módulos MUST tener pruebas ejecutables con `terraform test` que se puedan correr en local sin
  cuenta de AWS real.
- **FR-016**: MUST existir un ADR que compare Terraform + Floci con kind/k3d y con LocalStack, justifique la
  elección y recoja que el módulo `eks` puede usar kind como respaldo si el EKS del emulador falla.
- **FR-017**: El módulo `eks` MUST ofrecer un modo de respaldo con kind que exponga el mismo contrato de
  salida (kubeconfig) para que `platform` funcione sin cambios; el modo por defecto MUST ser el EKS emulado.
- **FR-018**: Todo el entorno MUST funcionar en local sobre Docker, sin depender de servicios externos de
  pago ni de una cuenta de AWS.

### Key Entities *(include if feature involves data)*

- **Emulador de AWS**: servicio local que simula los servicios de AWS necesarios (almacenamiento, secretos,
  parámetros, EKS); tiene versión fijada y endpoints a los que apuntan los proveedores.
- **Ambiente**: instancia aislada de la infraestructura (p. ej. local); posee su entorno raíz, su estado y su
  clúster.
- **Estado remoto**: registro de los recursos gestionados, guardado en un bucket emulado y protegido por un
  bloqueo.
- **Clúster Kubernetes**: clúster emulado (o de respaldo) sobre el que se instala la plataforma; su kubeconfig
  es el contrato de salida del módulo `eks`.
- **Secreto / Parámetro**: valor de configuración de la aplicación que vive en el gestor de secretos o en el
  almacén de parámetros emulados y se sincroniza al clúster; su valor nunca está en git.
- **Componente de plataforma**: ingress, gestión de secretos externos, Argo CD y monitorización, instalados
  con versión fijada.
- **ADR**: registro de la decisión de herramienta y alternativas consideradas.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador con las herramientas documentadas obtiene, desde una máquina sin entorno
  previo, un clúster con la plataforma completa y Argo CD sincronizando ejecutando un único flujo de aplicar,
  sin ningún paso manual intermedio.
- **SC-002**: El 100 % de los componentes de plataforma (ingress, secretos externos, Argo CD y monitorización)
  aparece sano tras el primer aplicar, en 3 ejecuciones consecutivas desde cero.
- **SC-003**: Un único flujo de destruir elimina el 100 % de los recursos creados; tras destruir y volver a
  aplicar, el entorno queda equivalente al original.
- **SC-004**: Aplicar un entorno sin cambios no produce ninguna modificación (0 cambios pendientes).
- **SC-005**: Ninguna operación del entorno contacta con la nube real ni requiere credenciales reales: el
  flujo completo funciona con la red hacia AWS bloqueada.
- **SC-006**: Un escaneo de credenciales sobre el repositorio y su historial de esta historia encuentra 0
  valores de secreto reales.
- **SC-007**: El 100 % de las versiones (herramienta, proveedores, charts e imagen del emulador) está fijado
  y el archivo de bloqueo está versionado; 0 usos de `latest` o rangos abiertos.
- **SC-008**: Las pruebas de los módulos se ejecutan en local sin recursos externos y todas pasan en la
  rama antes de fusionar.
- **SC-009**: Una segunda operación concurrente sobre el mismo estado es rechazada en el 100 % de los casos
  sin corromper el estado.
- **SC-010**: Un desarrollador nuevo reconstruye el entorno siguiendo solo la documentación de `docs/`, sin
  ayuda adicional, en una sola sesión.

## Assumptions

- La historia nombra el repositorio `shoplab-infra`, que no existe localmente; la especificación se registra
  en `specs/` de este repositorio, donde viven las de todas las historias LAB, y el código resultante se
  entregará en `shoplab-infra`. Si ese repositorio aún no existe, crearlo es parte del trabajo previo a la
  implementación.
- Los "ambientes" iniciales se reducen a uno local; la estructura admite añadir más sin cambiar los módulos.
- El desarrollador dispone de Docker con recursos suficientes para el emulador y un clúster Kubernetes local
  (los requisitos mínimos se documentan como parte de FR-014).
- Los valores de secretos se proporcionan por variables de entorno o un archivo local excluido de git; no se
  usa ningún gestor de secretos externo adicional.
- Las aplicaciones de ShopLab (backend, frontend, ingest) y su despliegue vía Argo CD quedan fuera del alcance;
  aquí solo se deja Argo CD instalado y sincronizando su configuración inicial.
- Las políticas de admisión (Kyverno, LAB-83), la observabilidad de aplicaciones y los dashboards quedan fuera
  del alcance; solo se instala la pila de monitorización base.
- Las imágenes Docker de las aplicaciones provienen de LAB-78 (GHCR); esta historia depende de ella pero no
  las construye.
- El flujo se ejecuta manualmente por el desarrollador; ejecutar `terraform` en CI queda fuera del alcance.
- Se mantiene la convención del repositorio: identificadores en inglés, documentación y ADR en español.
