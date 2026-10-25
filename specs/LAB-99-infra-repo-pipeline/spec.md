# Feature Specification: Pipeline del repo de infraestructura

**Feature Branch**: `chore/LAB-99-infra-repo-pipeline`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-99 (F0-16) Como desarrollador, quiero que cada cambio en `shoplab-infra` se valide y se pruebe en CI antes de llegar a `main`. Criterios: workflow con `terraform fmt -check`, `validate`, TFLint y `terraform test`; `terraform plan` contra Floci (service container del runner) publicado como comentario del PR; `kustomize build` de cada overlay (`dev`, `staging`, `prod`) + kubeconform; políticas que fallan el PR (imágenes con `:latest` o sin tag, `repoURL` de plantilla `CAMBIA-ESTE-OWNER`, `targetRevision` distinto de `main`); Trivy config sobre Terraform y manifiestos con SARIF en la pestaña Security; prueba de humo con `terraform apply` sobre Floci, espera a que Argo CD quede Synced y Healthy, y destruye; ruleset en `main` de `shoplab-infra` igual que en el repo de la app. Funciona en local con `make check`. Depende de: LAB-98."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Validación estática de Terraform y manifiestos en cada PR (Priority: P1)

Como desarrollador, quiero que cada pull request a `shoplab-infra` ejecute automáticamente las
validaciones de formato, sintaxis, lint y pruebas de Terraform, y que construya cada overlay de
Kustomize (`dev`, `staging`, `prod`) validando los manifiestos resultantes, para detectar errores
de infraestructura antes de fusionar.

**Why this priority**: Es el mínimo que hace útil el pipeline: sin esta validación, un error de
sintaxis o un overlay roto llega a `main` y rompe el despliegue.

**Independent Test**: Abrir un PR con un archivo Terraform mal formateado y otro con un overlay
inválido; comprobar que el PR queda en rojo señalando cada fallo. Corregirlos y comprobar que
queda en verde.

**Acceptance Scenarios**:

1. **Given** un PR con código Terraform sin formatear, **When** corre el pipeline, **Then** falla
   en el paso de formato indicando los archivos afectados.
2. **Given** un PR con una configuración Terraform inválida o que incumple las reglas de lint,
   **When** corre el pipeline, **Then** falla en validación o lint con el detalle del error.
3. **Given** un PR que rompe una prueba de Terraform, **When** corre el pipeline, **Then** falla
   en el paso de pruebas.
4. **Given** un PR con un overlay (`dev`, `staging` o `prod`) que no construye o genera manifiestos
   que no cumplen el esquema de Kubernetes, **When** corre el pipeline, **Then** falla indicando
   el overlay y el recurso afectado.
5. **Given** un PR sin errores, **When** corre el pipeline, **Then** todos los pasos de validación
   pasan en verde.

---

### User Story 2 - Políticas de manifiestos que fallan el PR (Priority: P1)

Como desarrollador, quiero que el pipeline rechace manifiestos con imágenes sin versión fija, con
valores de plantilla sin sustituir o apuntando a una revisión distinta de `main`, para evitar
despliegues no reproducibles o mal enlazados.

**Why this priority**: Estas reglas protegen la reproducibilidad del despliegue por GitOps; un
`:latest` o un `repoURL` de plantilla provoca fallos silenciosos o despliegues inconsistentes.

**Independent Test**: Introducir en un overlay, por separado, una imagen con `:latest`, una imagen
sin tag, un `repoURL` con `CAMBIA-ESTE-OWNER` y un `targetRevision` distinto de `main`; comprobar
que cada uno hace fallar el PR con un mensaje que nombra la regla incumplida.

**Acceptance Scenarios**:

1. **Given** un manifiesto con una imagen `:latest`, **When** corre el pipeline, **Then** el PR
   falla indicando la imagen y el archivo.
2. **Given** un manifiesto con una imagen sin tag, **When** corre el pipeline, **Then** el PR
   falla indicando la imagen y el archivo.
3. **Given** un manifiesto con `repoURL` que contiene `CAMBIA-ESTE-OWNER`, **When** corre el
   pipeline, **Then** el PR falla indicando que el valor de plantilla no fue sustituido.
4. **Given** una aplicación de Argo CD con `targetRevision` distinto de `main`, **When** corre el
   pipeline, **Then** el PR falla indicando la aplicación y la revisión encontrada.
5. **Given** manifiestos con imágenes de tag fijo, `repoURL` real y `targetRevision: main`,
   **When** corre el pipeline, **Then** las políticas pasan.

---

### User Story 3 - Plan de Terraform contra Floci publicado en el PR (Priority: P2)

Como desarrollador, quiero que el pipeline ejecute `terraform plan` contra una instancia de Floci
levantada en el runner y publique el resultado como comentario del PR, para revisar el efecto de
un cambio de infraestructura sin desplegar nada real.

**Why this priority**: Aporta visibilidad del impacto del cambio al revisor, pero el PR ya está
protegido por las validaciones estáticas de las historias anteriores.

**Independent Test**: Abrir un PR que añade un recurso Terraform y comprobar que aparece un
comentario con el plan; hacer un push adicional y comprobar que el comentario se actualiza en vez
de duplicarse.

**Acceptance Scenarios**:

1. **Given** un PR con cambios en Terraform, **When** corre el pipeline, **Then** se levanta Floci
   en el runner, se ejecuta el plan y se publica un comentario en el PR con el resultado.
2. **Given** un PR que ya tiene el comentario del plan, **When** se sube un nuevo commit, **Then**
   el mismo comentario se actualiza y no se crea otro.
3. **Given** un plan que falla (error de configuración o Floci no disponible), **When** corre el
   pipeline, **Then** el paso falla y el comentario refleja el error.
4. **Given** un PR de un fork o sin permiso de escritura en comentarios, **When** corre el
   pipeline, **Then** el plan se ejecuta igualmente y el resultado queda en el resumen del job.

---

### User Story 4 - Escaneo de seguridad de configuración con Trivy (Priority: P2)

Como desarrollador, quiero que Trivy analice la configuración de Terraform y de los manifiestos y
suba sus hallazgos a la pestaña Security del repositorio, para detectar malas configuraciones de
seguridad antes de fusionar.

**Why this priority**: Es una barrera de seguridad importante, pero independiente del resto de
validaciones y ya resuelta de forma análoga en el repo de la app (LAB-76).

**Independent Test**: Introducir una mala configuración conocida (por ejemplo, un recurso con
acceso público no justificado) y comprobar que el PR falla y que el hallazgo aparece en la pestaña
Security.

**Acceptance Scenarios**:

1. **Given** una configuración con un hallazgo de severidad que bloquea, **When** corre el
   pipeline, **Then** el PR falla y el hallazgo aparece en la pestaña Security en formato SARIF.
2. **Given** una configuración sin hallazgos bloqueantes, **When** corre el pipeline, **Then** el
   paso pasa y el reporte SARIF se sube igualmente.
3. **Given** un hallazgo aceptado de forma explícita, **When** corre el pipeline, **Then** no
   bloquea el PR y la excepción queda documentada en el repositorio.

---

### User Story 5 - Prueba de humo: apply, Argo CD sano y destroy (Priority: P2)

Como desarrollador, quiero que el pipeline aplique la infraestructura completa sobre Floci en el
runner, espere a que Argo CD quede Synced y Healthy, y luego la destruya, para confirmar que lo
que se fusiona realmente se despliega de punta a punta.

**Why this priority**: Es la prueba más fuerte de que el repositorio funciona, pero es la más lenta
y depende de que las demás validaciones ya hayan pasado.

**Independent Test**: Ejecutar el pipeline sobre `main` sin cambios y comprobar que apply, espera
y destroy terminan en verde; romper a propósito un manifiesto de Argo CD y comprobar que la espera
agota su tiempo y el paso falla.

**Acceptance Scenarios**:

1. **Given** una infraestructura válida, **When** corre la prueba de humo, **Then** el apply
   termina, Argo CD alcanza Synced y Healthy dentro del tiempo límite y el destroy finaliza.
2. **Given** una aplicación de Argo CD que nunca queda Healthy, **When** se agota el tiempo
   límite, **Then** el paso falla mostrando el estado y los eventos de las aplicaciones.
3. **Given** una prueba de humo que falla en cualquier punto, **When** termina el job, **Then** el
   destroy y la limpieza del entorno se ejecutan igualmente.

---

### User Story 6 - Misma protección de `main` que el repo de la app (Priority: P1)

Como desarrollador, quiero que `main` de `shoplab-infra` tenga el mismo ruleset que el repo de la
app (sin push directo ni force push, PR obligatorio, squash merge con clave LAB en el título), con
los checks de este pipeline como requeridos, para que nada llegue a `main` sin pasar por ellos.

**Why this priority**: Sin el ruleset, el pipeline es opcional y se puede saltar con un push
directo; cierra el objetivo de la historia.

**Independent Test**: Intentar un push directo y un force push a `main` de `shoplab-infra` y
comprobar el rechazo; abrir un PR con un check en rojo y comprobar que no se puede fusionar.

**Acceptance Scenarios**:

1. **Given** un commit local, **When** se hace push directo o force push a `main`, **Then** el
   remoto lo rechaza.
2. **Given** un PR con algún check requerido en rojo o pendiente, **When** se intenta fusionar,
   **Then** la fusión está bloqueada.
3. **Given** un PR con todos los checks en verde, **When** se fusiona, **Then** solo es posible
   mediante squash merge con título válido.
4. **Given** el ruleset de `shoplab-infra`, **When** se compara con el del repo de la app, **Then**
   las reglas son equivalentes y las diferencias (checks requeridos propios) están documentadas.

---

### User Story 7 - Los mismos controles en local con `make check` (Priority: P2)

Como desarrollador, quiero ejecutar en mi máquina los mismos comandos que corre el pipeline
mediante `make check`, para obtener el mismo resultado antes de abrir el PR.

**Why this priority**: Acorta el ciclo de retroalimentación y evita commits de "arreglar CI", pero
el pipeline funciona sin ello.

**Independent Test**: Ejecutar `make check` en un clon limpio con las herramientas instaladas y
comprobar que reproduce los mismos pasos y resultados que el pipeline en CI.

**Acceptance Scenarios**:

1. **Given** un clon limpio con las herramientas requeridas, **When** se ejecuta `make check`,
   **Then** corren los mismos controles estáticos, de políticas y de seguridad que en CI.
2. **Given** un cambio que falla en CI por una política, **When** se ejecuta `make check`,
   **Then** falla localmente con el mismo mensaje.
3. **Given** una herramienta requerida sin instalar, **When** se ejecuta `make check`, **Then**
   se indica cuál falta y cómo instalarla.

---

### Edge Cases

- PR que solo toca documentación o archivos sin relación con infraestructura: los checks
  requeridos deben reportar estado (no quedar pendientes para siempre) para no bloquear la fusión.
- Floci tarda en arrancar o no responde: el pipeline espera a que esté listo con un tiempo límite y
  falla con un mensaje claro, sin colgarse.
- Ejecuciones concurrentes sobre el mismo PR: la más reciente cancela a la anterior para evitar
  comentarios y estados contradictorios.
- Un overlay vacío o inexistente (`dev`, `staging` o `prod`): se trata como fallo, no como éxito.
- Imagen referenciada por digest (`@sha256:...`) en lugar de tag: es válida para la política de
  imágenes.
- `targetRevision` con valores como `HEAD` o una rama distinta de `main`: se rechaza por no ser
  exactamente `main`.
- Falsos positivos de Trivy: solo se aceptan mediante una excepción explícita y versionada, nunca
  desactivando el paso.
- La prueba de humo deja recursos huérfanos en Floci si el job se cancela: debe haber limpieza
  garantizada al terminar.
- Comentario del plan demasiado largo para el límite de tamaño de un comentario: se trunca
  indicando dónde ver el plan completo.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El pipeline de `shoplab-infra` MUST ejecutarse en cada pull request hacia `main` y en
  cada push a `main`.
- **FR-002**: El pipeline MUST verificar el formato de Terraform sin modificar archivos y fallar si
  hay archivos sin formatear.
- **FR-003**: El pipeline MUST validar la sintaxis y consistencia de la configuración de Terraform
  y ejecutar el linter de Terraform, fallando ante cualquier error.
- **FR-004**: El pipeline MUST ejecutar las pruebas de Terraform y fallar si alguna no pasa.
- **FR-005**: El pipeline MUST levantar Floci como servicio auxiliar del runner y esperar a que
  esté disponible antes de usarlo, con un tiempo límite.
- **FR-006**: El pipeline MUST ejecutar `terraform plan` contra Floci y publicar su resultado como
  comentario del PR, actualizando el comentario existente en lugar de crear uno nuevo en cada
  ejecución.
- **FR-007**: El pipeline MUST construir cada overlay (`dev`, `staging`, `prod`) de Kustomize y
  validar los manifiestos generados contra el esquema de Kubernetes con kubeconform.
- **FR-008**: El pipeline MUST fallar si algún overlay no existe, no construye o genera cero
  recursos.
- **FR-009**: El pipeline MUST fallar si algún manifiesto construido contiene una imagen con el tag
  `:latest` o sin tag (se aceptan imágenes con tag fijo o digest).
- **FR-010**: El pipeline MUST fallar si algún `repoURL` contiene el valor de plantilla
  `CAMBIA-ESTE-OWNER`.
- **FR-011**: El pipeline MUST fallar si algún `targetRevision` de las aplicaciones de Argo CD es
  distinto de `main`.
- **FR-012**: Cada política que falle MUST indicar la regla incumplida, el archivo o recurso
  afectado y el valor encontrado.
- **FR-013**: El pipeline MUST ejecutar Trivy en modo configuración sobre Terraform y manifiestos,
  fallar ante hallazgos de la severidad acordada y subir el resultado en formato SARIF a la pestaña
  Security del repositorio, incluso cuando el paso falla.
- **FR-014**: Las excepciones de Trivy MUST declararse en un archivo versionado del repositorio con
  su justificación.
- **FR-015**: El pipeline MUST ejecutar una prueba de humo que aplique la infraestructura sobre
  Floci, espere a que Argo CD quede Synced y Healthy dentro de un tiempo límite, y destruya la
  infraestructura.
- **FR-016**: La prueba de humo MUST ejecutar el destroy y la limpieza aunque un paso anterior
  falle o el job se cancele, y MUST mostrar el estado de las aplicaciones de Argo CD cuando falle.
- **FR-017**: El ruleset de `main` en `shoplab-infra` MUST bloquear push directo, force push y
  borrado, exigir PR, rama actualizada y squash merge con título válido, igual que el repo de la
  app, sin permitir bypass.
- **FR-018**: El ruleset MUST exigir como checks requeridos los trabajos de este pipeline,
  garantizando que siempre reporten estado aun cuando el PR no toque infraestructura.
- **FR-019**: El repositorio MUST ofrecer `make check`, que ejecute en local los mismos controles
  estáticos, de políticas y de seguridad que el pipeline, e indique qué herramienta falta si no
  está instalada.
- **FR-020**: Las versiones de las herramientas del pipeline (Terraform, TFLint, kustomize,
  kubeconform, Trivy, Floci) MUST estar fijadas y ser las mismas en CI y en `make check`.
- **FR-021**: El pipeline MUST cancelar ejecuciones obsoletas del mismo PR y usar los permisos
  mínimos necesarios por trabajo.
- **FR-022**: El pipeline y sus requisitos MUST documentarse en el README de `shoplab-infra`,
  incluida la lista de checks requeridos y cómo reproducirlos en local.

### Key Entities *(include if feature involves data)*

- **Pipeline de CI de `shoplab-infra`**: conjunto de trabajos (validación, políticas, plan,
  seguridad, prueba de humo) que se ejecutan por PR y push a `main`; produce un estado por trabajo.
- **Overlay de Kustomize**: variante de manifiestos por entorno (`dev`, `staging`, `prod`) que se
  construye y valida de forma independiente.
- **Política de manifiestos**: regla verificable sobre los manifiestos construidos (imágenes
  versionadas, sin plantillas sin sustituir, `targetRevision` igual a `main`).
- **Entorno Floci efímero**: emulación de servicios cloud levantada en el runner para plan y
  prueba de humo; se crea y se destruye en cada ejecución.
- **Ruleset de `main`**: reglas de protección de la rama, con la lista de checks requeridos.
- **Reporte de seguridad (SARIF)**: resultado de Trivy publicado en la pestaña Security.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100% de los PR a `main` de `shoplab-infra` ejecutan todos los controles
  requeridos; ninguno se puede fusionar con un control en rojo o pendiente.
- **SC-002**: Cada una de las 4 violaciones de política (`:latest`, sin tag, `repoURL` de
  plantilla, `targetRevision` distinto de `main`) introducida a propósito hace fallar el PR en el
  100% de los intentos, con un mensaje que identifica la regla y el archivo.
- **SC-003**: Los controles estáticos (formato, validación, lint, pruebas, overlays y políticas)
  entregan resultado en menos de 10 minutos desde el push.
- **SC-004**: La prueba de humo completa (apply, Argo CD sano, destroy) termina en menos de 20
  minutos y no deja recursos remanentes en el 100% de las ejecuciones, incluso si falla.
- **SC-005**: El plan de Terraform aparece como comentario en el PR en menos de 10 minutos y
  existe un único comentario por PR tras múltiples pushes.
- **SC-006**: Un desarrollador con las herramientas instaladas obtiene en local con `make check` el
  mismo veredicto que el pipeline en CI en el 100% de los casos probados.
- **SC-007**: Los hallazgos de Trivy aparecen en la pestaña Security tras cada ejecución, y un
  push directo o force push a `main` es rechazado el 100% de las veces.

## Assumptions

- LAB-98 (Terraform + Floci + EKS) está completada: `shoplab-infra` contiene la configuración de
  Terraform, los overlays de Kustomize y las aplicaciones de Argo CD que este pipeline valida.
- Floci está disponible como imagen de contenedor utilizable como service container en los runners
  de GitHub Actions, sin credenciales cloud reales.
- Se usan runners alojados por GitHub (Linux); no se requieren runners propios.
- El repo de la app (LAB-15, LAB-75, LAB-76, LAB-77) es la referencia de estilo del pipeline y del
  ruleset; los checks requeridos propios de `shoplab-infra` difieren solo por el contenido.
- La severidad de Trivy que bloquea el PR es HIGH y CRITICAL, alineada con LAB-76.
- Los overlays objetivo son exactamente `dev`, `staging` y `prod`; añadir otros queda fuera de
  alcance.
- El comentario del plan se publica solo cuando el token del PR tiene permiso de escritura; en PR
  desde forks se usa el resumen del job.
- Esta historia no despliega a ningún entorno real (cloud ni Kubernetes productivo); todo ocurre
  sobre Floci en el runner.
- Fuera de alcance: despliegue continuo, gestión de estado remoto de Terraform y notificaciones
  fuera de GitHub.
