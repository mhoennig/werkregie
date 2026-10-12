# PR#53: Parallel Work Shares Its Minutes in the Werkjournal Proposal

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#45](https://git.javagil.de/mi/werkregie/pulls/45): the proposal this PR changes.

## Problem

The proposal counts the minutes of each entry on its own, so work in two projects at the same time counts twice.
On 2026-10-12 the entries summed up to 873 min, while all work of the day together covered 652 min of the clock.
Both recorded days reached the limit of 12 hours.

## Non-Goals

- No change to the active spans of a session or to the 15 min of an action.
- No change within one entry: its parallel sessions already count once.

## Specification

### Feature: Parallel work shares its minutes

#### Scenario#53.01: two entries at the same time share the minutes

&hellip; so that the entries of a day add up to the time of the clock.

- **Given** an entry active from 09:00 to 11:00 and another one active from 10:00 to 11:00
- **When** the server proposes the day
- **Then** the first entry has 90 minutes and the second one 30 minutes

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt):
  "two entries at the same time share the minutes".
- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt):
  "the shared minutes add up to the time of the clock".
- Manual: the recorded 2026-10-12 of mih09 gives 652 min in all instead of 873, the union of all its spans;
  2026-10-11 stays at 827 min, since nearly all its work was in one project.

## Solution

- `JournalProposer` cuts the day at each start and end of an entry's spans;
  each stretch between two cuts is divided equally among the entries active in it.
- The minutes are rounded by the largest remainders, so the entries add up to the rounded time of the clock.
- Version 0.33.0.

## Open Questions

- [x] How does parallel work count?
  Each minute counts once; k entries active in it get 1/k each; decided by the human on 2026-10-12.
