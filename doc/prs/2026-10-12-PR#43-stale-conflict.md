# PR#43: Du bist dran Shows a Conflict the Agent Leaves

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

User request of 2026-10-12: a pull request with a merge conflict that its coding agent does not react to
shows under "Du bist dran".

On 2026-10-12, PR#41 and PR#42 got conflicts after other pull requests merged into `main`.
Their agents did not react until the user noticed it.
Today a conflict marks only the user's own pull requests; a foreign one shows "Konflikt" in its detail line only.

## Non-Goals

- No change of the flags of the user's own pull requests; a conflict there stays Nacharbeit at once.
- No flag for a draft: an agent that works on it is not stuck.
- No exact time of the conflict: Gitea tells only that a pull request conflicts now, not since when.

## Specification

### Feature: Du bist dran shows a conflict the agent leaves

#### Scenario#43.01: a conflict without a new commit for 30 minutes is stuck

&hellip; so that the user steps in when the agent does not rebase.

- **Given** a pull request of an agent that has conflicted with `main` for 30 minutes without a new commit
- **When** the user opens the overview
- **Then** "Du bist dran" lists it under Festgefahren with "Konflikt seit 30 min ohne neuen Commit"

##### Verified by

- [GiteaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt):
  "a foreign conflict without a new commit for 30 minutes is stuck".

#### Scenario#43.02: a fresh conflict is the agent's

&hellip; so that the agent has the time to rebase before the user is asked.

- **Given** a pull request of an agent that has conflicted with `main` for 20 minutes
- **When** the user opens the overview
- **Then** "Du bist dran" does not list the pull request

##### Verified by

- [GiteaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt):
  "a fresh foreign conflict is the agent's, a draft asks nothing".

#### Scenario#43.03: the conflict starts when `main` leaves the base of the pull request

&hellip; so that the time counts from the change of `main`, not from an older commit of the agent.

- **Given** a pull request of an agent with its last commit at 13:28,
  and `main` that left its base at 13:34 and conflicts with it
- **When** the user opens the overview at 14:04
- **Then** "Du bist dran" shows "Konflikt seit 30 min ohne neuen Commit"

##### Verified by

- [GiteaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt):
  "a conflict counts from the later of the head commit and main leaving its base".

## Solution

- `GiteaSource.flags` adds the Festgefahren flag "Konflikt seit … ohne neuen Commit" to an agent's pull request
  that has conflicted for 30 min (`CONFLICT_AFTER`).
- `GiteaSource.conflictSince` dates the conflict: the later of the head commit
  and the oldest commit of `main` that the head lacks, from Gitea's `compare/<head>...<base>`.
  The pull request's `updated_at` does not fit, as each review of werkrevisor changes it.
- The two requests run only for an agent's pull request that conflicts and is no draft.
- The overview translates the reason into English.
  The hint of Festgefahren names the agent alone: "der Agent kommt nicht weiter", no longer werkrevisor.
- The demo data show such a pull request.
- Version 0.23.0.

On 2026-10-12 this dates the conflict of PR#41 at 13:34, the merge of PR#40; its head commit is of 13:28.

## Open Questions

- [x] Which group shows the conflict?
  User decision: Festgefahren, as RED without a new commit; agent and `main` do not get on.
- [x] How long may the agent leave a conflict?
  User decision: 30 min.
- [x] From when is the time measured?
  User decision: from the later of the head commit and the first commit of `main` after the base of that head.
  Another merge into `main` does not reset the time; the conflict may be dated a bit too early.

## Additional Changes

- A work in progress no longer shows "Konflikt": Gitea's `mergeable` is false for each one, e.g. for this PR while it was `WIP: `.
- The dashboard README no longer says that a requested review counts while werkrevisor runs; PR#42 changed that.
