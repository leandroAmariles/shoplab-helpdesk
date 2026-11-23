# Contract: Package Distribution Coordinates and Registries

**Specification**: `contracts/package-distribution-contract.md`  
**Purpose**: Formal specification of coordinates, registries, and authentication models for all distributed packages.

---

## 1. Maven Package Specification (Java Backend)

- **Target Registry**: GitHub Packages (`https://maven.pkg.github.com/leandroAmariles/shoplab-contracts`)
- **Group ID**: `com.shoplab`
- **Artifact ID**: `shoplab-contracts`
- **Packaging**: `jar`
- **Version Pattern**: `${revision}` aligned with Git tag `vX.Y.Z`
- **Artifact Contents**:
  - Compiled DTO records and models generated from OpenAPI specifications.
  - `@Generated` annotations pointing to OpenAPI generator version.
  - Zero business logic, zero entity annotations, zero database dependencies.
- **Consumer Configuration (`pom.xml`)**:
  ```xml
  <repositories>
      <repository>
          <id>github-shoplab-contracts</id>
          <name>GitHub Packages ShopLab Contracts</name>
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
- **Authentication**: `~/.m2/settings.xml` server entry for `github-shoplab-contracts` using `GITHUB_TOKEN` or personal access token with scope `read:packages`.

---

## 2. npm Package Specification (Angular Frontend)

- **Target Registry**: GitHub Packages npm (`https://npm.pkg.github.com`)
- **Scope**: `@leandroamariles`
- **Package Name**: `@leandroamariles/shoplab-contracts`
- **Version Pattern**: SemVer `X.Y.Z`
- **Artifact Contents**:
  - Compiled TypeScript `.d.ts` definitions and ESM/CommonJS modules.
  - Angular typed HTTP client services and model interfaces generated from OpenAPI.
- **Consumer Configuration (`.npmrc` and `package.json`)**:
  - In `.npmrc`:
    ```ini
    @leandroamariles:registry=https://npm.pkg.github.com
    //npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
    ```
  - In `package.json`:
    ```json
    "dependencies": {
      "@leandroamariles/shoplab-contracts": "1.0.0"
    }
    ```

---

## 3. Python Package Specification (`uv` Git Tag)

- **Target**: Git Repository Tag (`https://github.com/leandroAmariles/shoplab-contracts.git@vX.Y.Z`)
- **Package Name**: `shoplab-contracts`
- **Build Backend**: `hatchling` (PEP 517 / PEP 621)
- **Artifact Contents**:
  - Pydantic v2 models generated from OpenAPI schemas.
  - Pure typed data containers (`BaseModel`), type hints, and serialization helpers.
- **Consumer Configuration (`pyproject.toml`)**:
  ```toml
  [project]
  name = "my-python-service"
  dependencies = [
      "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.0.0"
  ]
  ```
  Installed deterministically via:
  ```bash
  uv add "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.0.0"
  ```
