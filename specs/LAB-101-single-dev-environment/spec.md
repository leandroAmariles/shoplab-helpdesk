# Feature Specification: Consolidar shoplab-infra en un solo ambiente dev

**Feature Branch**: `chore/LAB-101-single-dev-environment`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-101 (F0-20) Como desarrollador, quiero un solo ambiente dev para que el laboratorio sea simple de operar en mi máquina, sin mantener staging ni prod. Criterios: se eliminan los overlays `staging` y `prod` creados en LAB-80 (queda `base/` + overlay `dev`); se eliminan las Applications de Argo CD de staging y prod si existen; el pipeline de `shoplab-infra` (LAB-99) solo construye y valida el overlay `dev`; Terraform (LAB-98) deja un único entorno raíz `envs/dev`; README y `docs/` actualizados y el ADR registra la decisión y cómo se añadirían más ambientes (otro overlay + otra Application) si hiciera falta; `terraform apply` y Argo CD siguen llevando dev a Synced y Healthy después del cambio. Depende de: LAB-79."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un solo ambiente definido en el repositorio de infraestructura (Priority: P1)

Como desarrollador, quiero que `shoplab-infra` defina únicamente el ambiente `dev`, para no
mantener, revisar ni razonar sobre configuración de `staging` y `prod` que nunca se ejecuta en mi
máquina.

**Why this priority**: Es el núcleo de la historia. Hoy `staging` y `prod` se definen, se construyen
y se validan en cada cambio, pero el clúster local solo puede alojar un ambiente (el overlay
`argocd-local` ya retira sus Applications). Es costo de mantenimiento sin ningún uso.

**Independent Test**: Listar los overlays y las Applications del repositorio y comprobar que solo
existen `base/`, el overlay `dev` y la Application de `dev`; construir el overlay `dev` y comprobar
que el resultado es idéntico al de antes del cambio.

**Acceptance Scenarios**:

1. **Given** el repositorio tras el cambio, **When** se listan los overlays de manifiestos,
   **Then** existen únicamente `base/` y el overlay `dev`; los overlays `staging` y `prod` ya no
   existen.
2. **Given** el repositorio tras el cambio, **When** se listan las Applications de Argo CD
   versionadas, **Then** solo existe la de `dev` (más la raíz que la gestiona) y no hay referencias
   a `shoplab-staging` ni a `shoplab-prod`.
3. **Given** el overlay `dev` antes y después del cambio, **When** se construye cada uno,
   **Then** los manifiestos resultantes son idénticos: el cambio no altera lo que se despliega en
   `dev`.
4. **Given** el overlay `dev`, **When** el despliegue continuo (LAB-81) fija un nuevo tag de imagen,
   **Then** sigue funcionando sin cambios, porque solo escribe en ese overlay.

---

### User Story 2 - El pipeline valida solo dev (Priority: P1)

Como desarrollador, quiero que el pipeline de `shoplab-infra` construya y valide únicamente el
overlay `dev`, para que las verificaciones sean más rápidas y no fallen por ambientes que ya no
existen.

**Why this priority**: Si el pipeline sigue buscando `staging` y `prod` después de eliminarlos,
todo cambio posterior quedaría bloqueado; si se relaja de más, se pierde la protección de `dev`.

**Independent Test**: Abrir un pull request con el cambio y comprobar que todas las verificaciones
obligatorias pasan, que la construcción y la validación de manifiestos cubren solo `dev` y que las
políticas siguen rechazando los manifiestos inválidos de sus pruebas.

**Acceptance Scenarios**:

1. **Given** un pull request sobre `shoplab-infra`, **When** corre el pipeline, **Then** construye y
   valida solo el overlay `dev`, y no intenta construir `staging` ni `prod`.
2. **Given** el overlay `dev` con un defecto (por ejemplo, una imagen con `:latest`), **When** corre
   el pipeline, **Then** las políticas existentes lo rechazan igual que antes del cambio.
3. **Given** la protección de la rama `main`, **When** el pipeline cambia su forma, **Then** las
   verificaciones exigidas por la protección siguen existiendo con el mismo nombre y un pull
   request puede fusionarse.
4. **Given** las pruebas de las propias comprobaciones del repositorio (políticas, estructura,
   fijado de versiones), **When** corren, **Then** pasan sin depender de que existan `staging` ni
   `prod`.

---

### User Story 3 - Terraform con un único entorno raíz `dev` (Priority: P1)

Como desarrollador, quiero que Terraform deje un único entorno raíz `envs/dev`, para que el nombre
del entorno coincida con el del ambiente que levanta y el repositorio no sugiera que hay otros.

**Why this priority**: Es un criterio de aceptación explícito y, sin él, la documentación y los
nombres del estado seguirían hablando de un entorno `local` que no corresponde al ambiente `dev`.

**Independent Test**: Comprobar que bajo `terraform/envs/` solo existe `dev`; ejecutar
`terraform apply` desde cero y comprobar que el clúster local queda con el ambiente `dev` en
Synced y Healthy.

**Acceptance Scenarios**:

1. **Given** el repositorio tras el cambio, **When** se listan los entornos raíz de Terraform,
   **Then** existe únicamente `envs/dev` y ya no existe `envs/local`.
2. **Given** un clúster local vacío, **When** se ejecuta `terraform apply` sobre `envs/dev`,
   **Then** Argo CD crea la Application de `dev` y esta llega a Synced y Healthy sin pasos
   manuales.
3. **Given** el entorno raíz, **When** se inspeccionan las referencias a su ruta (Application raíz,
   scripts, pruebas, documentación), **Then** todas apuntan a `envs/dev` y ninguna a `envs/local`.
4. **Given** el entorno raíz, **When** se revisan sus variables, **Then** ya no ofrece crear
   ambientes adicionales de la aplicación: el único ambiente es `dev`.
5. **Given** la validación estática y las pruebas de Terraform, **When** corren, **Then** pasan
   sobre `envs/dev`.

---

### User Story 4 - Decisión registrada y documentación coherente (Priority: P2)

Como desarrollador, quiero que el README, los documentos y un ADR cuenten la misma historia de un
solo ambiente y expliquen cómo se añadiría otro si hiciera falta, para que nadie busque `staging`
o `prod` ni reinvente cómo agregarlos.

**Why this priority**: Sin el registro, la decisión parece un recorte accidental y la próxima
persona (o yo mismo) repetiría el análisis. Es documentación: no cambia el comportamiento.

**Independent Test**: Buscar `staging` y `prod` en los README y documentos de ambos repositorios y
comprobar que solo aparecen en el ADR de la decisión y en el procedimiento para añadir ambientes;
leer el ADR y comprobar que contiene decisión, motivo, consecuencias y el procedimiento.

**Acceptance Scenarios**:

1. **Given** el README y los documentos de `shoplab-infra`, **When** se leen, **Then** describen un
   único ambiente `dev` y no presentan `staging` ni `prod` como ambientes existentes o validados.
2. **Given** los documentos de `shoplab-helpdesk` que hablan del despliegue (README, `docs/` y
   ADRs vigentes), **When** se leen, **Then** no afirman que existan `staging` ni `prod`; las
   decisiones históricas se conservan y se marcan como parcialmente reemplazadas por el nuevo ADR.
3. **Given** el registro de ADRs, **When** se consulta, **Then** existe un ADR nuevo que registra la
   decisión de un solo ambiente, su motivo y sus consecuencias, enlazado desde el índice.
4. **Given** el ADR, **When** se lee la sección de cómo añadir un ambiente, **Then** describe los
   pasos mínimos (otro overlay sobre `base/`, otra Application de Argo CD y, si aplica, otro
   entorno raíz de Terraform) sin implementarlos.

---

### User Story 5 - Dev sigue funcionando tras el cambio (Priority: P1)

Como desarrollador, quiero comprobar que, después de consolidar, un `terraform apply` y Argo CD
siguen llevando `dev` a Synced y Healthy, para tener la certeza de que la limpieza no rompió el
laboratorio.

**Why this priority**: Es el criterio que demuestra que el cambio es seguro; sin esta validación en
vivo, borrar archivos podría haber roto el arranque o la sincronización.

**Independent Test**: Destruir y volver a crear el clúster local con la configuración consolidada y
comprobar que `dev` llega a Synced y Healthy y que un despliegue continuo posterior (LAB-81) sigue
actualizándolo.

**Acceptance Scenarios**:

1. **Given** la configuración consolidada, **When** se levanta el clúster local desde cero con el
   flujo documentado, **Then** Argo CD sincroniza `dev` y las Applications quedan en Synced y
   Healthy.
2. **Given** un clúster local que ya estaba funcionando con la configuración anterior, **When** se
   destruye con el procedimiento documentado y se recrea con la configuración consolidada,
   **Then** `dev` llega a Synced y Healthy y no quedan recursos ni estado del entorno anterior.
3. **Given** `dev` consolidado, **When** se fusiona a `main` un cambio de la aplicación, **Then** el
   despliegue continuo de LAB-81 actualiza `dev` como antes.
4. **Given** el resultado de la validación, **When** se registra en la carpeta de la especificación,
   **Then** queda constancia de lo ejecutado y de lo observado.

---

### Edge Cases

- **Clúster con `staging`/`prod` ya creados**: si alguna vez se sincronizaron en un clúster, su
  eliminación del repositorio debe quedar documentada (qué ocurre con sus recursos y cómo
  limpiarlos) para no dejar Applications huérfanas ni namespaces colgados. En el laboratorio local
  ya no se crean.
- **Poda automática de Argo CD**: eliminar las Applications del repositorio no debe arrastrar ni
  afectar a la Application de `dev` ni a los componentes de plataforma.
- **Estado de Terraform existente**: renombrar el entorno raíz cambia el nombre del clúster y del
  estado, por lo que el entorno anterior no se adopta: debe destruirse antes de cambiar de
  configuración para no dejar estado huérfano en el emulador, y el procedimiento debe documentarse.
- **Protección de la rama `main`**: renombrar o eliminar trabajos del pipeline puede dejar
  verificaciones exigidas que nunca se reportan y bloquear todos los pull requests.
- **Políticas y pruebas con datos de ejemplo**: las pruebas de las comprobaciones pueden usar
  nombres de ambiente en sus datos; deben seguir cubriendo los casos válidos e inválidos.
- **Despliegue continuo (LAB-81)**: el contrato con `shoplab-helpdesk` (ruta del overlay `dev`,
  forma de la fijación de imágenes) no debe cambiar.
- **Documentos históricos**: las especificaciones y ADRs de historias anteriores describen
  decisiones tomadas en su momento; no se reescriben, se enlazan al nuevo ADR cuando corresponde.
- **Valores propios de cada ambiente**: lo que solo existía para `staging`/`prod` (hosts de ejemplo,
  rutas de secretos, recursos mayores, escalado) se elimina con ellos; no debe quedar en `base/` ni
  en `dev`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio de infraestructura MUST definir un único ambiente de aplicación, `dev`,
  compuesto por `base/` y el overlay `dev`; los overlays `staging` y `prod` MUST eliminarse.
- **FR-002**: Las Applications de Argo CD de `staging` y `prod` MUST eliminarse del repositorio,
  junto con cualquier mecanismo cuyo único fin fuera ocultarlas en el clúster local.
- **FR-003**: Los manifiestos que resultan de construir el overlay `dev` MUST ser equivalentes a los
  de antes del cambio.
- **FR-004**: El pipeline de `shoplab-infra` MUST construir y validar únicamente el overlay `dev`,
  manteniendo las mismas políticas, y sus verificaciones obligatorias MUST seguir reportándose con
  los nombres que exige la protección de `main`.
- **FR-005**: Las comprobaciones y pruebas propias del repositorio (estructura, políticas, fijado de
  versiones, integración) MUST pasar sin requerir que existan `staging` ni `prod`.
- **FR-006**: Terraform MUST dejar un único entorno raíz, `envs/dev`; `envs/local` MUST dejar de
  existir y toda referencia a su ruta MUST actualizarse.
- **FR-007**: El entorno raíz MUST dejar de ofrecer la creación de ambientes de aplicación
  adicionales a `dev`.
- **FR-008**: El flujo documentado de creación del clúster local desde cero MUST seguir llevando
  `dev` a Synced y Healthy sin pasos manuales añadidos.
- **FR-009**: Quien tenga un clúster local anterior al cambio MUST poder pasar a la configuración
  consolidada con un procedimiento documentado de destruir y recrear el entorno desde cero (el
  estado del laboratorio es desechable y el clúster cambia de nombre); el resultado MUST ser `dev`
  Synced y Healthy. El cambio no migra el estado de Terraform ni conserva los datos del clúster
  anterior.
- **FR-010**: El despliegue continuo a `dev` (LAB-81) MUST seguir funcionando sin modificaciones en
  su contrato.
- **FR-011**: El README y los documentos de `shoplab-infra`, y los documentos vigentes de
  `shoplab-helpdesk` que describen el despliegue, MUST describir un único ambiente `dev` y no
  presentar `staging` ni `prod` como existentes.
- **FR-012**: Un ADR nuevo MUST registrar la decisión de un solo ambiente, su motivo y sus
  consecuencias, y MUST describir cómo se añadiría otro ambiente (otro overlay sobre `base/` y otra
  Application de Argo CD, y el entorno raíz de Terraform que corresponda); el índice de ADRs MUST
  enlazarlo.
- **FR-013**: Los ADRs y especificaciones previos MUST conservarse; los que queden parcialmente
  superados MUST indicarlo y apuntar al ADR nuevo.
- **FR-014**: El cambio MUST documentar qué hacer con los recursos de `staging`/`prod` si alguna vez
  se sincronizaron en un clúster, y cómo limpiar un entorno local creado con el nombre anterior
  antes de recrearlo con el nombre `dev`.
- **FR-015**: La validación en vivo (`terraform apply` y estado de Argo CD) MUST ejecutarse y
  registrar su resultado en la carpeta de esta especificación.

### Key Entities *(include if feature involves data)*

- **Ambiente**: Un destino de despliegue con nombre propio (namespace, imágenes, recursos, escalado,
  configuración y claves de secretos). Tras el cambio existe uno solo: `dev`.
- **Overlay**: La personalización de `base/` para un ambiente. Tras el cambio solo existe el de
  `dev`.
- **Application de Argo CD**: El objeto que enlaza un overlay con un namespace del clúster y lo
  mantiene sincronizado. Tras el cambio solo existe la de `dev`, gestionada por la Application
  raíz.
- **Entorno raíz de Terraform**: La unidad de infraestructura que levanta clúster, plataforma y
  Applications, con su propio estado. Tras el cambio solo existe `envs/dev`.
- **ADR**: El registro de una decisión de arquitectura; este cambio añade uno que reemplaza
  parcialmente decisiones previas sobre múltiples ambientes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El número de ambientes definidos en `shoplab-infra` pasa de 3 a 1, y una búsqueda de
  `staging` o `prod` en manifiestos, pipeline, scripts, Terraform y documentación vigente no
  devuelve ninguna referencia fuera del ADR de la decisión.
- **SC-002**: El 100 % de las verificaciones obligatorias de un pull request con este cambio pasa, y
  las que construyen manifiestos cubren exactamente 1 ambiente.
- **SC-003**: Los manifiestos construidos para `dev` son idénticos antes y después del cambio
  (diferencia cero).
- **SC-004**: Un `terraform apply` desde cero lleva `dev` a Synced y Healthy sin intervención manual
  en el tiempo habitual del flujo documentado, y lo mismo ocurre al recrear un entorno local
  anterior siguiendo el procedimiento documentado, sin dejar recursos del entorno previo.
- **SC-005**: Un merge a `main` de la aplicación posterior al cambio sigue actualizando `dev` sin
  pasos manuales.
- **SC-006**: Una persona nueva puede encontrar en el ADR, en menos de 5 minutos, por qué hay un
  solo ambiente y los pasos para añadir otro.

## Assumptions

- La historia afecta principalmente a `shoplab-infra`; la documentación vigente de
  `shoplab-helpdesk` se corrige donde afirme que existen `staging` o `prod`. Sus especificaciones
  históricas (LAB-79, LAB-80, LAB-81, LAB-98, LAB-99) no se reescriben.
- Hoy el entorno raíz de Terraform se llama `local`; el criterio "un único entorno raíz `envs/dev`"
  se interpreta como renombrarlo a `dev`, de modo que el nombre coincida con el ambiente.
- Los overlays y Applications de `staging` y `prod` nunca se sincronizaron en el clúster local (el
  overlay `argocd-local` ya los retira), por lo que no hay recursos que limpiar en él; el
  procedimiento de limpieza para otros clústeres se documenta de todos modos.
- El estado del laboratorio vive en un emulador efímero y no contiene datos que conservar: pasar de
  un entorno local anterior a `dev` es destruir y recrear, no una migración (decisión del
  2026-10-26, opción A de la revisión de FR-009).
- Se crea un ADR nuevo (en lugar de editar uno existente) porque los ADRs vigentes son registros
  históricos; los afectados se marcan como parcialmente reemplazados.
- Añadir otro ambiente en el futuro queda solo documentado, no implementado (YAGNI).
- Depende de LAB-79 (Applications de Argo CD) y se apoya en LAB-80, LAB-98, LAB-99 y LAB-81 ya
  fusionadas; no introduce funcionalidad nueva de aplicación.
- La validación en vivo se hace en la máquina local del desarrollador con el flujo documentado de
  `shoplab-infra`.
