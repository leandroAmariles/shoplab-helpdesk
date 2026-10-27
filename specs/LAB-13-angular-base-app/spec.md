# Feature Specification: App Angular base

**Feature Branch**: `feature/LAB-13-angular-base-app`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-13 (F0-05) App Angular base. Como desarrollador, quiero una app Angular con standalone components y signals. Criterios: proyecto generado; Angular Material o PrimeNG; layout base y proxy al backend."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Proyecto Angular que arranca y compila (Priority: P1)

Como desarrollador, quiero un proyecto Angular en `frontend/` construido con standalone components y signals, para
tener una base moderna sobre la que construir la pantalla de chat y las consolas siguientes sin reescribir cimientos.

**Why this priority**: Es el objetivo de la historia y bloquea todo el trabajo de interfaz de F0 (la pantalla de chat
con streaming y Markdown depende de esta base). Sin proyecto no hay nada que probar.

**Independent Test**: Desde una copia limpia del repositorio, instalar dependencias, arrancar la app en modo
desarrollo y abrirla en el navegador; después ejecutar las pruebas y el build de producción y comprobar que los tres
terminan sin errores.

**Acceptance Scenarios**:

1. **Given** una copia limpia del repositorio, **When** el desarrollador instala dependencias y arranca la app en
   desarrollo, **Then** la app abre en el navegador sin errores en consola.
2. **Given** el proyecto generado, **When** se inspecciona su código, **Then** los componentes son standalone (sin
   módulos de declaración) y el estado local de la interfaz se maneja con signals.
3. **Given** el proyecto, **When** se ejecutan las pruebas unitarias y el build de producción, **Then** ambos terminan
   en verde y producen un artefacto desplegable.
4. **Given** un pull request que modifica `frontend/`, **When** corre el pipeline de CI, **Then** el job de frontend
   ejecuta lint, pruebas y build sobre el proyecto real y deja de ser un paso de relevo.

---

### User Story 2 - Librería de componentes visuales integrada (Priority: P1)

Como desarrollador, quiero que la app incluya una librería de componentes (Angular Material o PrimeNG) ya configurada
con un tema, para construir pantallas con componentes accesibles y consistentes sin diseñar cada control desde cero.

**Why this priority**: Es un criterio de aceptación explícito y condiciona el aspecto de todas las pantallas
posteriores; elegirla y configurarla ahora evita retrabajo.

**Independent Test**: Renderizar en la app al menos un componente de la librería elegida (por ejemplo, un botón o una
barra superior) y comprobar que se muestra con el tema del proyecto.

**Acceptance Scenarios**:

1. **Given** el proyecto base, **When** se abre la app, **Then** los componentes de la librería elegida se muestran con
   el tema definido por el proyecto (colores y tipografía coherentes).
2. **Given** que la librería es una única opción elegida, **When** se revisan las dependencias, **Then** solo una de las
   dos librerías (Angular Material o PrimeNG) está instalada.
3. **Given** la decisión tomada, **When** otro desarrollador busca por qué se eligió esa librería, **Then** encuentra el
   motivo registrado en la documentación del proyecto.

---

### User Story 3 - Layout base de la aplicación (Priority: P1)

Como desarrollador, quiero un layout base (cabecera, navegación y área de contenido) con enrutado, para que las
pantallas siguientes (chat, consola de agentes, administración) se añadan como rutas dentro de una estructura común.

**Why this priority**: Es un criterio de aceptación explícito. La pantalla de chat de la siguiente historia se monta
sobre este layout.

**Independent Test**: Abrir la app, comprobar que se ven cabecera, navegación y área de contenido, navegar entre dos
rutas y comprobar que el layout permanece y solo cambia el contenido.

**Acceptance Scenarios**:

1. **Given** la app abierta, **When** carga la ruta inicial, **Then** se muestra el layout con cabecera, navegación y un
   área de contenido que aloja la pantalla de la ruta.
2. **Given** el layout, **When** el usuario navega entre rutas, **Then** cabecera y navegación se mantienen y solo
   cambia el contenido.
3. **Given** una ruta inexistente, **When** el usuario la visita, **Then** ve una pantalla de «no encontrado» dentro del
   layout, no una página en blanco.
4. **Given** una pantalla ancha y una estrecha (móvil), **When** se abre la app, **Then** el layout se adapta y la
   navegación sigue siendo utilizable en ambas.

---

### User Story 4 - Llamadas al backend sin configurar CORS (Priority: P2)

Como desarrollador, quiero que en desarrollo las peticiones a `/api` de la app se redirijan al backend local, para
probar la integración sin configurar CORS ni cambiar URLs entre entornos.

**Why this priority**: Es un criterio de aceptación explícito. Es P2 porque el layout y la librería ya demuestran la
base; el proxy se ejercita de verdad con la pantalla de chat (LAB-14), pero debe quedar listo ahora.

**Independent Test**: Con el backend local levantado y la app en desarrollo, hacer desde la app una petición a un
endpoint de salud del backend usando una ruta relativa `/api/...` y comprobar que recibe la respuesta del backend.

**Acceptance Scenarios**:

1. **Given** el backend local en ejecución y la app en modo desarrollo, **When** la app hace una petición a una ruta
   relativa que empieza por `/api`, **Then** la respuesta es la del backend y no hay errores de CORS.
2. **Given** que el backend no está disponible, **When** la app hace esa petición, **Then** la app recibe un error de
   conexión manejable y no se rompe el layout.
3. **Given** la configuración del proxy, **When** se cambia el destino del backend, **Then** basta con modificar un
   único punto de configuración, sin tocar el código de la app.
4. **Given** una respuesta en streaming (SSE) del backend, **When** pasa por el proxy, **Then** los eventos llegan al
   navegador a medida que se generan, sin quedar acumulados.

---

### User Story 5 - Imagen de contenedor con la app real (Priority: P3)

Como desarrollador, quiero que la imagen de `frontend` empaquete la app compilada en lugar de la página de relevo
actual, para que el despliegue continuo ya sirva la interfaz real.

**Why this priority**: Aprovecha la cadena existente (imágenes, GitOps) pero no es un criterio de aceptación de la
historia; si falla o se aplaza, la base Angular sigue siendo útil.

**Independent Test**: Construir la imagen del frontend, ejecutarla y abrir la URL en el navegador.

**Acceptance Scenarios**:

1. **Given** el proyecto Angular, **When** se construye la imagen de `frontend`, **Then** la imagen sirve la app
   compilada y no la página de relevo.
2. **Given** la imagen en ejecución, **When** se visita una ruta interna directamente (recarga en `/algo`), **Then** la
   app carga igualmente y no devuelve un error 404 del servidor.

---

### Edge Cases

- ¿Qué pasa si el puerto de desarrollo ya está en uso? El arranque informa del conflicto o elige otro puerto, sin
  dejar la app a medias.
- ¿Qué pasa si se abre la app en un navegador sin JavaScript? Muestra un aviso legible en lugar de una página en blanco.
- ¿Qué pasa si el backend responde con un error HTTP (4xx/5xx) a una petición de `/api`? La app lo recibe como error
  tipado y no lo trata como éxito.
- ¿Qué pasa si se llega a una ruta profunda por URL directa (recarga)? La app resuelve la ruta en el cliente.
- ¿Qué pasa si el entorno de CI no tiene un navegador para las pruebas? Las pruebas corren en un modo sin interfaz y no
  requieren un navegador instalado a mano.
- ¿Qué pasa si el desarrollador tiene una versión de Node distinta a la esperada? La versión requerida queda declarada
  y el arranque falla con un mensaje claro en lugar de errores crípticos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST contener, en `frontend/`, un proyecto Angular generado y funcional que arranque en
  modo desarrollo, pase las pruebas y compile para producción.
- **FR-002**: Los componentes de la app MUST ser standalone (sin módulos de declaración) y el estado local de la
  interfaz MUST modelarse con signals.
- **FR-003**: La app MUST incluir una única librería de componentes visuales, Angular Material o PrimeNG, con un tema
  configurado; la elección MUST quedar justificada en la documentación del proyecto.
- **FR-004**: La app MUST ofrecer un layout base con cabecera, navegación y área de contenido, con enrutado donde cada
  pantalla futura se añada como ruta dentro del layout.
- **FR-005**: El layout MUST adaptarse a pantallas anchas y estrechas manteniendo la navegación utilizable.
- **FR-006**: La app MUST mostrar una pantalla de «no encontrado» dentro del layout para rutas inexistentes.
- **FR-007**: En desarrollo, las peticiones a rutas relativas que empiezan por `/api` MUST redirigirse al backend local
  sin requerir configuración de CORS, y el destino MUST definirse en un único punto de configuración.
- **FR-008**: El proxy de desarrollo MUST transmitir las respuestas en streaming (SSE) a medida que se generan, sin
  acumularlas.
- **FR-009**: La app MUST manejar los errores de conexión con el backend sin romper el layout.
- **FR-010**: El pipeline de CI MUST ejecutar lint, pruebas y build sobre el proyecto Angular cuando cambie
  `frontend/`, reemplazando el comportamiento de relevo actual.
- **FR-011**: La imagen de contenedor de `frontend` SHOULD servir la app compilada, incluyendo la resolución de rutas
  internas en recarga.
- **FR-012**: La versión de Node y de las herramientas necesarias MUST estar declarada para que el arranque sea
  reproducible entre máquinas y CI.
- **FR-013**: El README MUST documentar cómo instalar, arrancar, probar y compilar la app.

### Key Entities *(include if feature involves data)*

- **Ruta de la aplicación**: una pantalla accesible por URL dentro del layout; tiene una ruta, un título y un lugar en
  la navegación. Las pantallas de chat, consola de agentes y administración serán rutas futuras.
- **Configuración del proxy**: relación entre el prefijo `/api` y el backend de destino en desarrollo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador con una copia limpia del repositorio tiene la app corriendo en el navegador en menos de
  10 minutos siguiendo solo el README.
- **SC-002**: Instalar, ejecutar las pruebas y compilar para producción termina con 0 errores y 0 advertencias de
  lint en una copia limpia y en CI.
- **SC-003**: El 100% de las peticiones relativas a `/api` hechas en desarrollo llegan al backend sin errores de CORS.
- **SC-004**: Añadir una pantalla nueva como ruta dentro del layout requiere cambios en un máximo de 2 lugares
  (el componente de la pantalla y su registro en las rutas).
- **SC-005**: El job de frontend en CI deja de ser un paso de relevo y completa en menos de 10 minutos en un pull
  request típico.
- **SC-006**: La app se muestra sin desbordes ni controles inaccesibles tanto a 360 px como a 1440 px de ancho.

## Assumptions

- Se elige **Angular Material** como librería de componentes por defecto, por su integración oficial con Angular y su
  accesibilidad; la decisión final (y la alternativa PrimeNG descartada) se registra en la fase de plan. Solo se
  instala una de las dos.
- Se usa la versión estable más reciente de Angular y la LTS de Node vigentes al implementar.
- El backend corre en local en el puerto `8080` (configuración actual de `backend/`) y expone la API bajo `/api`.
- Fuera de alcance: la pantalla de chat y el consumo del streaming (LAB-14 y siguientes), autenticación con Keycloak,
  consola de agentes y administración, internacionalización y modo oscuro.
- La interfaz de usuario está en español, coherente con la documentación del proyecto; los identificadores de código
  están en inglés (constitución, principio III).
- El gate de cobertura del 80% de la constitución aplica al backend; para el frontend basta con que las pruebas
  unitarias existan y pasen en CI, sin umbral numérico en esta historia.
- La imagen de `frontend` y su workflow ya existen con página de relevo; esta historia solo sustituye el contenido, no
  la cadena de publicación.
- Depende de que el backend de LAB-12 (`POST /api/chat`, SSE) esté disponible para ejercitar el proxy con streaming
  de punta a punta, aunque la pantalla de chat llegue después.
