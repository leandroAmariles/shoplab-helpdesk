# Contrato de Scripts de Clonación (`clone-all`)

**Feature**: `LAB-110-multi-repo-local-env`  
**Artefactos**: `Makefile` y `scripts/clone-all.ps1`  
**Status**: Approved  

---

## 1. Contrato Operativo de `make clone-all`

1. **Ubicación y Ejecución**:
   * Se ejecuta desde la raíz de `shoplab-helpdesk`.
   * Sintaxis: `make clone-all` o `GIT_BASE_URL=git@github.com:leandroAmariles make clone-all`.

2. **Parámetros y Variables**:
   * `GIT_BASE_URL`: URL base de la organización/usuario de GitHub. Valor por defecto: `https://github.com/leandroAmariles`.

3. **Lista Mandatoria de Repositorios**:
   * `shoplab-frontend`
   * `shoplab-ingest`
   * `shoplab-eval`
   * `shoplab-contracts`
   * `shoplab-ci`
   * `shoplab-infra`

4. **Comportamiento e Idempotencia**:
   * Antes de ejecutar `git clone`, verifica la existencia de `../<repo>`.
   * Si el directorio existe:
     * No ejecuta `git clone`.
     * No altera el árbol de trabajo actual del repositorio hermano.
     * Imprime un mensaje informativo: `[SKIP] <repo> already exists in ../<repo>`.
   * Si el directorio no existe:
     * Ejecuta `git clone $(GIT_BASE_URL)/<repo>.git ../<repo>`.
     * Imprime confirmación: `[CLONED] <repo> cloned successfully`.
   * El código de salida (`exit code`) debe ser `0` si todos los repositorios fueron clonados o ya existían previamente.

---

## 2. Contrato Operativo de `scripts/clone-all.ps1` (PowerShell)

1. **Compatibilidad y Paridad**:
   * Diseñado para ejecutarse nativamente en Windows PowerShell 5.1 y PowerShell Core 7+.
   * Sintaxis: `pwsh scripts/clone-all.ps1` o `powershell -ExecutionPolicy Bypass -File scripts/clone-all.ps1`.
   * Acepta el parámetro opcional `-GitBaseUrl <url>`, con valor por defecto `https://github.com/leandroAmariles`.

2. **Idempotencia**:
   * Utiliza `Test-Path (Join-Path $PSScriptRoot "../../$repo")` para validar la existencia antes de clonar.
   * Mantiene idénticos códigos de retorno y mensajes de log que el Makefile.
