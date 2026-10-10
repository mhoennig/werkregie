# PR#7: The Shared Agentic Workflow

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkrevisor PR#78](https://git.javagil.de/mi/werkrevisor/pulls/78): the master copy of `docs/agentic-workflow.md`.

## Problem

All werk repositories share one workflow with AI agents, described in `docs/agentic-workflow.md`.
werkregie, the orchestrator of that workflow, has neither the document nor `AGENTS.md`, `CLAUDE.md` or PR documents.
werkregie takes the master copy over once every repository has the document.

## Non-Goals

- No werkator CI and no review by werkrevisor yet; see "Follow-up PRs".
- No rename of `doc/` to `docs/`: the human has uncommitted work in `doc/`.

## Solution

- `doc/agentic-workflow.md` is the shared document, copied unchanged from werkrevisor.
- `AGENTS.md` refers to it, describes build and architecture, and states the exceptions of werkregie:
  its documents live in `doc/`, not `docs/`, and it takes the master copy over once every repository has it.
- `CLAUDE.md` imports both.
- `doc/prs/README.md` and `doc/prs/TEMPLATE.md` give the PR documents their structure, as in werkrevisor.
- The Werkbaum gets `#ops.workflow` and `#ops.master`.
- Version 0.3.2 of the dashboard.

## Follow-up PRs

- `.werkrevisor.toml` and the review by werkrevisor, once werkator builds werkregie (`#ops.werkator`, `#ops.werkrevisor`).
