# PR#54: A Short Story Joins the Entry of Its Project

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#45](https://git.javagil.de/mi/werkregie/pulls/45): the proposal; [PR#53](https://git.javagil.de/mi/werkregie/pulls/53): parallel work shares its minutes.

## Problem

An entry below 30 minutes is only a hint, so it stays out of the file.
Since parallel work shares its minutes, a Taiga story gets fewer of them and drops below 30 minutes more often.
On 2026-10-12, "HSAdmin NG" had 130 minutes, but only story #545 with 81 minutes went into the file;
story #544 with 15, story #597 with 6 and the work without story with 28 minutes were hints.

## Non-Goals

- No change for a story of 30 minutes and more: it keeps its own entry.
- No change to the rounding or to the limit of 12 hours.

## Specification

### Feature: A short story joins the entry of its project

#### Scenario#54.01: a story below half an hour joins its project

&hellip; so that short work on a story is booked with its project instead of being lost.

- **Given** in project "HSAdmin NG" story #544 with 15 minutes and work without story with 28 minutes
- **When** the server proposes the day
- **Then** there is one entry of "HSAdmin NG" without story, with 43 minutes, 1 hour and story #544 in its description

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt):
  "a story below half an hour joins the entry of its project".

## Solution

- After sharing the minutes, `JournalProposer` moves each story below 30 minutes into the entry of its project without story,
  in the order of the refs: its minutes, its evidence, and its story at the front of the description.
- Version 0.34.0.

## Open Questions

- [x] What happens to a story below 30 minutes?
  It joins the entry of its project without story; only what stays below 30 minutes then is a hint;
  decided by the human on 2026-10-12.
