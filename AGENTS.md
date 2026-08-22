# Gilpick Agent Guide

## Shared source of truth

- This file defines repository-wide instructions for every coding agent.
- Treat `.specify/memory/constitution.md` as the project governance source of truth once the team ratifies it.
- Treat each feature directory under `specs/` as the source of truth for that feature's requirements, plan, and tasks.
- Keep agent-specific files thin. Do not duplicate shared rules in `CLAUDE.md` or agent skills.

## Spec Kit workflow

- Start a new feature by creating or updating its specification before implementation.
- Complete the flow in this order: constitution, specify, clarify when needed, plan, tasks, analyze when needed, implement.
- Do not silently invent missing product requirements. Record open questions in the specification and ask the team when the answer changes scope or behavior.
- Keep `spec.md`, `plan.md`, and `tasks.md` aligned with the implemented behavior.
- Do not manually edit generated files under `.agents/skills/`, `.claude/skills/`, or managed `.specify/` infrastructure unless the task is explicitly a Spec Kit customization or upgrade.

## GitHub Flow

- Use `main` as the only long-lived branch. Do not commit or push directly to `main`.
- Name branches `<type>/<initials>-<short-description>`.
- Allowed types are `feat`, `fix`, `docs`, `refactor`, `test`, and `chore`.
- Keep commits and pull requests focused on one specification or maintenance concern.
- Rebase or merge the latest `origin/main` before requesting final review, then resolve and test conflicts locally.
- Require at least one teammate review before merging a pull request.

## Engineering expectations

- Separate confirmed facts, reasonable inferences, and unverified assumptions in research and technical decisions.
- Prefer the smallest implementation that satisfies the approved specification.
- Add or update tests for behavior changes. Run the relevant test and static-analysis commands before requesting review.
- Report commands that could not be run and the reason; never claim unexecuted checks passed.
- Never commit secrets, credentials, local agent settings, generated build outputs, or machine-specific paths.
- Avoid unrelated formatting or refactoring in feature pull requests.

## Collaboration

- Before editing, inspect the active specification and nearby code rather than relying only on conversation context.
- If two tasks touch the same files or interface, coordinate ownership before implementation.
- Document important architectural decisions and tradeoffs in the feature plan.
- Keep user-facing documentation synchronized with behavior changes.
