# PR#51: An Info Symbol Shows the Last Input and Claude's Question on a Phone

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

Since PR#47, the tooltip of a waiting Claude Code session in "Du bist dran" shows the user's last input and Claude's question.
A phone has no tooltip, so the user does not see the two texts there.

## Non-Goals

- No change to the texts themselves.
- No info symbol on rows without these texts, e.g. a pull request.

## Specification

### Feature: an info symbol shows the two texts of a waiting session

#### Scenario#51.01: a tap shows the texts

&hellip; so that the user sees on a phone what a session is about and what it asks.

- **Given** a waiting session in "Du bist dran" with the last input "Baue den Export"
- **When** the user taps the info symbol of its row
- **Then** the row shows "Du: Baue den Export" below its title

##### Verified by

- manual check in the browser, see "Solution"

#### Scenario#51.02: a second tap hides the texts

&hellip; so that an open row does not stay long.

- **Given** a row that shows the two texts
- **When** the user taps its info symbol again
- **Then** the row hides the texts

##### Verified by

- manual check in the browser, see "Solution"

#### Scenario#51.03: the tap does not open the session

&hellip; so that reading the texts does not leave the page.

- **Given** a waiting session whose row links to Claude Desktop
- **When** the user taps the info symbol
- **Then** the page stays open

##### Verified by

- manual check in the browser, see "Solution"

## Solution

- A row in "Du bist dran" whose flag carries `prompt` or `question` gets a button ⓘ before its age.
- A tap shows the two texts below the title and detail of the row; a second tap hides them.
  The tap does not follow the link of the row.
- The page keeps which rows are open, so the render every 5 s does not close them.
- The tooltip stays for the desktop.
- The repository has no tests of the page; a local check in the browser at 375 px width with the live sessions on think showed:
  a tap opens the texts of its row only, the row stays open across the render every 5 s,
  a second tap closes it, and the page does not follow the link of the row.
- Version 0.31.0.

## Open Questions

- [x] An info symbol that opens the row, a popup, or the texts always visible on a phone?
  An info symbol that opens the row, chosen by the human.
