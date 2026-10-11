# PR#25: Taiga Statuses with Aliases, Hidden Ones and Their Colours

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#21](https://git.javagil.de/mi/werkregie/pulls/21): the Taiga source and its `statuses`.

## Problem

The user's Taiga projects name the same status differently, e.g. "In Arbeit" and "In Progress", "Neu" and "New".
`statuses` of werkregie PR#21 takes one name per place, so the order does not work across projects.
"Erledigt" and "Verworfen" are open statuses in Taiga, so 43 items show, many of them done.
A status that is missing from `statuses` goes to the end unnoticed.

## Non-Goals

- No status categories of Werkregie's own; the colours are Taiga's.

## Specification

### Feature: the order and the look of Taiga statuses

#### Scenario#25.01: equal names share a place

&hellip; so that one order fits projects that name a status differently.

- **Given** `statuses` with `[In Arbeit, In Progress]`, `Bereit`, `[Neu, New]`
- **When** the page shows items in "New", "In Progress", "Bereit" and "In Arbeit"
- **Then** "In Arbeit" and "In Progress" share the first place, "Bereit" follows, "New" comes last

##### Verified by

- [TaigaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TaigaSourceTest.kt): "the configured statuses rank in their order; equal names share a place, regardless of case",
  "statuses in YAML: single names and lists of equal names".

#### Scenario#25.02: an unknown status comes first and is marked

&hellip; so that the user notices it and adds it to `statuses`.

- **Given** an item in a status that `statuses` and `hiddenStatuses` do not name
- **When** the page shows the panel "Aufgaben"
- **Then** the item stands first and carries the marker "Status unbekannt" with a question mark

##### Verified by

- [TaigaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TaigaSourceTest.kt): "an unknown status comes first and is marked; a hidden one comes last and is marked for the page".
- Manual: the demo shows "Gutscheine einlösen" in "Im Test" first, marked.

#### Scenario#25.03: hidden statuses show on demand

&hellip; so that items that are done in all but name do not crowd the panel.

- **Given** `hiddenStatuses` with `Erledigt` and an item in "Erledigt"
- **When** the user clicks "1 ausgeblendet" in the panel's count
- **Then** the item shows, last, and the count offers "1 ausblenden"; the browser keeps the choice

##### Verified by

- Manual: the demo counts "4 offen · 1 ausgeblendet"; after the click "5 offen · 1 ausblenden", 5 rows,
  and `showHiddenTasks: true` in `werkregie-settings`.

#### Scenario#25.04: a status shows Taiga's colour

&hellip; so that a status looks as in Taiga.

- **Given** a Taiga status with the colour `#A8E440`
- **When** the page shows an item in it
- **Then** the dot before the status has that colour; a value that is no CSS hex colour is dropped

##### Verified by

- [TaigaSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TaigaSourceTest.kt): "the status keeps Taiga's colour, and nothing but a colour".
- Manual: the dot of "Im Test" in the demo computes to `rgb(168, 228, 64)`.

## Solution

- `statuses` takes single names and lists of equal names; `hiddenStatuses` names statuses to hide.
- `TaigaSource` ranks an unknown status -1 and marks it `unknown`, a hidden one after all others and marks it `hidden`.
- `Item.color` carries Taiga's status colour; server and page accept `#` and 3, 4, 6 or 8 hex digits only,
  since the page puts it into a style attribute.
- The page hides `hidden` items until the count's button shows them, and keeps that choice in `werkregie-settings`.
- The demo, the example configurations and `dashboard/README.md` show it.
- Version 0.10.0.

## Open Questions

None.
