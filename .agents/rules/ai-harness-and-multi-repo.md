# AI Harness Discipline & Multi-Repository Governance

## What this pattern is
Mandatory operating rules for all AI assistant sessions in ShopLab Helpdesk to ensure consistency with the team's AI starter kit, prevent misrouted commits across repositories, and guarantee complete auditability in pull requests.

## Non-Negotiable Rules

### 1. Multi-Repository Scope & Infrastructure Changes
The ShopLab Helpdesk ecosystem is partitioned across multiple dedicated Git repositories:
* **`shoplab-helpdesk`** (this repo): Source code for modular monolith / core services, design specifications under `specs/LAB-<n>-<slug>/`, and architecture docs under `docs/`.
* **`shoplab-infra`** (sibling repo at `../shoplab-infra`): Dedicated repository for Kubernetes manifests (`apps/<service>/base`, `apps/<service>/overlays/dev`), Argo CD resources (`k8s/argocd/`), Terraform, and cluster deployment scripts.

**Rule:** When a story involves cluster deployments, Argo CD (`Application`, `ApplicationSet`), Kustomize overlays, or deployment scripts:
1. NEVER place executable infrastructure manifests in `shoplab-helpdesk`.
2. Navigate to `../shoplab-infra`.
3. Create/checkout the story branch (e.g. `feature/LAB-<n>-<slug>`).
4. Commit, test, push, and open the Pull Request directly in `shoplab-infra`.

### 2. Token Usage Telemetry (`.token-usage.json`)
Every feature spec folder MUST contain `.token-usage.json`:
* In runtimes without automatic stdin statusline hooks (such as Google Antigravity / Gemini), the agent MUST explicitly write and maintain `specs/<spec-folder>/.token-usage.json` recording:
  * `input_tokens`, `output_tokens`, `turns`, `cost_usd`, and tool key (`"antigravity"`).
* Never omit `.token-usage.json` in completed feature specs.

### 3. Pull Request Template Enforcement
All Pull Requests (both in `shoplab-helpdesk` and in `shoplab-infra`) MUST be filled using the complete AI starter kit template:
* `## Description`
* `## Related issue / task` (`[LAB-<n>]`)
* `## Type of change`
* `## How was this tested?`
* `## Token usage (if .token-usage.json exists)` (report real numbers from `.token-usage.json`)
* `## Checklist`
* `## Compatibility / breaking changes`
* `## Rollback plan`

### 4. Session Canary and Skill Transparency
* **First Turn:** Print the canary verification line (`session-identity-canary.md`).
* **Every Turn:** Disclose callable skills and context-skills (`skill-transparency.md`) before substantive output.
