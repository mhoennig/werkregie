# PR#46: No Superseded Failures of Werkator

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The dashboard shows every failed Werkator build of its window as red,
also when the same branch was built again afterwards and passed.
On 2026-10-12 `claude-code/stale-conflict` showed red, though three green builds followed and PR#43 was merged.

## Non-Goals

- No change in Werkator; its build history is enough.
- A failed build stays when it is the newest of its branch, even if the branch is gone.

## Specification

### Feature: Only current failures of Werkator

#### Scenario#46.01: a failure superseded by a newer build is not shown

&hellip; so that red means something is broken now.

- **Given** a failed build of a branch and a newer build of the same branch
- **When** the user opens the dashboard
- **Then** neither the builds panel nor the history shows the failed build

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "a failure with a newer build of its branch is left out".
- Manual: a local dashboard against Werkator javagil and vm4006 no longer showed `claude-code/stale-conflict`;
  of 152 builds only the newest build of `main@mutation` of werkator stayed red.

## Solution

- `WerkatorSource` leaves out a failed, errored, timed-out or interrupted build when a newer build of the same branch exists.
  Builds of a branch and its `@ai-review` runs count as different branches.
- Version 0.25.0.

## Open Questions

None.
