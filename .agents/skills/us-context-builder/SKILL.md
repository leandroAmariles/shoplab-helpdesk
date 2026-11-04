---
name: us-context-builder
description: Given an Azure DevOps work item URL (User Story, Bug, Task), fetch its title, description, acceptance criteria, discussion, links and images through the REST API and write a self-contained Markdown context file under .ai/us-context/US<id>/context.md. Use when the user shares a dev.azure.com/_workitems/edit/<id> link, or before /speckit-specify or /opsx-propose so the spec starts from the real US instead of a pasted summary.
---

# us-context-builder — Usage Instructions

## Purpose

Azure DevOps pages need authentication, so agents cannot read a work item from its URL alone. This
skill runs `us_context.py` (same folder, standard library only) with a Personal Access Token and turns
the work item into a Markdown file an agent can read: metadata, description, acceptance criteria,
repro steps, comments, related links, and the images (downloaded locally, referenced relatively).

## Prerequisites

- Python 3.8+.
- `AZURE_DEVOPS_PAT` in the environment, with the **Work Items (Read)** scope.

## Steps

1. Take the work item URL from the user (`https://dev.azure.com/<org>/<project>/_workitems/edit/<id>`).
2. Run from the repo root:
   `python .ai/skills/us-context-builder/us_context.py "<url>"`.
3. Read the generated `.ai/us-context/US<id>/context.md`.
4. Report what was captured.
5. If the user wants to specify it next, hand off to `/speckit-specify`.
