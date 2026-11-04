---
name: commit-and-push
description: Stage relevant changes, commit with a Conventional Commits message, push the branch, and open a pull request filled from the team's PR template. Use when the user asks to commit, push, or open a PR, or after finishing a logical unit of work that's ready for review.
---

# Commit, Push & Open PR — Usage Instructions

## Purpose

Turns a finished change into a reviewable pull request in one flow: stage → commit → push → open
a PR filled from `.github/pull_request_template.md`. Never runs against the repository's default
branch, and never force-pushes.

## When to use it

Use when the user asks to "commit this", "push my changes", "open a PR", or after completing a
scoped piece of work that's ready for review. Do not use for work-in-progress the user explicitly
wants to keep local or uncommitted, and do not chain straight into it after an unrelated task
without the user asking — committing and opening a PR are visible, shared-state actions.

## Steps

1. **Check the branch.** Run `git branch --show-current`. If it matches the repository's default
   branch (check `git remote show origin` or `gh repo view --json defaultBranchRef`), stop and ask
   the user to create a feature branch first. Never commit, push, or open a PR directly against
   the default branch.

2. **Review what would be committed.** Run `git status --short` and `git diff`. Stage only the
   files relevant to the current change (`git add <specific paths>`, or `git add -p` for partial
   files) — never `git add -A` / `git add .` blindly, since that can pull in unrelated local files
   or secrets.

3. **Write a Conventional Commits message** (`type(scope): description`, see
   `<AI_ROOT>/rules/development-guidelines.md`). Summarize the *why*, not a restatement of the diff.

4. **Commit.** `git commit -m "<message>"`.

5. **Push.** `git push -u origin <branch-name>` (the `-u` only matters on the branch's first push).
   Never use `--force` or `--force-with-lease`. If the push is rejected as non-fast-forward, stop
   and ask the user how they want to proceed instead of force-pushing on their behalf.

6. **Check for an existing PR first.** Run `gh pr view --json url` for the current branch. If one
   already exists, report its URL and stop here — do not create a duplicate.

7. **Fill the PR template.** Read `.github/pull_request_template.md` (fallback:
   `<AI_ROOT>/templates/pull_request_template.md` if the repo copy is missing). Fill in:
   - **Description** — summarize the commits on this branch, in your own words
   - **Type of change** — check only the box(es) that match the actual change
   - **How was this tested** — describe only tests/commands you actually ran this session; never
     claim testing you didn't do
   - Leave every other checklist item **unchecked** — those confirm things only the human
     author/reviewer can actually verify (self-review, docs, style), not you
   - **Token usage:** resolve the spec folder as `specs/<last path segment of the branch>/`

8. **Open the PR.**
   ```bash
   gh pr create --title "<title>" --body-file <path-to-filled-template> --base <default-branch>
   ```
   Report the PR URL to the user.

9. **Refresh the architecture graph, if THIS repo has one.**
