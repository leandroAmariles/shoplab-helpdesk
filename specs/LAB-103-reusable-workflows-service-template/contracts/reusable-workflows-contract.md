# Contrato: Workflows Reutilizables en `shoplab-ci`

**Repositorio**: `leandroamariles/shoplab-ci`
**Ruta**: `.github/workflows/`
**Versión**: `@v1`
**Trigger**: `workflow_call`

---

## 1. Workflow `java-service.yml`

Diseñado para compilar, probar y verificar la calidad de microservicios Java 21 basados en Spring Boot 4.x.

### 1.1. Entradas (`inputs`)

| Nombre | Tipo | Requerido | Por Defecto | Descripción |
|---|---|---|---|---|
| `java-version` | `string` | No | `'21'` | Versión del JDK para compilación y pruebas |
| `coverage-threshold` | `number` | No | `80` | Porcentaje mínimo de cobertura de líneas JaCoCo |
| `qodana-enabled` | `boolean` | No | `true` | Habilitar análisis estático con JetBrains Qodana |
| `trivy-scan` | `boolean` | No | `true` | Habilitar escaneo de seguridad con Trivy |
| `trivy-severity` | `string` | No | `'HIGH,CRITICAL'` | Severidades que provocan fallo en Trivy |
| `image-name` | `string` | Sí | — | Nombre base de la imagen (ej: `ghcr.io/leandroamariles/chat-service`) |
| `docker-context` | `string` | No | `'.'` | Contexto del Docker build |
| `dockerfile` | `string` | No | `'Dockerfile'` | Ruta al Dockerfile |
| `publish-image` | `boolean` | No | `false` | Publicar imagen a GHCR (solo en push/release) |

### 1.2. Secretos (`secrets`)

| Nombre | Requerido | Descripción |
|---|---|---|
| `qodana-token` | No | Token de acceso para subir reportes a Qodana Cloud |
| `github-token` | Sí | Token con permisos de lectura/escritura en paquetes de GitHub (`packages: write`) |

### 1.3. Secuencia de Jobs y Steps

```yaml
jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - name: Policy check (anti-latest y CAMBIA-ESTE-OWNER)
        run: |
          if grep -r --exclude-dir={.git,target} "CAMBIA-ESTE-OWNER" .; then
            echo "::error::Presencia de marcador CAMBIA-ESTE-OWNER en el repositorio"
            exit 1
          fi
          if grep -r --exclude-dir={.git,target} --include="*.ya?ml" ":latest" .; then
            echo "::error::Uso prohibido de tag :latest en manifiestos"
            exit 1
          fi
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ inputs.java-version }}
          distribution: 'temurin'
          cache: 'maven'
      - name: Run Maven Build & Tests with Testcontainers
        run: mvn clean verify -B
      - name: Check JaCoCo Coverage Gate (>= 80%)
        run: |
          COVERAGE=$(awk -F',' '{ instructions += $4 + $5; covered += $5 } END { print int(100*covered/instructions) }' target/site/jacoco/jacoco.csv)
          echo "Cobertura calculada: ${COVERAGE}% (umbral: ${{ inputs.coverage-threshold }}%)"
          if [ "$COVERAGE" -lt "${{ inputs.coverage-threshold }}" ]; then
            echo "::error::Cobertura insuficiente: ${COVERAGE}% < ${{ inputs.coverage-threshold }}%"
            exit 1
          fi
      - name: Qodana Scan
        if: inputs.qodana-enabled
        uses: JetBrains/qodana-action@v2024.2
        with:
          args: --fail-threshold,0
      - name: Trivy Filesystem Scan
        if: inputs.trivy-scan
        uses: aquasecurity/trivy-action@v0.36.0
        with:
          scan-type: 'fs'
          severity: ${{ inputs.trivy-severity }}
          exit-code: '1'
          ignore-unfixed: true
```

---

## 2. Workflow `python-service.yml`

Diseñado para microservicios y herramientas basadas en Python (ej: `retrieval-service`, `eval`).

### 2.1. Entradas principales
* `python-version`: Versión de Python (por defecto `'3.11'`).
* `uv-version`: Versión del gestor `uv` (por defecto `'latest'`).
* `image-name`: Nombre de la imagen OCI en GHCR.
* `publish-image`: Booleano para activar la publicación.

### 2.2. Pasos estándar
* Chequeo de políticas (`CAMBIA-ESTE-OWNER`, `:latest`).
* Instalación de `uv` y sincronización de dependencias (`uv sync --frozen`).
* Formato y linting con Ruff (`uv run ruff check .` y `uv run ruff format --check .`).
* Ejecución de tests con Pytest (`uv run pytest --cov --cov-fail-under=80`).
* Escaneo de vulnerabilidades en dependencias y código con Trivy.

---

## 3. Workflow `angular-app.yml`

Diseñado para la aplicación web Angular (`frontend`).

### 3.1. Entradas principales
* `node-version`: Versión de Node.js (por defecto `'22'`).
* `image-name`: Nombre de la imagen en GHCR (ej: `ghcr.io/leandroamariles/frontend`).
* `publish-image`: Booleano para activar publicación.

### 3.2. Pasos estándar
* Chequeo de políticas (`CAMBIA-ESTE-OWNER`, `:latest`).
* Instalación de dependencias con `npm ci`.
* Linter de código (`npm run lint`).
* Tests unitarios con Karma/Jest en modo headless (`npm run test -- --no-watch --no-progress --browsers=ChromeHeadless`).
* Compilación de producción (`npm run build`).
* Escaneo de paquetes npm con Trivy.

---

## 4. Workflow `publish-image.yml` (Firma y Publicación OCI)

Job reutilizable para construir y publicar contenedores en GHCR:
* Autenticación contra `ghcr.io` con `secrets.github-token`.
* Verificación de inmutabilidad: rechaza publicar si el tag `vX.Y.Z` ya existe.
* Generación de SBOM (`sbom: true`) y procedencia (`provenance: mode=max`).
* Firma keyless con Cosign usando identidad OIDC:
  ```bash
  cosign sign --yes "${IMAGE}@${DIGEST}"
  ```
* Verificación inmediata de la firma:
  ```bash
  cosign verify "${IMAGE}@${DIGEST}" \
    --certificate-identity-regexp "^https://github.com/leandroamariles/.*" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com
  ```
