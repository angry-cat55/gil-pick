---
name: "speckit-taskstoissues"
description: "Group existing feature tasks into actionable, dependency-ordered GitHub issues sized for one owner, branch, and pull request."
compatibility: "Requires spec-kit project structure with .specify/ directory"
metadata:
  author: "github-spec-kit"
  source: "templates/commands/taskstoissues.md"
---


## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Pre-Execution Checks

**Check for extension hooks (before tasks-to-issues conversion)**:
- Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.before_taskstoissues` key
- If the YAML cannot be parsed or is invalid, skip hook checking silently and continue normally
- Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
- For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
  - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
  - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
- When constructing command invocations from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `$speckit-git-commit`.
- For each executable hook, output the following based on its `optional` flag:
  - **Optional hook** (`optional: true`):
    ```
    ## Extension Hooks

    **Optional Pre-Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```
  - **Mandatory hook** (`optional: false`):
    ```
    ## Extension Hooks

    **Automatic Pre-Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}

    Wait for the result of the hook command before proceeding to the Outline.
    ```
    After emitting the block above you MUST actually invoke the hook and wait for it to finish before continuing. Run it the same way you would run the command yourself in this agent/session (the invocation may differ from the literal `{command}` id shown above, e.g. a skills-mode agent runs it as `/skill:speckit-...` or `$speckit-...`). Emitting the block alone does not run the hook.
- If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently

## Outline

1. Run `.specify/scripts/powershell/check-prerequisites.ps1 -Json -RequireTasks -IncludeTasks` from repo root and parse FEATURE_DIR and AVAILABLE_DOCS list. All paths must be absolute. For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").
1. **IF EXISTS**: Load `.specify/memory/constitution.md` for project principles and governance constraints.
1. From the executed script, extract the path to **tasks**.
1. Get the Git remote by running:

```bash
git config --get remote.origin.url
```

> [!CAUTION]
> ONLY PROCEED TO NEXT STEPS IF THE REMOTE IS A GITHUB URL

1. **Fetch existing issues for deduplication**: Before creating anything, build the set of task IDs you are about to process from `tasks.md` (each is a `T` followed by **at least** three digits, e.g. `T001` — `$speckit-converge` assigns new IDs with `T{M+1:03d}`, which is a floor rather than a cap). Then use the GitHub MCP server's `list_issues` tool to fetch open and closed issues with `perPage: 100` and cursor pagination. Match `\bT\d{3,}\b` against both each issue title and body because grouped issues normally keep task IDs in a body checklist. Mark every matched task ID as already covered. Stop when all task IDs are covered or there are no more pages.
1. **Plan issue groups before creating anything**:
   - A task is a detailed implementation/checking step; an issue is a cohesive unit that one assignee can complete on one branch and in one pull request. Do not create one issue per task by default.
   - Group tasks only when they have the same assignee and ownership area, contribute to one implementation slice, have compatible predecessor constraints, and can be reviewed together without obscuring completion.
   - Setup and tests may be grouped with their implementation when the same owner and change set need them. Split tasks when owners or platforms differ, when they can be independently delivered, when the combined review would be too broad, or when a separate integration checkpoint is required.
   - Preserve task-level ordering inside each group. `[P]` permits parallel execution after predecessors are satisfied but does not by itself require a separate issue.
   - Put cross-owner end-to-end verification in a separate integration issue with one agreed assignee and explicit review/verification responsibilities for the other owner.
   - If task owners are missing, conflict within a proposed group, or cannot be mapped to GitHub accounts from repository guidance, stop and ask the user instead of assigning or grouping by guess.
1. **Create one GitHub issue per group** in dependency order:
   - Use a concise title describing the implementation result. Do not enumerate every task ID in the title.
   - In the body, include purpose, scope, assignee/area, completion conditions, test method, related artifacts, and an `- [ ] T### ...` checklist containing every grouped task exactly once. Preserve each task's target, predecessor, and verification metadata.
   - Assign the issue to the single agreed owner. Do not create a group with multiple implementation owners.
   - Record whole-group dependencies as `blocked by #<issue-number>`. If only some tasks depend on an intermediate result from another issue, keep the issue startable and document the task-specific partial dependency and required artifact instead of blocking the whole issue.
   - State that `$speckit-implement` must process only the task IDs listed in the current issue and must not mark unrelated tasks complete.
1. **Verify coverage after creation**: Re-fetch the created issues and confirm that every uncovered task ID appears exactly once, no already-covered task was duplicated, assignees match the agreed task owners, and all whole-group dependency references point to real issues. Report the group count, task coverage, assignments, and any skipped existing coverage.

> [!CAUTION]
> UNDER NO CIRCUMSTANCES EVER CREATE ISSUES IN REPOSITORIES THAT DO NOT MATCH THE REMOTE URL

## Post-Execution Checks

**Check for extension hooks (after tasks-to-issues conversion)**:
Check if `.specify/extensions.yml` exists in the project root.
- If it exists, read it and look for entries under the `hooks.after_taskstoissues` key
- If the YAML cannot be parsed or is invalid, skip hook checking silently and continue normally
- Filter out hooks where `enabled` is explicitly `false`. Treat hooks without an `enabled` field as enabled by default.
- For each remaining hook, do **not** attempt to interpret or evaluate hook `condition` expressions:
  - If the hook has no `condition` field, or it is null/empty, treat the hook as executable
  - If the hook defines a non-empty `condition`, skip the hook and leave condition evaluation to the HookExecutor implementation
- When constructing command invocations from hook command names, replace dots (`.`) with hyphens (`-`). For example, `speckit.git.commit` → `$speckit-git-commit`.
- For each executable hook, output the following based on its `optional` flag:
  - **Optional hook** (`optional: true`):
    ```
    ## Extension Hooks

    **Optional Hook**: {extension}
    Command: `/{command}`
    Description: {description}

    Prompt: {prompt}
    To execute: `/{command}`
    ```
  - **Mandatory hook** (`optional: false`):
    ```
    ## Extension Hooks

    **Automatic Hook**: {extension}
    Executing: `/{command}`
    EXECUTE_COMMAND: {command}
    ```
    After emitting the block above you MUST actually invoke the hook and wait for it to finish before continuing. Run it the same way you would run the command yourself in this agent/session (the invocation may differ from the literal `{command}` id shown above, e.g. a skills-mode agent runs it as `/skill:speckit-...` or `$speckit-...`). Emitting the block alone does not run the hook.
- If no hooks are registered or `.specify/extensions.yml` does not exist, skip silently
