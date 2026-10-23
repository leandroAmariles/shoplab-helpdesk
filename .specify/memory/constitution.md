<!--
Sync Impact Report (temporary - remove before committing)
- Version change: (template, unversioned) -> 1.0.0
- Modified principles: none (initial ratification; all placeholders replaced)
- Added sections: Core Principles I-VII, Technology & Architecture Constraints,
  Development Workflow & Quality Gates, Governance
- Removed sections: none
- Deferred / TODO: none. Ratification date set to the date of the first accepted ADRs
  (2026-10-23). Coverage threshold and Checkstyle/PMD come from
  .ai/rules/development-guidelines.md and apply once backend code exists.
- Reconciliations to confirm: commit format combines README ("LAB-123" prefix) with
  Conventional Commits; branch slug combines spec-branch-naming rule with README
  (feature/LAB-123-description).
-->
# ShopLab Helpdesk Constitution

## Core Principles

### I. Modular Monolith First, Extract Later
Phases F0-F3 MUST run as a single Spring Boot modular monolith (Spring Modulith). Modules
communicate through in-process domain events and MUST NOT share tables or reach into each
other's internals. Module boundaries MUST be verified by Modulith architecture tests. Extraction
into services (Kafka events, CQRS, per-service databases) happens only from F4/F5 onward and
requires an ADR. Rationale: learn RAG before paying distributed-systems cost (ADR-0001).

### II. Hexagonal Architecture and Domain Purity
Each module MUST separate domain, application (use cases / ports) and infrastructure
(adapters). Controllers contain no logic: they build a command and call an inbound port. Use
cases MUST NOT depend on infrastructure or network clients; adapters resolve external
dependencies and map with explicit mappers. Domain constants live in `model/constants`
(`@UtilityClass`) and business enums in `model/enums`. LLM, embedding and vector-store access
MUST sit behind ports so providers can be swapped.

### III. English Identifiers, Typed Errors
All code identifiers (classes, methods, fields, constants) MUST be in English; documentation and
user-facing text MAY be Spanish. Errors are thrown as typed exceptions from any layer and mapped
to HTTP responses only in the central `GlobalExceptionHandler`; adapters and use cases MUST NOT
build `ResponseEntity` error responses. Error codes live in a single `ResponseCodeEnum`.

### IV. Test-First with Coverage Gate (NON-NEGOTIABLE)
New behavior MUST have tests written before or with the implementation. Backend line coverage
MUST stay >= 80% (JaCoCo; `dto`, `config`, `model`, mapper and `exception` packages excluded and
therefore logic-free). Integration tests MUST cover each module's external contracts (REST,
database with pgvector, messaging once introduced). Tests are deterministic: LLM calls are
stubbed or recorded in CI.

### V. Evaluation-Driven RAG
From F2, every pull request MUST run the automatic RAG evaluation (golden dataset in `eval/`,
RAGAS / DeepEval) and MUST NOT degrade the agreed metrics (faithfulness, answer relevance,
context precision/recall). Answers grounded in the knowledge base MUST include citations to their
source chunks. Prompt, chunking, embedding or retrieval changes without an evaluation result are
rejected. Rationale: without measurement, RAG changes are guesses.

### VI. Observability and Safe AI Handling
Services MUST emit structured logs with correlation ids, metrics and traces covering each stage
of the RAG/agent pipeline (retrieval, prompt, model call, tool call), including latency and token
usage. Secrets and API keys MUST come from environment/config, never from source control. Real
customer data MUST NOT be committed; fixtures use the public Olist dataset or obvious
placeholders. Agent tools that mutate state MUST validate input and be auditable.

### VII. Simplicity and Documented Decisions
Start with the simplest design that satisfies the current phase (YAGNI). Complexity beyond the
phase plan MUST be justified in the plan's complexity tracking. Significant architectural
decisions (database, messaging, module extraction, retrieval strategy) MUST be recorded as ADRs
in `docs/adr/` using the template. PostgreSQL 16 + pgvector is the single data store until F5
(ADR-0002).

## Technology & Architecture Constraints

- **Backend**: Java 21, Spring Boot 3, Spring AI, Spring Modulith (`backend/`).
- **Frontend**: Angular with streaming chat, agent console and admin (`frontend/`).
- **Ingestion & evaluation**: Python / FastAPI (`ingest/`), golden dataset and metrics (`eval/`),
  experiments in `notebooks/` (never imported by production code).
- **Data**: PostgreSQL 16 with pgvector (HNSW) and `tsvector`/GIN for full-text; hybrid search is
  resolved in SQL. Knowledge base and loaders live in `data/`.
- **Infrastructure**: docker compose, Keycloak and observability stack under `infra/`; Kubernetes
  deployment arrives in F7. Local startup MUST remain a single
  `docker compose -f infra/docker-compose.yml up`.
- **Style gates**: no PMD or Checkstyle violations; max line length 121; braces on all blocks.
- The phased roadmap (F0-F7) in `README.md` defines scope; work outside the current phase needs
  explicit approval.

## Development Workflow & Quality Gates

- The backlog lives in Jira project `LAB`; every change traces to a story `LAB-<n>`.
- Branches: `<type>/LAB-<n>-<slug>` where type is feature, fix, hotfix, chore, refactor or docs.
  For spec-kit, the spec directory name MUST equal the branch's last segment.
- Commits: Conventional Commits with the story key first:
  `LAB-<n> type(scope): description in english`. One commit = one logical change.
- Flow: specify -> (clarify) -> plan -> tasks -> (analyze) -> implement. After implementing an
  HTTP endpoint, generate `requests.http` in the spec folder covering the spec's scenarios.
- Before committing: run affected-module tests, Checkstyle and coverage; review `git status` and
  `git diff --staged` for stray files.
- Pull requests MUST use the PR template: CI green, tests added or updated, RAG evaluation not
  worse (from F2), and docs/ADR updated when applicable.

## Governance

This constitution supersedes other practices in this repository; where it conflicts with
`.ai/rules/*` or `.ai/context/*`, the constitution wins and the conflicting file MUST be fixed.
Runtime guidance lives in `.ai/rules/development-guidelines.md`, `.ai/context/skills/*` and
`README.md`.

- **Amendments**: proposed via pull request that edits this file, states the rationale and
  migration impact on existing specs/plans, and is approved by the repository maintainers.
- **Versioning** (semantic): MAJOR for removing or redefining a principle in a backward
  incompatible way; MINOR for adding a principle/section or materially expanding guidance; PATCH
  for clarifications and wording.
- **Compliance**: every plan MUST pass a Constitution Check against these principles, and every
  pull request review MUST verify compliance. Deviations MUST be justified in writing and, if
  lasting, recorded as an ADR.

**Version**: 1.0.0 | **Ratified**: 2026-10-23 | **Last Amended**: 2026-10-23
