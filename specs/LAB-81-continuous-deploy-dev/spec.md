# Feature Specification: Despliegue continuo a dev con kustomize edit set image

**Feature Branch**: `feature/LAB-81-continuous-deploy-dev`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-81 (F0-19) Como desarrollador, quiero que un merge a `main` termine desplegado en dev sin pasos manuales y sin que CI toque el clúster. Criterios: job `deploy` en el repo de la app con `imranismail/setup-kustomize`: hace `kustomize edit set image` en el overlay `dev` de `shoplab-infra` con el `:sha` inmutable y hace commit del nuevo tag; nunca `kubectl apply` ni `:latest`; escribe en `shoplab-infra` con un token de alcance mínimo (GitHub App o PAT fine-grained solo con ese repo) guardado como secreto; Argo CD sincroniza dev automáticamente; el commit enlaza el SHA y la ejecución de CI que lo produjo; rollback documentado: revertir el commit en `shoplab-infra`. Depende de: LAB-78, LAB-79."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un merge a main llega a dev sin pasos manuales (Priority: P1)

Como desarrollador, quiero que, al fusionar a `main`, las imágenes recién publicadas queden
registradas como la versión deseada del ambiente `dev` en `shoplab-infra`, para que `dev` se
actualice solo, sin que yo ejecute nada y sin que el pipeline de la aplicación tenga acceso al
clúster.

**Why this priority**: Es el objetivo de la historia. Cierra el ciclo de entrega continua iniciado
por las imágenes (LAB-78) y la sincronización GitOps (LAB-79).

**Independent Test**: Fusionar un cambio de la aplicación a `main`, esperar a que termine el
pipeline y comprobar que `shoplab-infra` recibe un commit que fija en el overlay `dev` el tag del
SHA fusionado, y que `dev` termina ejecutando esa versión sin intervención.

**Acceptance Scenarios**:

1. **Given** un merge a `main` cuyas imágenes se publicaron con el tag del SHA, **When** termina la
   publicación, **Then** se ejecuta el despliegue y se crea en `shoplab-infra` un commit que fija
   en el overlay `dev` ese tag inmutable para cada servicio publicado.
2. **Given** el commit en `shoplab-infra`, **When** Argo CD sincroniza `dev`, **Then** el ambiente
   pasa a ejecutar las imágenes del SHA fusionado sin ningún paso manual.
3. **Given** el despliegue, **When** se revisa lo que ejecutó, **Then** no usó `kubectl apply` ni
   tuvo credenciales del clúster, y no escribió `:latest` en ningún manifiesto.
4. **Given** un merge a `main` donde la publicación de imágenes falló o fue bloqueada por el
   escaneo, **When** termina el pipeline, **Then** no se ejecuta el despliegue ni cambia `dev`.
5. **Given** una ejecución sobre una rama distinta de `main`, un pull request o un tag de versión,
   **When** corre el pipeline, **Then** el despliegue a `dev` no se ejecuta.

---

### User Story 2 - Acceso mínimo y trazabilidad del cambio (Priority: P1)

Como desarrollador, quiero que el pipeline escriba en `shoplab-infra` con una credencial de alcance
mínimo y que cada commit de despliegue enlace el SHA y la ejecución de CI que lo produjo, para
limitar el daño de una filtración y poder auditar qué se desplegó, cuándo y por qué.

**Why this priority**: Una credencial con más alcance del necesario es un riesgo de seguridad
permanente; la trazabilidad es lo que hace utilizable el rollback y la auditoría.

**Independent Test**: Revisar la credencial y comprobar que solo puede escribir contenido en
`shoplab-infra`; revisar un commit de despliegue y comprobar que contiene el SHA completo de la
aplicación y el enlace a la ejecución de CI.

**Acceptance Scenarios**:

1. **Given** la credencial de escritura, **When** se inspeccionan sus permisos, **Then** solo
   alcanza el repositorio `shoplab-infra` y solo con el permiso de escritura de contenido.
2. **Given** la credencial, **When** se busca en el repositorio, los logs y los manifiestos,
   **Then** no aparece en claro: se guarda como secreto del repositorio y los logs la enmascaran.
3. **Given** un commit de despliegue, **When** se lee su mensaje, **Then** incluye el SHA del
   commit de la aplicación y el enlace a la ejecución de CI que publicó las imágenes.
4. **Given** una ejecución sobre un pull request (incluido uno de un fork), **When** corre el
   pipeline, **Then** la credencial no está disponible para ella.

---

### User Story 3 - Rollback documentado (Priority: P2)

Como desarrollador, quiero una guía que explique cómo volver a la versión anterior de `dev`
revirtiendo el commit en `shoplab-infra`, para recuperar un ambiente roto con un procedimiento
conocido y sin tocar el clúster a mano.

**Why this priority**: Un despliegue automático sin vuelta atrás es peligroso, pero el rollback
reutiliza el mecanismo GitOps ya decidido (ADR-0006); basta documentarlo y probarlo.

**Independent Test**: Seguir la guía para revertir el último commit de despliegue y comprobar que
`dev` vuelve a la versión anterior y que el siguiente merge a `main` vuelve a desplegar.

**Acceptance Scenarios**:

1. **Given** un despliegue que dejó `dev` con una versión defectuosa, **When** se revierte su
   commit en `shoplab-infra` siguiendo la guía, **Then** Argo CD devuelve `dev` a las imágenes
   anteriores.
2. **Given** un rollback hecho, **When** se fusiona un nuevo cambio a `main`, **Then** el
   despliegue vuelve a avanzar `dev` a esa nueva versión.
3. **Given** la guía, **When** la lee un desarrollador que no participó en la historia, **Then**
   puede identificar el commit a revertir y verificar el resultado sin ayuda.

---

### User Story 4 - Despliegues concurrentes y repetidos son seguros (Priority: P2)

Como desarrollador, quiero que varios merges seguidos, reejecuciones y conflictos de escritura no
dejen `dev` en una versión más antigua ni en un estado inconsistente, para confiar en que `dev`
siempre refleja el último `main` desplegable.

**Why this priority**: En un equipo, los merges seguidos son habituales; sin esto el despliegue
automático produciría versiones mezcladas o pasos manuales de recuperación.

**Independent Test**: Fusionar dos cambios en sucesión rápida y comprobar que `dev` termina en el
SHA más reciente; reejecutar un despliegue ya aplicado y comprobar que no genera commits vacíos.

**Acceptance Scenarios**:

1. **Given** dos merges consecutivos a `main`, **When** terminan ambos despliegues, **Then** el
   overlay `dev` queda fijado al SHA del merge más reciente, nunca a uno anterior.
2. **Given** un despliegue cuyo SHA ya está fijado en el overlay `dev`, **When** se reejecuta,
   **Then** no se crea ningún commit y la ejecución termina en verde.
3. **Given** que `shoplab-infra` recibió otro commit mientras el despliegue preparaba el suyo,
   **When** intenta escribir, **Then** integra el cambio y reintenta, y solo falla de forma
   visible si no logra escribir tras los reintentos.
4. **Given** un tag cuya imagen no existe en el registro, **When** el despliegue lo intenta
   fijar, **Then** se rechaza antes de escribir y `dev` no cambia.

---

### Edge Cases

- Publicación parcial (una imagen sí y otra no): no se despliega ningún servicio; `dev` no queda con
  servicios en SHAs distintos por causa del despliegue.
- Servicios cuya imagen es un contenido mínimo por no tener código aún (`frontend`, `ingest`): se
  fijan igual que el resto mientras el overlay `dev` los declare.
- Un servicio publicado que el overlay `dev` no declara: el despliegue lo detecta y falla de forma
  visible en lugar de ignorarlo en silencio.
- Un merge que no cambia código de aplicación (solo documentación): sigue el flujo normal de
  `main`; si no hay imágenes nuevas que desplegar no debe generar ruido ni commits vacíos.
- La credencial expira o es revocada: el despliegue falla de forma visible y `dev` conserva su
  última versión válida; no hay despliegue parcial.
- `shoplab-infra` con protección de rama: el despliegue debe poder escribir en `main` con su
  identidad sin eludir las validaciones de la infraestructura (ver Assumptions).
- Un commit de despliegue que rompe la validación de CI de `shoplab-infra` (LAB-99): el fallo es
  visible en ese repositorio; el rollback sigue siendo revertir el commit.
- Argo CD detenido o inaccesible: el commit queda registrado en git y `dev` converge cuando
  Argo CD vuelve; el despliegue no depende de que Argo CD esté arriba.
- Cuota o límite de la API de GitHub: se trata como fallo visible reintentable, no como éxito.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El pipeline de la aplicación MUST incluir un job `deploy` que se ejecute tras la
  publicación correcta de las imágenes de un merge a `main`, y solo entonces.
- **FR-002**: El job `deploy` MUST NOT ejecutarse en pull requests, ramas distintas de `main` ni
  tags de versión.
- **FR-003**: El job `deploy` MUST fijar, mediante `kustomize edit set image` (instalado con
  `imranismail/setup-kustomize`), la imagen de cada servicio publicado en el overlay `dev` de
  `shoplab-infra`, usando el tag inmutable del SHA completo del commit.
- **FR-004**: El job `deploy` MUST NOT usar `kubectl apply` ni ningún otro acceso directo al
  clúster, y MUST NOT disponer de credenciales del clúster.
- **FR-005**: El job `deploy` MUST NOT escribir `:latest` ni una referencia sin tag en ningún
  manifiesto.
- **FR-006**: El job `deploy` MUST confirmar que cada imagen con el tag del SHA existe en el
  registro antes de escribir, y MUST abortar sin cambios si alguna falta.
- **FR-007**: El job `deploy` MUST crear un commit en `shoplab-infra` con el nuevo tag y MUST
  incluir en su mensaje el SHA del commit de la aplicación y el enlace a la ejecución de CI que lo
  produjo, siguiendo Conventional Commits.
- **FR-008**: El job `deploy` MUST escribir en `shoplab-infra` con una credencial de alcance mínimo
  (GitHub App o token de acceso fino) limitada a ese repositorio y al permiso de escritura de
  contenido.
- **FR-009**: La credencial MUST guardarse como secreto del repositorio de la aplicación, MUST NOT
  aparecer en claro en código, manifiestos ni logs, y MUST NOT estar disponible para ejecuciones de
  pull requests.
- **FR-010**: Si el SHA ya está fijado en el overlay `dev`, el job `deploy` MUST terminar en verde
  sin crear commit.
- **FR-011**: Las ejecuciones concurrentes del despliegue MUST serializarse de modo que `dev` nunca
  quede fijado a un SHA más antiguo que uno ya desplegado.
- **FR-012**: Ante un conflicto de escritura en `shoplab-infra`, el job `deploy` MUST integrar el
  cambio remoto y reintentar un número acotado de veces, y MUST fallar de forma visible si no lo
  logra.
- **FR-013**: Solo el overlay `dev` MUST ser modificado por este despliegue; `staging` y `prod`
  MUST NOT cambiar.
- **FR-014**: Argo CD MUST sincronizar `dev` automáticamente a partir del commit, sin acciones
  manuales (comportamiento aportado por LAB-79).
- **FR-015**: Cada ejecución MUST dejar en su resumen el SHA desplegado, los servicios y tags
  fijados y el enlace al commit creado en `shoplab-infra`, o el motivo por el que no desplegó.
- **FR-016**: Una guía en `docs/` MUST documentar el flujo de despliegue continuo, cómo identificar
  la versión desplegada en `dev` y el rollback revirtiendo el commit en `shoplab-infra`, incluido
  cómo verificar el resultado y cómo se reanuda el avance con el siguiente merge.
- **FR-017**: La guía MUST documentar cómo crear, rotar y revocar la credencial y qué permisos
  exactos necesita.
- **FR-018**: La imagen fijada MUST ser compatible con las reglas de LAB-80 y LAB-99 (versión
  fija, sin `:latest`) para que la validación de `shoplab-infra` siga en verde tras el commit.

### Key Entities *(include if feature involves data)*

- **Despliegue a dev**: acto de fijar en el overlay `dev` la versión de cada servicio; identificado
  por el SHA de la aplicación y por la ejecución de CI que lo produjo.
- **Tag inmutable**: tag del SHA completo del commit de la aplicación, publicado por LAB-78; nunca
  se reasigna.
- **Overlay dev**: parte de `shoplab-infra` que declara las imágenes y ajustes del ambiente `dev`.
- **Commit de despliegue**: commit en `shoplab-infra` que registra el cambio de versión y enlaza el
  SHA y la ejecución de CI; es la unidad de auditoría y de rollback.
- **Credencial de escritura**: secreto de alcance mínimo con el que el pipeline escribe en
  `shoplab-infra`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los merges a `main` con imágenes publicadas correctamente dejan un commit
  de despliegue en `shoplab-infra` sin intervención manual.
- **SC-002**: Desde que las imágenes están publicadas hasta que el commit de despliegue existe en
  `shoplab-infra` transcurren menos de 5 minutos en el caso habitual.
- **SC-003**: Tras el commit, `dev` converge a la nueva versión sin pasos manuales en un plazo
  acotado por el intervalo de sincronización de Argo CD (menos de 10 minutos en el entorno local).
- **SC-004**: 0 ejecuciones del pipeline de la aplicación disponen de credenciales del clúster o
  ejecutan `kubectl apply`, verificado revisando el pipeline.
- **SC-005**: 0 manifiestos de `shoplab-infra` contienen `:latest` tras cualquier despliegue.
- **SC-006**: El 100 % de los commits de despliegue permiten identificar, desde su mensaje, el SHA
  de la aplicación y la ejecución de CI de origen.
- **SC-007**: Con dos merges consecutivos, `dev` termina en el SHA más reciente en el 100 % de los
  casos probados, y reejecutar un despliegue ya aplicado genera 0 commits.
- **SC-008**: Un desarrollador que no participó en la historia revierte el último despliegue
  siguiendo solo la guía y `dev` vuelve a la versión anterior en menos de 15 minutos.

## Assumptions

- `shoplab-infra` ya contiene el overlay `dev` (LAB-80) y Argo CD sincroniza `main` de forma
  automática con `prune` y `selfHeal` (LAB-79, ADR-0006); en local solo se despliega `dev`.
- Las imágenes se publican en `ghcr.io/leandroamariles/<servicio>` con el tag del SHA completo y
  solo si el escaneo pasa (LAB-78); el despliegue reutiliza ese resultado y no reconstruye nada.
- Las referencias de imagen del overlay `dev` usan el nombre del registro, de modo que
  `kustomize edit set image` puede sustituir el tag sin tocar la base.
- `shoplab-infra` permite la escritura directa en `main` a la identidad del despliegue (la
  credencial o la excepción en la protección de rama); sus validaciones (LAB-99) siguen corriendo
  tras el commit. Si la política exige pull request, se decidirá en planificación sin cambiar el
  alcance de esta historia.
- El despliegue automático cubre solo `dev`; no verifica firmas en admisión (LAB-83).
- Fuera de alcance: promoción a `staging` y `prod`, aprobación manual de `prod`, despliegue de
  imágenes de release `vX.Y.Z`, pruebas de humo posteriores al despliegue, notificaciones,
  rollback automático y gestión de la infraestructura del clúster.
- Depende de LAB-78 (imágenes en GHCR) y LAB-79 (Argo CD); relacionada con LAB-80 (overlays), LAB-99
  (validación de `shoplab-infra`) y LAB-100 (versionado).
