# Quickstart: validar el registro de ADR

Guía de verificación manual de LAB-16. Prerrequisito: rama `docs/LAB-16-adr-registry` con los
cambios aplicados. Los comandos se ejecutan desde la raíz del repositorio (shell tipo Bash).

## 1. Plantilla (US1, FR-001 a FR-003)

```bash
grep -nE "Estado|Fecha|## Contexto|## Decisión|## Consecuencias|Sustituye" docs/adr/0000-plantilla.md
```

Esperado: aparecen título numerado, estado con los tres valores permitidos, fecha, las tres
secciones y el campo opcional `Sustituye a`. La regla de numeración figura en la plantilla o se
enlaza al índice.

## 2. ADR 0001 y 0002 (US2, US3, FR-004, FR-005, FR-007)

```bash
for f in docs/adr/0001-*.md docs/adr/0002-*.md; do
  echo "== $f"; grep -c "^- Estado: aceptado" "$f"
  for s in "## Contexto" "## Decisión" "## Consecuencias"; do grep -c "^$s" "$f"; done
done
```

Esperado: cada archivo imprime `1` cinco veces (estado aceptado y tres secciones). Revisar a
ojo que cada sección tiene texto: 0001 menciona F0–F3 y F4; 0002 menciona base única, índice
vectorial, texto completo y F5.

## 3. Convención de nombres y orden (FR-006)

```bash
ls docs/adr | grep -vE '^([0-9]{4}-[a-z0-9-]+\.md|README\.md)$'
```

Esperado: sin salida. Además, `ls docs/adr` muestra los archivos en orden numérico.

## 4. Coherencia de todos los ADR (FR-007, FR-010)

```bash
for f in docs/adr/000[1-9]-*.md; do
  for s in "- Estado:" "- Fecha:" "## Contexto" "## Decisión" "## Consecuencias"; do
    grep -q "^$s" "$f" || echo "FALTA '$s' en $f"
  done
done
```

Esperado: sin salida.

## 5. Índice y descubrimiento (US4, FR-008, SC-004)

```bash
grep -n "docs/adr" README.md
grep -nE "0001|0002|0003|0004" docs/adr/README.md
```

Esperado: el README raíz enlaza a `docs/adr/`; el índice lista los cuatro ADR con su estado, y
explica cómo añadir un ADR, la regla de numeración y la regla de sustitución sin editar ni borrar
el original.

## 6. Prueba de usuario nuevo (SC-001, SC-003)

Pedir a alguien que no conozca el proyecto que cree un ADR ficticio desde la plantilla y que
explique, leyendo solo 0001 y 0002, por qué hay un monolito modular con una base única. Debe
lograrlo en menos de 10 y 5 minutos respectivamente. Descartar el ADR ficticio al terminar.

## 7. Limpieza

```bash
git status --short
```

Esperado: solo cambios en `docs/adr/`, `README.md` y `specs/LAB-16-adr-registry/`.
