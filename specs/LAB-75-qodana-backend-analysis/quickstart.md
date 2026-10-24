# Quickstart: validar el análisis de Qodana

Guía de validación del pipeline. Detalles del job en [data-model.md](data-model.md) y del contrato en
[contracts/qodana-check.md](contracts/qodana-check.md).

## Prerrequisitos

- LAB-15 implementado (`.github/workflows/ci.yml` con `changes`, `backend` y `ci-gate`).
- Acceso al repositorio en GitHub con Actions habilitado; Code Scanning habilitado para ver la pestaña
  Security (si no, usar el artefacto `qodana-report`).
- Opcional en local: Docker y [Qodana CLI](https://www.jetbrains.com/help/qodana/qodana-cli.html).

## 0. Generar el baseline inicial (una vez)

1. En una rama sin baseline, ejecutar el job `qodana` (o `qodana scan --project-dir backend` en
   local) y descargar `qodana.sarif.json` del artefacto `qodana-report`.
2. Guardarlo como `backend/qodana.sarif.json` y commitearlo.
3. Si la ruta del baseline no se resolviera, cambiar el argumento a
   `--baseline,backend/qodana.sarif.json` (research 3).

## 1. PR limpio (Historia 1, esc. 2; Historia 2, esc. 1)

1. Abrir un PR que cambie un comentario en un archivo de `backend/src/`.
2. Esperar el check `qodana`.
3. **Esperado**: verde; comentario del PR indica sin problemas nuevos; `ci-gate` verde. Los problemas
   heredados no aparecen como fallo. Esto confirma además que los umbrales cuentan solo lo nuevo
   (research 4).

## 2. PR con problema de severidad alta (Historia 1, esc. 3)

1. En una rama, introducir una desreferencia nula evidente o código inalcanzable en una clase del
   backend (no commitear a `main`).
2. Abrir el PR.
3. **Esperado**: `qodana` rojo, con el problema señalado en el comentario y las anotaciones;
   `ci-gate` rojo; el SARIF se publica igualmente (Historia 3, esc. 4).

## 3. PR con problema de severidad menor (Historia 1, esc. 4)

1. Introducir una advertencia de baja severidad (p. ej. una variable local sin usar).
2. **Esperado**: verde; el problema aparece solo como informativo.

## 4. PR de documentación (Historia 1, esc. 5)

1. Abrir un PR que solo cambie `README.md`.
2. **Esperado**: `qodana` omitido; `ci-gate` verde.

## 5. Cambio de configuración (Historia 4, esc. 4)

1. Abrir un PR que solo cambie `backend/qodana.yaml`.
2. **Esperado**: `qodana` se ejecuta.

## 6. Resultados en GitHub (Historia 3)

1. Tras el escenario 2, abrir Security > Code scanning.
2. **Esperado**: los hallazgos de Qodana asociados al commit. En un segundo push al mismo PR, el
   comentario se actualiza y no se duplica.

## 7. Fork y secretos (Historia 4, esc. 2-3)

1. Abrir un PR desde un fork (o simularlo con la condición del job).
2. **Esperado**: el análisis corre sin secretos; no se publican comentario, anotaciones ni SARIF; sin
   fallos por falta de permisos.
3. Revisar el repo y los logs: no debe aparecer ningún token (SC-005).

## 8. Baseline y falsos positivos

1. Corregir un problema heredado: desaparece sin marcarse como regresión (Historia 2, esc. 2).
2. Suprimir un falso positivo con `ignore` en `qodana.yaml` en un PR revisado: el gate sigue activo.

## Criterio de salida

Escenarios 1-5 cumplidos y escenarios 6-7 verificados; si el escenario 1 fallara por deuda
heredada, aplicar el plan B de research 4.
