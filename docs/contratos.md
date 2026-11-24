# Gestión de Contratos y Orden de Despliegue

Este documento describe el modelo de gobierno de interfaces, el flujo de publicación del repositorio
de soporte `shoplab-contracts` y la política obligatoria de despliegue entre productores y consumidores
(LAB-108, ADR-0015, LAB-128).

---

## 1. Repositorio `shoplab-contracts`

Siguiendo **ADR-0015**, las especificaciones de interfaz entre servicios no se comparten mediante carpetas
locales ni bibliotecas de código de dominio. Viven en el repositorio dedicado
[`leandroAmariles/shoplab-contracts`](https://github.com/leandroAmariles/shoplab-contracts).

### Árbol de especificaciones

```text
shoplab-contracts/
├── openapi/        # Contratos HTTP REST síncronos (OpenAPI 3.1)
├── avro/           # Esquemas de eventos Kafka (.avsc) — vacío hasta LAB-50
├── asyncapi/       # Especificaciones de mensajería asíncrona — vacío hasta LAB-50
└── generators/     # Proyectos de generación para Java, npm y Python
```

---

## 2. Ciclo de Vida de un Cambio de Contrato

```text
PR en shoplab-contracts ──> CI (oasdiff + avro check) ──> Merge a main ──> release-please (PR)
                                                                                  │
                                            npm & Maven en GitHub Packages ◄──────┴── Merge de Release
                                            Tag git vX.Y.Z para uv (Python)
```

### Reglas de Compatibilidad en Pull Requests

1. **Cambio Compatible (Aditivo)**:
   - Añadir un endpoint nuevo o campos opcionales.
   - Commit: `feat(tickets): [LAB-xxx] añade campo priority opcional`.
   - `oasdiff breaking` reporta 0 roturas. Check en verde. `release-please` incrementa versión `minor` (`1.1.0`).

2. **Cambio Incompatible (Breaking Change)**:
   - Eliminar un campo, cambiar un tipo de dato, hacer obligatorio un parámetro existente o retirar un endpoint.
   - Commit o título de PR obligatorio con `!` o cuerpo con `BREAKING CHANGE:`:
     `feat(tickets)!: [LAB-xxx] retira campo legacy status`.
   - `oasdiff breaking` detecta la ruptura. Si el título/commit incluye `!:` o `BREAKING CHANGE:`, el gate
     aprueba el cambio para versión **mayor**; si no lo incluye, **la CI bloquea el PR**.
   - `release-please-config.json` tiene fijado `"bump-minor-pre-major": false`, garantizando que cualquier
     cambio incompatible genera la siguiente versión mayor (`2.0.0`).

3. **Esquemas Avro (Schema Registry)**:
   - Hasta `LAB-50`, la carpeta `avro/` no contiene esquemas de eventos; el script de CI omite limpiamente
     la verificación con mensaje explícito.
   - A partir de `LAB-50`, todo esquema modificado se valida bajo modo de compatibilidad `BACKWARD`.

---

## 3. Publicación y Consumo por Stack

Cada release publicada (`vX.Y.Z`) genera y distribuye artefactos inmutables sin intervención manual:

### 3.1. Backend Java (Maven)
- **Artefacto**: `com.shoplab:shoplab-contracts:X.Y.Z` en GitHub Packages.
- **Configuración en el consumidor (`pom.xml`)**:
  ```xml
  <repositories>
      <repository>
          <id>github-shoplab-contracts</id>
          <url>https://maven.pkg.github.com/leandroAmariles/shoplab-contracts</url>
      </repository>
  </repositories>

  <dependencies>
      <dependency>
          <groupId>com.shoplab</groupId>
          <artifactId>shoplab-contracts</artifactId>
          <version>1.0.0</version>
      </dependency>
  </dependencies>
  ```
- **Autenticación local**: Servidor en `~/.m2/settings.xml` con credencial de GitHub Packages y token con permiso `read:packages`.

### 3.2. Frontend Angular (npm)
- **Paquete**: `@leandroamariles/shoplab-contracts` en GitHub Packages npm.
- **Configuración en el consumidor (`.npmrc` y `package.json`)**:
  ```ini
  @leandroamariles:registry=https://npm.pkg.github.com
  //npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
  ```
  ```json
  "dependencies": {
    "@leandroamariles/shoplab-contracts": "1.0.0"
  }
  ```

### 3.3. Servicios Python (`uv`)
- **Instalación**: Referencia inmutable por Git tag:
  ```bash
  uv add "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.0.0"
  ```
- **En `pyproject.toml`**:
  ```toml
  [project]
  dependencies = [
      "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.0.0"
  ]
  ```

---

## 4. Política de Dependabot en los Servicios Consumidores

Para que Dependabot detecte nuevas versiones publicadas en GitHub Packages, los consumidores
configuran los registros en `.github/dependabot.yml`:

```yaml
registries:
  github-maven:
    type: maven-repository
    url: https://maven.pkg.github.com/leandroAmariles
    token: ${{ secrets.DEPENDABOT_PACKAGES_TOKEN }}
  github-npm:
    type: npm-registry
    url: https://npm.pkg.github.com
    token: ${{ secrets.DEPENDABOT_PACKAGES_TOKEN }}

updates:
  - package-ecosystem: maven
    directory: /backend
    registries:
      - github-maven
    schedule:
      interval: weekly

  - package-ecosystem: npm
    directory: /frontend
    registries:
      - github-npm
    schedule:
      interval: weekly
```

> **Nota sobre Python**: Dependabot sondea índices de paquetes estándar (PEP 503 / PyPI). Para dependencias
> fijadas por URL Git (`@vX.Y.Z`), la actualización se realiza mediante `uv lock --upgrade-package shoplab-contracts`
> o automatizaciones vía `workflow_dispatch`.

---

## 5. Orden Obligatorio de Despliegue (Expand-and-Contract)

En sistemas distribuidos, el orden de despliegue entre el servicio productor (quien expone la API) y
los servicios consumidores determina si hay caídas en producción.

### Escenario A: Cambio Compatible (Aditivo)
1. **Paso 1**: Publicar la nueva versión del contrato en `shoplab-contracts` (ej. `v1.1.0`).
2. **Paso 2**: **Desplegar el Productor primero**. El productor empieza a aceptar y emitir el nuevo campo opcional.
3. **Paso 3**: **Desplegar los Consumidores después**. Cada consumidor actualiza su versión de contratos y empieza a utilizar el nuevo campo cuando esté listo.

### Escenario B: Cambio Incompatible (Breaking Change) — Estrategia Expand-and-Contract

**Nunca se retira un campo ni se cambia un tipo de dato en un solo paso.** Se ejecuta en tres fases:

```text
[FASE 1: EXPAND]
1. Modificar el contrato para soportar ambos formatos (el antiguo y el nuevo) en la versión v1.x.
2. Desplegar el Productor (capaz de responder tanto al formato antiguo como al nuevo).

[FASE 2: MIGRATE]
3. Actualizar y desplegar todos los Consumidores progresivamente para que consuman el nuevo formato.
4. Verificar mediante métricas y logs que el formato antiguo tiene 0 invocaciones.

[FASE 3: CONTRACT]
5. Publicar v2.0.0 en shoplab-contracts retirando el formato antiguo con 'feat!: [LAB-xxx] ...'.
6. Desplegar el Productor v2.0.0 eliminando el código de compatibilidad legacy.
```

Para la matriz completa de criterios de cambio de versión (`patch`, `minor`, `major`), convivencia de versiones y el procedimiento de retiro (*sunset*) paso a paso en APIs HTTP, consulta también [F5 · Versionado de APIs HTTP y retiro de versiones anteriores](fases/F5-contratos-http-versionado-y-retiro.md).
