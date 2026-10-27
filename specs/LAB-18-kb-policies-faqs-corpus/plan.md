# Implementation Plan: Corpus de políticas y FAQs de la tienda

**Branch**: `feature/LAB-18-kb-policies-faqs-corpus` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-18-kb-policies-faqs-corpus/spec.md`

## Summary

Redactar 24 documentos de políticas y FAQs de la tienda ficticia ShopLab en `data/kb/` (Markdown como fuente, 10 de ellos
también en PDF), con un índice y un registro de revisión manual, y una pequeña herramienta Python en `data/kb-tools/`
que genera los PDF a partir del Markdown y valida el corpus (FR-013).

Enfoque técnico (detalle en [research.md](research.md)):

- **Contenido = Markdown con front matter**: cada documento declara `title`, `topic`, `type`, `language` y `scope`;
  secciones uniformes (`Alcance`, `Cómo funciona`, `Plazos y costos`, `Casos especiales`, `Contacto`) para facilitar la
  fragmentación posterior (F1-02).
- **Una sola fuente de valores**: [contracts/corpus-facts.md](contracts/corpus-facts.md) fija plazos, importes y
  condiciones canónicos (moneda BRL, devolución 30 días, etc.) y los métodos de pago y estados de pedido de Olist; todo
  documento los cita sin variar. Evita contradicciones (FR-007, FR-008).
- **PDF generado, no escrito a mano**: `fpdf2` convierte el Markdown a PDF con texto seleccionable y fecha de creación
  fija (salida reproducible). El Markdown prevalece sobre el PDF.
- **Validador ejecutable**: comprueba recuento 20–30, ≥ 3 documentos por tema, índice = directorio, PDF con texto
  extraíble y equivalente al Markdown, ausencia de datos personales y revisión registrada. Corre en pytest y en CI.
- **Sin código de producción**: el backend, la base de datos y la infraestructura no cambian. La ingesta (indexado) es
  de la siguiente historia.

## Technical Context

**Language/Version**: Python 3.12 (herramienta `data/kb-tools/`); contenido en Markdown (CommonMark) y PDF.

**Primary Dependencies**: `fpdf2` (generación PDF), `markdown-it-py` (parseo de Markdown), `pypdf` (extraer texto del
PDF para validar), `pyyaml` (front matter), `typer` (CLI); desarrollo: `pytest`, `ruff`. Gestor `uv`, igual que el resto
de los componentes Python.

**Storage**: archivos versionados en `data/kb/` (`.md`, `.pdf`, `INDEX.md`, `REVIEW.md`); sin base de datos.

**Testing**: pytest sobre el validador (casos válidos y casos rotos construidos en directorios temporales) y una prueba
que ejecuta el validador contra el `data/kb/` real. Cobertura JaCoCo no afectada (sin Java).

**Target Platform**: desarrollo local (Windows/Linux) y CI de GitHub Actions.

**Project Type**: contenido documental + herramienta de línea de comandos para generarlo y validarlo.

**Performance Goals**: generar los 10 PDF y validar el corpus completo en menos de 10 segundos.

**Constraints**: sin datos personales ni secretos; PDF con texto seleccionable y reproducibles (mismo Markdown → mismos
bytes); fuentes estándar de PDF (latin-1) bastan para español, el validador rechaza caracteres fuera de ese rango; sin
PDF de imagen; sin dependencias nativas del sistema (instalable con `uv sync` en Windows y Linux).

**Infrastructure**: sin cambios. `shoplab-infra` y `infra/` no se tocan.

**Scale/Scope**: 24 documentos Markdown (~300–600 palabras cada uno), 10 PDF, 2 archivos de control (índice y registro),
1 paquete Python pequeño (~4 módulos), ~15 pruebas.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|---|---|---|
| I. Monolito modular primero | No toca módulos ni esquemas; el corpus vive en `data/kb/` y la herramienta es externa al backend | Pasa |
| II. Hexagonal y dominio puro | Sin código de backend; la herramienta Python es un script de apoyo sin lógica de dominio | N/A |
| III. Identificadores en inglés, errores tipados | Código, nombres de archivo y claves del front matter en inglés; el texto de los documentos en español (permitido). El validador usa excepciones propias con mensaje accionable | Pasa |
| IV. Test-first, cobertura >= 80 % | El validador se desarrolla con pytest primero (casos rotos incluidos); el backend no cambia | Pasa |
| V. RAG con evaluación | No aplica en F1-01 (no hay recuperación). El corpus será la base del conjunto de oro en F2; las preguntas de SC-005 se reutilizarán | N/A |
| VI. Observabilidad y datos seguros | Cero datos personales reales y cero secretos, comprobado por validador y por revisión manual; solo contactos genéricos (`@shoplab.example`) | Pasa |
| VII. Simplicidad y ADR | Sin decisión arquitectónica nueva: no requiere ADR. Un solo paquete Python, sin servicios ni base de datos. Se rechazaron WeasyPrint (dependencias nativas) y un motor de plantillas (YAGNI) | Pasa |

Sin violaciones; **Complexity Tracking** no aplica.

**Re-evaluación post-diseño**: sin cambios; el diseño de Fase 1 añade solo archivos de contenido y una herramienta
pequeña, sin tocar el backend.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-18-kb-policies-faqs-corpus/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── corpus-format.md
│   ├── corpus-facts.md
│   └── kb-tools-cli.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
data/
├── kb/                                  # corpus (contenido versionado)
│   ├── INDEX.md                         # NUEVO: tema, título y formatos de cada documento
│   ├── REVIEW.md                        # NUEVO: registro de revisión manual (revisor y fecha)
│   ├── shipping-01-delivery-times.md    # NUEVO: 24 documentos <topic>-NN-<slug>.md
│   ├── …                                #   shipping (5), returns (5), warranty (4), payments (5), privacy (5)
│   └── <topic>-NN-<slug>.pdf            # NUEVO: 10 PDF generados desde el Markdown (≥ 1 por tema)
├── kb-tools/                            # NUEVO: generador de PDF y validador
│   ├── pyproject.toml
│   ├── uv.lock
│   ├── README.md
│   ├── src/kb_tools/
│   │   ├── cli.py                       # comandos `build-pdf`, `validate`
│   │   ├── document.py                  # lectura de front matter y secciones
│   │   ├── pdf.py                       # Markdown → PDF reproducible
│   │   └── validate.py                  # reglas FR-013 y mensajes accionables
│   └── tests/
│       ├── conftest.py                  # corpus mínimo válido en directorio temporal
│       └── test_*.py
├── olist/                               # existente (LAB-17)
└── raw/                                 # ignorado por git

docs/base-conocimiento.md                # NUEVO: cómo se organiza, cómo añadir una política y regenerar PDF
.github/workflows/ci.yml                 # AJUSTE: añadir `data/kb-tools` y `data/kb` al filtro y a la matriz python
README.md                                # AJUSTE: enlace breve a la base de conocimiento
```

**Structure Decision**: el corpus va solo con contenido en `data/kb/` (carpeta reservada por LAB-9) para que la ingesta
de la siguiente historia pueda leer el directorio completo sin filtrar scripts; la herramienta va en un proyecto
Python hermano `data/kb-tools/`, como `data/olist/` en LAB-17. No se usa `ingest/` porque añadirle un `pyproject.toml`
activaría la rama real de su `Dockerfile` (ver plan de LAB-17).

## Complexity Tracking

No aplica: sin violaciones de la constitución.
