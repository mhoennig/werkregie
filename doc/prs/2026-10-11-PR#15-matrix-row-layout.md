# PR#15: Matrix Rows Lead with Room and Sender

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#8](https://git.javagil.de/mi/werkregie/pulls/8): the panel "Nachrichten".

## Problem

A row of the panel "Nachrichten" shows the start of the text in bold and room and sender small and grey below it.
The user scans the panel by who wrote where, and has to search for exactly that.

## Non-Goals

- No avatars and no colours per sender.
- No change to what a relay reports to the server.

## Specification

### Feature: rows that lead with room and sender

#### Scenario#15.01: a room message names its room and sender first

&hellip; so that the user sees at a glance who wrote where.

- **Given** a message from a room
- **When** the page shows it in the panel "Nachrichten"
- **Then** its row starts with the room name in the accent colour and the sender in bold,
  and the start of the text follows in normal weight

##### Verified by

- Manual: screenshots of the demo in light and dark, the rows "Team Shop · Ben" and "Release-Planung · Carla".

#### Scenario#15.02: a direct message names its sender first

&hellip; so that a direct chat reads like one.

- **Given** a direct message
- **When** the page shows it in the panel "Nachrichten"
- **Then** its row starts with the sender in bold, without a room name

##### Verified by

- Manual: screenshots of the demo in light and dark, the direct message of Anna.

## Solution

- The page splits the detail of a Matrix row, "room · sender", at its last separator;
  a direct chat has the sender alone.
- A row starts with the room in the accent colour and the sender in bold, then the start of the text in normal weight;
  the meta line no longer repeats room and sender.
- The demo marks two of its three messages unread, so that it shows both states.
- Version 0.6.2.

## Open Questions

None.

## Additional Changes

- Werkbaum: `#dash.taiga`, a Taiga integration, planned; its scope is open.
- A process that reads Matrix along no longer reports a note; the page showed it as a warning.
