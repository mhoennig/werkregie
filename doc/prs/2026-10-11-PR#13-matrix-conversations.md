# PR#13: Matrix Conversations with Their Read State

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#8](https://git.javagil.de/mi/werkregie/pulls/8): the Matrix walking skeleton.
- [werkregie PR#12](https://git.javagil.de/mi/werkregie/pulls/12): the device verified by the recovery key.

## Problem

The panel "Nachrichten" shows only messages of the chosen period, 24 hours by default.
On a quiet weekend it stays empty, although direct chats and configured rooms have older messages.
It does not say why it is empty, and it does not tell read from unread messages.

## Non-Goals

- Werkregie sends no read receipts; reading happens in Element or another client.
- No history beyond what the device has synced.
- Mentions outside the period stay hidden; only conversations keep their last message.

## Specification

### Feature: the last message of each conversation

#### Scenario#13.01: a direct chat keeps its last message

&hellip; so that the user sees every direct chat, however long ago its last message came.

- **Given** the last message of a direct chat is older than the chosen period
- **When** the page shows the panel "Nachrichten"
- **Then** the panel shows that message

##### Verified by

- [MatrixMessagesTest "a direct chat keeps its last message, however old"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#13.02: a configured room keeps its last message

&hellip; so that the rooms the user follows closely do not vanish on a quiet day.

- **Given** the last message of a room in `matrix.rooms` is older than the chosen period
- **When** the page shows the panel "Nachrichten"
- **Then** the panel shows that message

##### Verified by

- [MatrixMessagesTest "a configured room keeps its last message, however old"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#13.03: older messages of a conversation stay within the period

&hellip; so that the panel holds one line per quiet conversation, not its history.

- **Given** a direct chat whose last two messages are older than the chosen period
- **When** the page shows the panel "Nachrichten"
- **Then** the panel shows only the last of them

##### Verified by

- [MatrixMessagesTest "older messages of a conversation stay within the period, and old mentions are not kept"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

### Feature: read and unread messages

#### Scenario#13.04: an unread message stands out

&hellip; so that the user sees at once what is new.

- **Given** a message arrived after the user last read its room
- **When** the page shows the panel "Nachrichten"
- **Then** the row is marked "Neu" and shown in full strength

##### Verified by

- [MatrixMessagesTest "an unread message carries the mark"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)
- Manual: the local page with the real account marked 2 rows "Neu".

#### Scenario#13.05: reading in Element subdues the message

&hellip; so that a message read elsewhere does not ask for attention twice.

- **Given** the panel marks a message "Neu"
- **When** the user reads its room in Element
- **Then** the row loses its mark and is shown subdued within a few seconds

##### Verified by

- [MatrixMessagesTest "a read receipt after a message takes its mark, one before keeps it"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)
- Manual: the human reads the room in Element, after the deployment.

#### Scenario#13.06: a message read before stays subdued

&hellip; so that the panel does not call old news new.

- **Given** the user read a room after its last message arrived
- **When** the page shows the panel "Nachrichten"
- **Then** the rows of that room are shown subdued, without a mark

##### Verified by

- [MatrixMessagesTest "a read receipt after a message takes its mark, one before keeps it"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

### Feature: an empty panel says why

#### Scenario#13.07: the empty panel counts the older messages

&hellip; so that an empty panel is not taken for a broken source.

- **Given** no message of the panel lies within the chosen period, but older ones exist
- **When** the page shows the panel "Nachrichten"
- **Then** the panel names the number of older messages and offers to show 7 days

##### Verified by

- Manual: in the browser, two older mentions made the empty panel say "2 ältere Nachrichten · 7 Tage zeigen".

#### Scenario#13.08: the offer widens the period

&hellip; so that the older messages are one click away.

- **Given** the panel offers to show 7 days
- **When** the user follows the offer
- **Then** the period of the page is 7 days, and the panel shows the older messages

##### Verified by

- Manual: in the browser, the offer set the period to 7 days, and the panel showed 35 rows.

## Solution

- `MatrixMessages.visible` keeps the rows of the period and the last row of each direct chat and configured room.
  The page does the same for its own, shorter period; the link of a row without its event names the conversation.
- The Matrix source follows the user's read receipts in each room it shows, through Trixnity's `getReceiptsById`.
  A row that arrived after the newest receipt carries the marker `unread`; a newer receipt takes it away.
- The page marks such a row "● Neu" and counts it in the heading; a row without the marker is subdued.
- An empty panel counts the hidden older messages and offers a button that sets the period to 7 days.
- Version 0.6.0.

## Additional Changes

- Werkbaum: `#dash.matrix.accounts`, several Matrix accounts and servers, planned.

## Open Questions

None.
