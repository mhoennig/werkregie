# PR#42: Du bist dran Waits While werkrevisor Reviews

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

Since PR#37 a review requested from the user puts a pull request under "Du bist dran", also while werkrevisor reviews it.
werkrevisor's request stays after a YELLOW result, so the PR also shows while werkrevisor reviews its next head, e.g. PR#40.
The user can only approve after werkrevisor's result for the current head; until then there is nothing to do.

## Non-Goals

- No change of the flags of the user's own pull requests.

## Specification

### Feature: Du bist dran waits for werkrevisor's result

#### Scenario#42.01: no approval asked while werkrevisor reviews

&hellip; so that the user is not asked to approve a head that werkrevisor has not judged yet.

- **Given** a pull request of an agent with a review requested from the user, and werkrevisor reviewing its head
- **When** the user opens the overview
- **Then** "Du bist dran" does not list the pull request

##### Verified by

- [GiteaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt):
  "a review requested from the user waits for werkrevisor's result".

## Solution

- `GiteaSource.flags` asks for the approval only after werkrevisor's result; a requested review no longer overrides a running review.
- The reason "Review angefragt" is gone; after the result the reason is werkrevisor's verdict, as before.
- Version 0.21.1.

## Open Questions

None.
