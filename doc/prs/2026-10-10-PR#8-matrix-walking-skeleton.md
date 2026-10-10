# PR#8: Matrix Messages, Walking Skeleton

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#5](https://git.javagil.de/mi/werkregie/pulls/5): the spike, Trixnity runs on the JVM with vodozemac.
- Werkbaum `#dash.matrix` in [doc/werkregie.werkbaum](../werkregie.werkbaum).

## Problem

Messages that matter to the user, direct messages and mentions above all, are in Matrix, not in Werkregie.
Many of the user's rooms are end-to-end encrypted.
Only a device of the user's own account can read them, so Werkregie must sign in with that account.

## Non-Goals

- No verification of the device and no room keys from the key backup:
  until then, an encrypted message shows as encrypted, with room, sender and time.
- No sending, no read receipts, no notifications to colleagues (`#dash.notify`).
- No decision on the database of Werkregie beyond this client store (`#dash.store`).

## Specification

### Feature: Matrix messages in Werkregie

#### Scenario#8.01: the homeserver has no default

So that no homeserver is assumed or named in the repository.

- **Given** a configuration without `matrix.homeserver`
- **When** the dashboard starts
- **Then** it has no Matrix source

##### Verified by

- [MatrixSourceTest "without a homeserver there is no Matrix source"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#8.02: the user signs in as a device of its own

So that Werkregie reads with the user's account, and the device keys stay on the user's machine.

- **Given** `matrix.homeserver` is configured
- **When** the user runs `werkregie-dashboard matrix login` and enters user ID and password
- **Then** the homeserver knows a new device named "Werkregie" with the host name,
  and the state directory keeps its session

##### Verified by

- Manual: only the human can sign in with the account; checked after the deployment.
- [MatrixSourceTest "the client's H2 store opens and gets its tables, and an empty one asks for a login"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#8.03: without a login the source says what to do

So that a missing login does not look like an empty inbox.

- **Given** `matrix.homeserver` is configured, but nobody signed in
- **When** the page loads
- **Then** the Matrix source is in error and names `werkregie-dashboard matrix login`

##### Verified by

- [MatrixSourceTest "without a login the source names the command that signs in"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#8.04: a direct message shows

So that nothing addressed to the user personally is missed.

- **Given** the user is signed in
- **When** a direct message arrives
- **Then** the panel "Nachrichten" shows it with sender, time and the start of the text, marked "Direkt"

##### Verified by

- [MatrixMessagesTest "a direct message shows, marked as direct"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.05: a mention in any room shows

So that the user sees where somebody needs them.

- **Given** the user is signed in
- **When** a message in any room mentions the user
- **Then** the panel shows it, marked "Erwähnung"

##### Verified by

- [MatrixMessagesTest "a mention in any room shows, marked as mention"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)
- [MatrixMessagesTest "a mention is found in m.mentions, by user ID or by display name"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.06: every message of a configured room shows

So that the rooms the user follows closely are complete.

- **Given** a room is listed in `matrix.rooms`
- **When** a message arrives there
- **Then** the panel shows it, marked "Raum"

##### Verified by

- [MatrixMessagesTest "every message of a configured room shows, marked as room"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.07: other messages stay hidden

So that the panel holds what matters, not the whole of Matrix.

- **Given** a room that is no direct chat and not configured
- **When** a message arrives there without mentioning the user
- **Then** the panel does not show it

##### Verified by

- [MatrixMessagesTest "a message of another room, without a mention, stays hidden"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.08: a message the device cannot decrypt shows as encrypted

So that a message is not lost while the device has no keys.

- **Given** the device has no key for a message
- **When** the message arrives in a direct chat
- **Then** the panel shows room, sender and time, and "verschlüsselt" instead of the text

##### Verified by

- [MatrixMessagesTest "a message the device cannot decrypt shows as encrypted, with room, sender and time"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.09: the server gets only the start of the text

So that the server on a shared host holds no more message content than needed.

- **Given** a relay with a Matrix source
- **When** it reports to the server
- **Then** each message carries at most the first 80 characters of its text, on one line

##### Verified by

- [MatrixMessagesTest "only the start of the text goes into the row, on one line"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#8.10: a message links into Element

So that the user can answer at once.

- **Given** a message in the panel
- **When** the user opens it
- **Then** the link leads to that message, in the form `matrix.link` sets: `matrix.to` by default

##### Verified by

- [MatrixMessagesTest "a message links to itself, by matrix.to or Element Desktop's schemes"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

## Solution

- `MatrixSource` runs in the modes `local` and `relay` when `matrix.homeserver` is set.
  It syncs in a thread of its own with [Trixnity](https://central.sonatype.com/namespace/de.connect2x.trixnity) 5.9.2
  and keeps the shown rows in `<stateDir>/matrix/messages.json`.
- `werkregie-dashboard matrix login` asks for user and password in the terminal and signs in as the device "Werkregie <host>".
  It never takes the password from a file or an argument.
- `MatrixMessages` decides what shows and how, free of Trixnity, so that the tests need no homeserver.
- The page gets the panel "Nachrichten", shown only where a Matrix source exists,
  with the markers "Direkt", "Erwähnung" and "Raum"; the demo has three invented messages.
- Version 0.4.0.

Decisions:

- New runtime dependencies, with the consent of the human: Trixnity (client, vodozemac crypto driver, Exposed repositories),
  Ktor's Java engine, and r2dbc-h2 with H2.
  The runtime bundle grows from 57 MB to 87 MB;
  jlink adds `java.logging`, `java.management`, `java.instrument` and `jdk.unsupported`.
- The client's store is an H2 file: Trixnity's Exposed repositories need R2DBC, which SQLite does not offer.
  Rejected: Room with a bundled SQLite, a second native library and Android libraries on the JVM;
  a store in memory, a new device with new keys after each restart.
  Whether Werkregie gets one database of its own is open as `#dash.store`.
- sqlite-jdbc stays: it only reads the database of Codex.
- The client stores the decrypted content of timeline events in its H2 file (Trixnity's default).
  The file lies in a directory of mode 0700 on the user's machine; the server never gets it.

## Open Questions

None.

## Additional Changes

- Werkbaum: `#dash.pwa`, `#dash.matrix` and `#dash.relay` lose their sizes, since a node with children has none.
- Werkbaum: the decision of 2026-10-10 that the PWA may keep the last state in the browser's storage.
- Werkbaum: `#dash.store`, the database of Werkregie, to be discussed.
