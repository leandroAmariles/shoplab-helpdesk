---
name: "speckit-specify"
description: "Create or update the feature specification from a natural language feature description."
argument-hint: "Describe the feature you want to specify"
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/specify.md"
user-invocable: true
disable-model-invocation: false
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Pre-Execution Checks

**Check for extension hooks (before specification)**:
- Check if `.specify/extensions.yml` exists in the project root.

## Outline

1. Run `.specify/scripts/powershell/setup-spec.ps1 -Json` from repo root.

2. Load `.specify/memory/constitution.md` and feature templates.

3. **Draft specification**:
   - Define User Scenarios and Acceptance Criteria (Given/When/Then).
   - Define Functional Requirements (FR-001, FR-002...).
   - Define Key Entities and Edge Cases.
