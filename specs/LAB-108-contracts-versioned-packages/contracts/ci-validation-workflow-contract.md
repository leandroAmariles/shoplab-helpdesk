# Contract: CI Validation & Compatibility Gate Workflow

**Specification**: `contracts/ci-validation-workflow-contract.md`  
**Purpose**: Defines the operational contract for CI pull-request validation in `shoplab-contracts`.

---

## 1. OpenAPI Compatibility Gate (`oasdiff`)

### Trigger
- Pull Request targeted at branch `main` modifying files matching `openapi/**`.

### Inputs
- **Base Spec (`--base`)**: File in base branch or latest release tag `vX.Y.Z`.
- **Revision Spec (`--revision`)**: File in the incoming PR commit.
- **PR Title & Body**: Text analyzed for Conventional Commits breaking change marker (`!` in type/scope or `BREAKING CHANGE:` in footer).

### Exit Codes & Rules
| Scenario | Breaking Changes Count | PR Marker Present | Exit Code | Gate Status | Action |
|---|---|---|---|---|---|
| No breaking changes | 0 | Any | 0 | `PASSED` | Merge allowed. Minor or patch bump. |
| Breaking changes detected | >= 1 | `NO` | 1 | `BLOCKED` | Merge blocked. Developer must revert breakage or declare breaking change. |
| Breaking changes detected | >= 1 | `YES` (`!` or `BREAKING CHANGE:`) | 0 | `PASSED` | Merge allowed with warning. Major version bump (`X+1.0.0`). |
| Initial version / No base | N/A | Any | 0 | `PASSED` | Validates OpenAPI syntax only. Establishes v1.0.0 baseline. |

---

## 2. Avro Compatibility Gate (Schema Registry `BACKWARD`)

### Trigger
- Pull Request targeted at branch `main` modifying files matching `avro/**`.

### Rules
1. **Empty Directory Pre-Check**:
   - Condition: No `.avsc` files exist in `avro/`.
   - Behavior: Emit informational log: `"[INFO] No Avro schemas found in avro/; skipping Schema Registry compatibility check"` and return exit code `0`.
2. **Schema Evaluation (Post LAB-50)**:
   - For each modified `.avsc` file, execute Confluent Schema Registry compatibility check with mode `BACKWARD`.
   - If backward-incompatible:
     - If PR does NOT declare breaking change (`!` / `BREAKING CHANGE:`): Exit code `1` (BLOCKED).
     - If PR declares breaking change: Exit code `0` (PASSED for MAJOR release).

---

## 3. Package Generation Dry-Run Gate

### Trigger
- Every pull request in `shoplab-contracts`.

### Steps
1. **Java/Maven**: Run `mvn clean compile` on `generators/java/` verifying all generated classes compile without errors.
2. **TypeScript/npm**: Run `@openapitools/openapi-generator-cli` and `npm run build` on `generators/npm/` verifying TypeScript compiles without type errors.
3. **Python**: Run `datamodel-code-generator` and `uv build` on `generators/python/` verifying Python wheel/sdist builds cleanly.
4. **Failure Condition**: Any compiler or generator failure halts the PR.
