---
name: loop-spec
description: Revise a spec-kit feature's spec.md after implementation started (or finished) - scoped to only the requirement, scenario, or entity that broke or changed - then cascade that same scoped edit into plan.md and tasks.md so the SDD cycle can resume on just the delta, handing off to /speckit-implement. Use when a bug reveals a wrong/missing requirement, or a stakeholder decision changes scope, for a spec-kit feature. Never for drafting a brand-new feature (use /speckit-specify) and never to regenerate an entire spec/plan/tasks file at once.
---

# loop-spec — Usage Instructions

## Purpose

spec-kit's own cycle (`constitution → specify → clarify → plan → tasks → analyze → implement`) is
built to run forward once: `/speckit-plan` and `/speckit-tasks` each regenerate their artifact from
the current `spec.md`. That's the right tool for standing up a new feature, but it has no scoped
"just fix this one requirement" mode - re-running it after implementation has already started would
blow away completed-task history and any plan/tasks content unrelated to the fix.

`loop-spec` is that missing scoped-update step, the spec-kit equivalent of this kit's
`openspec-update-change`. It closes the loop when reality diverges from the spec mid-flight or
after the fact: find the smallest edit to `spec.md`, cascade only what that edit actually touches
into `plan.md` and `tasks.md`, and hand off to `/speckit-implement` to execute just the delta - so
the feature can go through **plan → tasks → implement** again without redoing everything.

## When to use it

- Implementation (or QA, or a later bug report) revealed that a requirement, acceptance scenario,
  or key entity in `spec.md` was wrong, ambiguous, or incomplete.
- A stakeholder decision changed the scope of one part of an already-specified feature.
- Tasks are fully checked off and the feature shipped, but the discovered issue means the spec no
  longer matches what should exist - a post-implementation correction, not new work.

Do **not** use it to:
- Draft a brand-new feature - use `/speckit-specify` for that.
- Make a sweeping redesign that touches most of the spec - at that point re-running
  `/speckit-plan`/`/speckit-tasks` in full (or a fresh `/speckit-specify`) is the honest choice; say
  so and stop rather than forcing a "scoped" edit that isn't one.
- Edit application code - this skill never leaves the planning artifacts. The actual code change is
  `/speckit-implement`'s job, invoked after this skill hands off.

## Steps

1. **Confirm spec-kit is installed and find the feature.**
   - Check `.specify/` exists.
   - Resolve the active feature directory under `specs/`. Prefer the one matching
     `git branch --show-current`.

2. **Read current state from disk - never from conversation memory.**
   - Read the feature's `spec.md`, and `plan.md`/`tasks.md` if they exist.

3. **Understand what changed.**

4. **Make the scoped edit to `spec.md`.**

5. **Cascade into `plan.md`, scoped to the same delta.**

6. **Cascade into `tasks.md`, scoped to the same delta.**

7. **Hand off - don't implement.** Point to `/speckit-implement`.
