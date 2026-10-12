# PR#49: The Werkjournal Proposal Sorts a Story Beside Its Project

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#45](https://git.javagil.de/mi/werkregie/pulls/45): the proposal this PR fixes.

## Problem

On mih09, `GET /api/journal/<day>/proposal` answered HTTP 500 for each day with a project that had both
a Taiga story and other work; with Taiga projects in `journal.customers`, this held for 2026-10-11 and 2026-10-12.
The proposal sorts its entries by customer, project and story ref, with 0 for an entry without story.
The ref is a `Long` and the 0 an `Int`, so comparing a story entry with the entry of the same project without story
throws a `ClassCastException`.
The tests of PR#45 had no project with both.

## Non-Goals

- No other change to the proposal.

## Specification

### Feature: The Werkjournal proposal sorts a story beside its project

#### Scenario#49.01: a story and the rest of its project

&hellip; so that a project with a Taiga story and other work gets its proposal.

- **Given** a session of story #545 and a session without Taiga ID, both in project "HSAdmin NG"
- **When** the server proposes the day
- **Then** there are two entries of "HSAdmin NG": first the one without story, then the one of story #545

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt):
  "a story and the rest of its project are two entries, the one without story first";
  without the fix it fails with the `ClassCastException` of mih09.

## Solution

- The sort key of an entry without story is `0L`, a `Long` as the ref.
- Version 0.29.1.

## Open Questions

None.
