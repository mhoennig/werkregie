# PR#14: Matrix Read State Follows Element's Read Marker

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#13](https://git.javagil.de/mi/werkregie/pulls/13): read and unread messages, which this PR fixes.

## Problem

Werkregie marks a message "Neu" when it arrived after the user's newest read receipt in its room.
An Element that sends no read receipts, e.g. because the user switched them off, only moves its read marker `m.fully_read`.
The user's receipts in two rooms dated from August, the read markers stood on the newest messages:
the panel marked 17 messages "Neu" that the user had read.

## Non-Goals

- Werkregie still sends neither read receipts nor read markers.

## Specification

### Feature: read state as Element shows it

#### Scenario#14.01: the read marker counts as reading

&hellip; so that a message read in an Element without read receipts does not stay "Neu".

- **Given** a message marked "Neu" in a room where the user sends no read receipts
- **When** the user reads the room in Element, which moves its read marker past the message
- **Then** the row loses its mark and is shown subdued within a few seconds

##### Verified by

- [MatrixMessagesTest "the later of read receipt and read marker decides until when a room is read"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)
- Manual: with the real account, the 17 room rows marked "Neu" lost their mark; the read markers stood on the newest messages.

#### Scenario#14.02: a read receipt still counts

&hellip; so that a client that sends read receipts but keeps no read marker works as before.

- **Given** a message marked "Neu"
- **When** a read receipt of the user dated after the message arrives
- **Then** the row loses its mark

##### Verified by

- [MatrixMessagesTest "the later of read receipt and read marker decides until when a room is read"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#14.03: the later of both decides

&hellip; so that an old read receipt does not undo a newer read marker, nor the other way round.

- **Given** the user's read receipt is older than a message, and the read marker stands on a newer one
- **When** the page shows the panel "Nachrichten"
- **Then** the message is shown subdued, without a mark

##### Verified by

- [MatrixMessagesTest "the later of read receipt and read marker decides until when a room is read"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

## Solution

- The Matrix source follows two flows per room: the user's read receipts and the room account data `m.fully_read`.
- For the read marker it looks up the event it stands on and takes its time; a missing event counts as no marker.
- `MatrixMessages.readUntil` takes the later of both; a row after it carries the marker `unread`.
- Version 0.6.1.

## Open Questions

None.
