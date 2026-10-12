# PR#38: A Changed Row of "Du bist dran" Shows Inverted

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

Since PR#33, a card of "Läuft jetzt" with new content shows inverted.
The rows of "Du bist dran" change without a sign, e.g. a pull request that waits for the user's approval now.
The user wants the same behaviour there.

## Non-Goals

- No marker for the lists in the tabs.
- No change of the cards' behaviour.

## Specification

### Feature: a row of "Du bist dran" with new content shows inverted for 12 seconds

#### Scenario#38.01: a new row shows inverted

&hellip; so that the user sees at once what newly asks for them.

- **Given** the overview with 2 rows under "Du bist dran"
- **When** a pull request starts to wait for the user's approval
- **Then** its new row shows in the colours of the other mode for 12 s, then fades back

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#38.02: a row that changes its group shows inverted

&hellip; so that a step forward of a pull request catches the eye.

- **Given** a row of a pull request under "Freigeben"
- **When** the pull request is approved and moves to "Mergen"
- **Then** its row shows inverted for 12 s

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#38.03: time alone changes no row

&hellip; so that only real changes catch the eye.

- **Given** a row with the reason "RED seit 1 h ohne neuen Commit" and the age "1 h"
- **When** the next data say "RED seit 2 h ohne neuen Commit" and "2 h", and nothing else changed
- **Then** the row keeps its normal colours

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#38.04: no row is inverted when the page opens

&hellip; so that opening the page does not flash all rows.

- **Given** a browser with the kept state of the last visit
- **When** the user opens the overview
- **Then** all rows show in their normal colours

##### Verified by

- Manual: see "Check in the Browser".

### Check in the Browser

The dashboard ran in local mode in Claude's browser pane;
a stand-in for `fetch` added 2 pull requests with flags to the answers of `/api/dashboard`.

- Opening the page showed no row inverted (#38.04).
- Both new rows, under "Festgefahren" and "Freigeben", showed inverted (#38.01); after 12 s both were normal again.
- "RED seit 1 h" → "RED seit 2 h" with the age 1 h → 2 h, and a switch to German, changed no row (#38.03).
- The move of #41 from "Freigeben" to "Mergen" inverted only its row (#38.02).
- In dark mode the changed rows showed white with dark text.
- The cards still work: a build's duration "12 s" → "2 min" changed nothing, its state "failed" inverted it.

## Solution

- The flashes of PR#33 become `flashList()`, one for the cards and one for the rows of "Du bist dran";
  one timer ends the flashes of both.
- The content of a row: its group, the tool, its title, the reason as the server writes it without time spans, and the project;
  for a message its text.
  The reason is taken before its translation, so a switch of the language changes nothing.
- `withoutTime()` removes every time span like "3 min" or "2 h" from a text, for the cards' detail as well.
- `.is-flash` and its rules apply to `.todo-row` too; a row gets the background of the other mode.
- Version 0.18.0.

## Open Questions

None.
