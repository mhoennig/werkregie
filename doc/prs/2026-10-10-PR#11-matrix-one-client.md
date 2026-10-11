# PR#11: One Matrix Client per Machine

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#8](https://git.javagil.de/mi/werkregie/pulls/8): the Matrix walking skeleton that this PR fixes.

## Problem

On a machine with a relay and the local mode, both read the same configuration and the same state directory.
With Matrix configured, both would open the same H2 store and act as the same device.
H2 lets only one process open the file, and two clients with the same keys would spoil each other's sessions.

## Non-Goals

- No second device for the local mode.
- No change for the server: it never runs a Matrix client.

## Specification

### Feature: One Matrix client per machine

#### Scenario#11.01: a second process reads along

So that the local mode shows the messages while the relay syncs them.

- **Given** the relay of a machine syncs Matrix
- **When** the local mode of the same machine shows its page
- **Then** the page shows the same messages as the relay reports,
  and the homeserver sees no second session of the device

##### Verified by

- [MatrixSourceTest "the local mode shows the relay's messages, without a session of its own"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#11.02: the local mode takes over from a stopped relay

So that the messages stay current when the relay stops.

- **Given** the local mode shows the messages that the relay of its machine syncs
- **When** the relay stops
- **Then** new messages still appear on the page of the local mode

##### Verified by

- [MatrixSourceTest "the local mode takes over from a stopped relay"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#11.03: no login while another process syncs

So that a login never writes into a store that another process uses.

- **Given** one process of the machine syncs Matrix
- **When** the user runs `werkregie-dashboard matrix login`
- **Then** it refuses and says to stop the relay or the local mode first

##### Verified by

- [MatrixSourceTest "no login while another process syncs"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)
- [MatrixSourceTest "a failed login frees the lock"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

## Solution

- The process that holds the file lock `<stateDir>/matrix/sync.lock` runs the client.
  The operating system frees the lock when the process ends, also after a crash.
- Another process reads `messages.json` on each fetch; its status note says "liest mit".
  On each fetch it tries the lock again and starts its own client once it gets it.
- `matrix login` takes the lock first and refuses while another process holds it.
- The directory `<stateDir>/matrix/` gets mode 0700 also when the lock creates it first.
- `matrix login` frees the lock on every path, also when the login fails.
- Version 0.4.3.

## Open Questions

None.
