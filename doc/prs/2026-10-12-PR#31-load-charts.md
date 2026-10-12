# PR#31: Small Charts of Werkator's Load

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

A Werkator panel of the dashboard shows only one chart, the CPU of 24 hours, and CPU, RAM and disk as meters.
Werkator's own home page charts every metric, e.g. load and the pressure-stall times,
which often are the reason for its verdict.
The panel has room for 4 to 5 such charts.

## Non-Goals

- No threshold lines and no per-step maxima, as on Werkator's home rail.
- No chart of the swap-out rate.

## Specification

### Feature: Werkator panel with small charts

#### Scenario#31.01: the fixed charts

&hellip; so that the user sees at one glance how the host was used during the last 24 hours.

- **Given** a Werkator with its system API
- **When** the user opens the overview
- **Then** its panel shows CPU, RAM, its first disk and load, each with its value now and its line of 24 hours

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "the load holds the four fixed charts with their levels".
- Manual: a local dashboard against Werkator javagil and vm4006 showed both panels with 5 charts each,
  on the desktop and at the mobile preset.

#### Scenario#31.02: the worst of the remaining metrics

&hellip; so that the cause of a warning shows as a chart.

- **Given** a Werkator with CPU pressure above 20 %
- **When** the user opens the overview
- **Then** the fifth chart of its panel is CPU wait, marked as a warning

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "the fifth chart is the worst of swap and the waits".
- Manual: vm4006 at a CPU pressure of 90 % showed CPU wait as the fifth chart; javagil, with swap at 52 %, showed swap.

## Solution

- `HostLoad` holds a list of `LoadChart` instead of the CPU, RAM and disk shares and the CPU line.
- `WerkatorSource` makes the four fixed charts, then the worst of swap, CPU wait, memory wait and disk wait.
  The worst one has the highest level; equal levels rank by the value against its warning threshold.
- The levels follow Werkator's thresholds:
  CPU, RAM and disk warn at 80 % and are critical at 90 %, load at 1 and 2 per thread,
  swap warns at 50 %, CPU wait at 20 %, memory wait at 2 % and 10 %.
  Disk wait has no level at Werkator; for the ranking it counts like CPU wait.
- Each line is scaled like on Werkator's home rail: a share against its total, load against the threads,
  a wait against 100 %, or against its highest value if that is more.
- The page shows the charts as a grid of small tiles: label, value and line in the colour of its level.
- Version 0.12.0.

## Open Questions

None.

## Additional Changes

- `JsonNode.number` moved from `WerkrevisorSource` next to `JsonNode.text`, for both sources.
