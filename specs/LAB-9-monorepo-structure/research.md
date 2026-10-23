# Research: Monorepo con la estructura acordada

No quedaban `NEEDS CLARIFICATION` en el Technical Context. Las decisiones siguientes cierran los
puntos de diseño de la historia.

## D1. Texto de la licencia

- **Decision**: Texto estándar de la licencia MIT (plantilla SPDX `MIT`), línea
  `Copyright (c) 2026 Leandro Amariles`, archivo `LICENSE` en la raíz sin extensión.
- **Rationale**: Elegida por el usuario. El nombre `LICENSE` y el texto SPDX exacto permiten que
  GitHub la reconozca automáticamente (SC-005).
- **Alternatives considered**: Apache-2.0 (más larga, concesión de patentes que no se necesita en
  un laboratorio); sin licencia (impide el uso por terceros).

## D2. Cómo documentar el arranque cuando `docker compose` aún no existe

- **Decision**: El README lista prerrequisitos (git, Docker, JDK 21, Node, Python), el comando
  objetivo `docker compose -f infra/docker-compose.yml up` y lo marca explícitamente como
  "disponible desde F0-02 (LAB)". No se inventan comandos de arranque por componente.
- **Rationale**: FR-004 exige no mostrar comandos que fallan; el README actual ya enlaza el
  comando con F0-02.
- **Alternatives considered**: Omitir la sección hasta F0-02 (incumple el criterio de
  aceptación); documentar arranque por componente (aún no existe código).

## D3. Conservación de carpetas vacías

- **Decision**: Mantener `.gitkeep` en cada carpeta sin contenido; retirarlo cuando una historia
  posterior añada archivos reales.
- **Rationale**: Git no versiona carpetas vacías (FR-002); `.gitkeep` es la convención habitual y
  ya se usa en el repo.
- **Alternatives considered**: `README.md` por carpeta (más ruido y duplica el README raíz).

## D4. Verificación de coincidencia README/estructura

- **Decision**: Script de shell en el quickstart que compara las carpetas de primer nivel
  versionadas con las de la tabla del README.
- **Rationale**: Hace medible SC-003 sin añadir herramientas ni CI en esta historia.
- **Alternatives considered**: Job de CI (fuera de alcance, llega en fases posteriores);
  revisión manual solamente (no repetible).

## D5. Exclusiones de control de versiones

- **Decision**: Conservar las entradas actuales de `.gitignore` (`data/raw/`, configuración local
  de Claude, telemetría por spec) y verificarlas con `git status` tras uso normal.
- **Rationale**: Cubre FR-009 / SC-004 sin cambios; solo se tocará si la verificación detecta
  fugas.
- **Alternatives considered**: Reescribir `.gitignore` por completo (cambio innecesario).
