# PR#33: A Changed Card Shows Inverted for 7 Seconds

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The overview reloads the cards of "Läuft jetzt" every 5 s.
A card that changed, e.g. a session that waits now, looks the same as before;
the user sees the change only by reading every card again.

## Non-Goals

- No marker for the other panels or tabs; only the cards of the overview.
- No sound and no notification.
- No comparison with the state of the last visit.

## Specification

### Feature: a card with new content shows inverted for 7 seconds

#### Scenario#33.01: a changed card shows inverted

&hellip; so that the user sees at once which card changed.

- **Given** the overview with the card of a running Claude Code session
- **When** the session's title changes
- **Then** the card shows in the colours of the other mode for 7 s, then fades back

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#33.02: a new card shows inverted

&hellip; so that a session or build that just started stands out.

- **Given** the overview with 3 cards
- **When** a Werkator build starts
- **Then** its new card shows in the colours of the other mode for 7 s

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#33.03: time alone changes nothing

&hellip; so that only real changes catch the eye.

- **Given** a card with the age "5 min" and a build card with the duration "2 min"
- **When** the next data say "6 min" and "3 min", and nothing else changed
- **Then** both cards keep their normal colours

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#33.04: the reload does not restart the 7 seconds

&hellip; so that a card is inverted for 7 s, not until the next reload.

- **Given** a card that shows inverted since 4 s
- **When** the page reloads the data, which are unchanged
- **Then** the card stays inverted for 3 more seconds and then fades back

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#33.05: no card is inverted when the page opens

&hellip; so that opening the page does not flash all cards.

- **Given** a browser with the kept state of the last visit
- **When** the user opens the overview
- **Then** all cards show in their normal colours

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#33.06: inverted means the other mode

&hellip; so that the inverted card fits the page in light and in dark mode.

- **Given** the page in dark mode
- **When** a card changes
- **Then** the card shows the colours of light mode; in light mode those of dark mode

##### Verified by

- Manual: see "Check in the Browser".

### Check in the Browser

The dashboard ran in local mode in Claude's browser pane;
a stand-in for `fetch` replaced the items of `/api/dashboard` with chosen ones.

- Opening the page with a kept `werkregie-last` showed 8 cards, none inverted (#33.05).
- 4 new cards showed inverted in light mode (#33.02); after 7 s all were normal again.
- Ages 5 → 6 min and 2 → 3 min, the build's "2 min" → "3 min" and a switch of the language changed no card (#33.03).
- A new title inverted only its card, sampled every 0.5 s: inverted until 6.5 s through 2 renders,
  the fade from 7.0 s, normal at 8.0 s (#33.01, #33.04).
- In dark mode, by `data-theme` and by the browser's preference, the changed cards showed white with dark text,
  the Codex logo black (#33.06).
- The mobile preset in light and dark mode showed the same.

## Solution

- `renderCards` keeps the content of each card from the last render and, by card, until when it shows inverted.
  A card is the item's `url`, else its `command`, else source, origin, kind, project and title.
- The content is what the card shows: source, state, title, waiting or running, project, origin or detail.
  Not in it: the time, parts of the detail like "3 min" (Werkator's build duration),
  and the badges and the model of a session, which the card does not show.
- The first render only takes the content; so opening the page inverts nothing.
- The content is taken of all cards, also those behind "+n weitere":
  a card that moves in when another one ends is not new.
- A timer ends the 7 s even without a render; the class `is-flash-end` makes the card fade back in 0.8 s.
- `.card.is-flash` sets each colour token to `light-dark(<light>, <dark>)` and `color-scheme` to the mode the page does not use.
  The fade moves nothing; with `prefers-reduced-motion` it stays, and the inverted state as well.
- Version 0.14.0.

## Open Questions

- [x] Does the kept state of the last visit (`werkregie-last`) count as the baseline?
  No: it can be hours old and written by another tab, and every changed card would flash when the page opens.
  The baseline is the first state the page shows, live or kept.
- [x] Which fields make the content of a card?
  Those the card shows, without time; a change the card does not show would invert it without a visible reason.
