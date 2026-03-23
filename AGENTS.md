# AGENTS.md

## Operating Rules

You must maintain an append-only development log at:

`docs/dev-log.md`

This log is a required artifact for all meaningful work in this repository.

## Append-Only Logging Policy

When performing any meaningful task, you must append a new entry to `docs/dev-log.md`.

A meaningful task includes:
- planning a feature
- making code changes
- refactoring
- debugging
- fixing test failures
- changing Docker or environment setup
- updating developer workflow
- making architectural decisions
- revising prior implementation approach

Do not log trivial read-only actions unless they materially affect the direction of the work.

## Strict Constraints

- The log is append-only.
- Never modify, rewrite, summarize, reorder, or delete previous entries.
- Never replace the full file when adding a new entry.
- Always read the existing file first, then append to the end.
- Preserve all prior content exactly as-is.
- If the file does not exist, create it with the required header and then append.
- If an earlier entry is inaccurate, add a new correction entry. Do not edit the old one.
- If asked to “clean up” or “dedupe” the log, refuse that change and append a note instead.

## Entry Format

Each appended entry must follow this structure exactly:

---
Timestamp: YYYY-MM-DD HH:MM local

User Request:
<short paraphrase of the user’s request>

Codex Plan:
- <2 to 6 bullets describing intended approach>

Actions Taken:
- <specific files created/updated/read>
- <commands run, if relevant>
- <tests run, if relevant>

Outcome:
<what changed, what worked, what failed, and current status>

Notes:
<risks, assumptions, tradeoffs, blockers, or next steps>
---

## Logging Quality Rules

- Be concise but specific.
- Prefer concrete facts over vague summaries.
- Name files and components explicitly.
- Mention commands only when they matter.
- Mention tests only when run.
- Mention failures honestly.
- Do not claim completion if work is partial.
- Do not omit the plan. The plan is required for each meaningful task.
- Do not include secrets, API keys, tokens, credentials, or private environment values.
- Do not paste large code blocks into the log.
- Do not dump full diffs into the log.

## Planning Requirement

Before making meaningful code or config changes, first think through the plan and include that plan in the log entry.

The log must capture both:
1. what the user asked for
2. what you planned to do before editing

This planning step is mandatory and should not be skipped even if the task seems small.

## Failure Handling

If you are unable to complete a task, still append a log entry that includes:
- intended plan
- what was attempted
- what failed
- what remains unresolved

## Priority

These logging rules apply to all repository work unless the user explicitly instructs otherwise.
If there is a conflict between convenience and logging compliance, logging compliance wins.

## Working Style

- Avoid over engineering.
- Work step by step.
- Start simple and only introduce complexity to solve an actual problem.
- Do not write code until explicitly told to do so.
- Keep responses concise.

## Deliverable Workflow

- Write RFCs in `docs/rfc`.
- Write execution plans in `docs/plans`.
- Name execution plans with a numeric prefix such as `001-` so future plans can be ordered consistently.
