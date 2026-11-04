---
name: "speckit-tasks"
description: "Generate an actionable, dependency-ordered tasks.md for the feature based on available design artifacts."
argument-hint: "Optional task generation constraints"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/tasks.md"
user-invocable: true
disable-model-invocation: false
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Pre-Execution Checks

**Check for extension hooks (before tasks generation)**:
- Check if `.specify/extensions.yml` exists in the project root.

## Outline

1. **Setup**: Run `.specify/scripts/powershell/setup-tasks.ps1 -Json` from repo root.

2. **Load design documents**: Read plan.md, spec.md, data-model.md, contracts/, research.md, quickstart.md.

3. **Execute task generation workflow**:
   - Organize tasks by user story (P1, P2, P3...)
   - Generate dependency graph
   - Validate task completeness

4. **Generate tasks.md**: Fill setup, foundational, user story phases, and cross-cutting concerns.
