# PR#16: werkregie Holds the Master Copy of the Workflow

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#7](https://git.javagil.de/mi/werkregie/pulls/7): the shared workflow in werkregie.
- [werkcrappit PR#54](https://git.javagil.de/mi/werkcrappit/pulls/54): aligns its copy with the text of werkregie.

## Problem

`doc/agentic-workflow.md` says that werkrevisor holds the master copy until every werk repository has the document.
werkdock and werkjournal have none yet.
`mi` decided on 2026-10-11 that werkregie holds the master copy from now on.

`AGENTS.md` also still says that werkregie has no CI and no review by werkrevisor;
both run since werkregie PR#9 and PR#10.

## Non-Goals

- No change of the other repositories; each one takes the new text over with its next PR.
- No change of the release notes line: werkator's copy says "a refactoring gets none"
  instead of "any other change gets one marked as internal".
  `mi` decided on 2026-10-11 that the master copy keeps its line; werkator takes it over with its next PR.

## Solution

- `doc/agentic-workflow.md`: werkregie holds the master copy, in `doc/agentic-workflow.md`.
- `AGENTS.md`: werkregie holds the master copy; the point about the missing CI and review is gone.
- Werkbaum: `#ops.master` is done.
- Version 0.7.1.

## Open Questions

- [x] Should line 3, "each one keeps it as `docs/agentic-workflow.md`", name werkregie's exception?
  No: the text stays the same in every repository, and `AGENTS.md` holds the exceptions of a repository;
  werkregie's `AGENTS.md` states that its documents live in `doc/`.

## Additional Changes

- Werkbaum: `#ops.werkrevisor` is done; branch protection and the reviewer's rights are set.
