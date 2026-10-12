# PR#34: The Three Most Critical Charts of a Werkator

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

A Werkator panel shows 5 to 6 charts, most of them of metrics that are fine.
The user wants to see only what matters now.

After the deployment of 0.13.0 the charts stayed empty until the page was reloaded:
when the first read of Werkator's history fails, the next one comes only after 5 minutes.

## Non-Goals

- No new layout of the tiles; it stays as in PR#31.

## Specification

### Feature: Werkator panel with its most critical charts

#### Scenario#34.01: the three most critical charts

&hellip; so that the panel shows only what needs attention.

- **Given** a Werkator with swap in use above its threshold and the other metrics fine
- **When** the user opens the overview
- **Then** its panel shows swap and the two metrics closest to their thresholds, in Werkator's order

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "a metric above its threshold comes first, then the ones closest to theirs";
  "the load holds the three most critical charts in Werkator's order" with critical RAM and HDD.

#### Scenario#34.02: a failed history is read again soon

&hellip; so that the charts do not stay empty for minutes.

- **Given** a Werkator whose history did not answer
- **When** the dashboard reads the Werkator again after 30 seconds
- **Then** the panel shows the lines of the history

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "a failed history is read again at the next fetch".

## Solution

- `WerkatorSource` ranks all metrics: by level first, then by the value against its warning threshold.
  It keeps the 3 highest and shows them in Werkator's order: CPU, RAM, disks, load, swap, the waits.
- CPU, RAM and the disks warn at 80 %, load at 1 per thread.
- A failed read of the history keeps the last lines and does not count as read,
  so the next fetch, 30 seconds later, reads it again.
- The history gets the same timeout of 30 seconds as the builds.
- Version 0.19.0.

## Open Questions

None.
