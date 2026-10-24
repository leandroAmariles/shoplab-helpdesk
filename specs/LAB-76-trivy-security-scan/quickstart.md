# Quickstart: validar el escaneo con Trivy

## Prerrequisitos

- LAB-15 en la rama (existe `ci.yml` con `ci-gate`).
- Code Scanning habilitado en el repositorio (si no, el SARIF queda solo como artefacto).
- Opcional: Docker o el binario de Trivy y `actionlint` para ensayar en local.

## 0. Ensayo local (antes del PR)

```bash
trivy fs . --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --ignore-unfixed \
  --skip-dirs node_modules,target,.venv --ignorefile .trivyignore --exit-code 1
actionlint .github/workflows/ci.yml
```

Esperado: exit 0 sobre el repo limpio; `actionlint` sin errores.

## 1. PR limpio (historia 1, escenario 2)

1. Abrir el PR de LAB-76.
2. Esperado: `trivy` y `ci-gate` en verde; el job corre aunque el PR solo toque `.github/` o docs.

## 2. Vulnerabilidad con corrección (escenarios 3 y 4)

1. En una rama de prueba, fijar en `backend/pom.xml` una dependencia con CVE CRITICAL ya corregido
   en una versión posterior (p. ej. una versión antigua de `jackson-databind`).
2. Esperado: `trivy` en rojo; el paso "Trivy gate" muestra paquete, versión instalada y corregida.
3. Una vulnerabilidad sin versión corregida no debe bloquear.

## 3. Secreto (escenario 5)

1. En la rama de prueba, añadir un archivo con una clave falsa con formato AWS de ejemplo
   (`AKIAIOSFODNN7EXAMPLE`) — obviamente de prueba.
2. Esperado: rojo; el log da archivo y regla con el valor enmascarado (FR-009). Descartar la rama.

## 4. Excepción (historia 3)

1. Añadir el id del hallazgo del paso 2 a `.trivyignore` con motivo, responsable y fecha.
2. Esperado: `trivy` en verde. Quitar la entrada: vuelve a rojo.

## 5. Pestaña Security (historia 2)

1. Tras la ejecución del paso 2 (rojo), abrir Security > Code scanning.
2. Esperado: alertas de la categoría `trivy` asociadas al commit; el artefacto `trivy-report` existe.
3. Tras el merge a `main`, las alertas reflejan la rama principal.

## 6. Dependabot (historia 4)

1. Tras el merge, abrir Insights > Dependency graph > Dependabot.
2. Esperado: cinco ecosistemas listados; directorios aún sin manifiesto aparecen con aviso, sin
   afectar al CI. Aparecen PRs de actualización con el pipeline (incluido `trivy`) ejecutándose.

## 7. Forks y Dependabot

1. En un PR de Dependabot (o de fork), verificar que el gate corre y que el paso de subida de SARIF
   se omite sin fallo, con el SARIF disponible como artefacto.
