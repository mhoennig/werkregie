# PR#39: Running Builds of a Werkator

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

A Werkator panel shows the load of the host, but not how many of its build slots are taken.
Werkator's system page shows this as the tile "Builds", e.g. "1 of 2 running".

## Non-Goals

- No count of the running PR targets; Werkator shows them in a tile of its own.

## Specification

### Feature: Running builds in the Werkator panel

#### Scenario#39.01: the builds in use of the slots

&hellip; so that the user sees at once whether a Werkator can take another build.

- **Given** a Werkator with 2 build slots and 1 running build
- **When** the user opens the overview
- **Then** the heading of its panel shows "1/2" before its verdict, in amber as on Werkator's tile

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "the running builds and their level as on Werkator's tile".
- Manual: a local dashboard against Werkator javagil (0 of 2 running) and vm4006 (1 of 2)
  showed "0/2" in the normal colour and "1/2" in amber, on the desktop and at the mobile preset.

## Solution

- `HostLoad` gets `builds`, `buildSlots` and `buildsLevel`.
- `WerkatorSource` counts the `runningBuilds` of `/api/system` that are no PR target, against `maxConcurrent`.
- The level follows Werkator's `buildSlotsLevel`: bad without a free slot, warn with one, else ok.
- The page shows "running/slots" before the verdict, amber at warn, red at bad.
- Version 0.20.0.

## Open Questions

None.
