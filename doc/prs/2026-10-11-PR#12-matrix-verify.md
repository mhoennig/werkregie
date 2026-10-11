# PR#12: Matrix Device Verified by the Recovery Key

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#8](https://git.javagil.de/mi/werkregie/pulls/8): the Matrix walking skeleton.
- [werkregie PR#11](https://git.javagil.de/mi/werkregie/pulls/11): one Matrix client per machine.

## Problem

The device "Werkregie" of the user's account is not verified and has no keys of older messages.
Encrypted messages from before its login show as "verschlüsselt".
Element knows the recovery key, earlier called "Security Key": it verifies a device and unlocks the key backup in one step.

## Non-Goals

- No verification by emoji comparison; it stays planned in the Werkbaum.
- No change to what the client stores: decrypted content stays in its H2 file, as the human decided.

## Specification

### Feature: Matrix device verified by the recovery key

#### Scenario#12.01: the recovery key verifies the device

&hellip; so that other clients trust the device and share their keys with it.

- **Given** Werkregie is signed in to Matrix, and no other process of the machine syncs Matrix
- **When** the user runs `werkregie-dashboard matrix verify` and enters the recovery key
- **Then** Element lists the device "Werkregie" as verified

##### Verified by

- Manual: only the human has the recovery key; checked after the deployment.
- [MatrixMessagesTest "a recovery key is told apart from a security phrase"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#12.02: the security phrase works as well

&hellip; so that a user who chose a phrase instead of a key can verify, too.

- **Given** the user's secret storage has a security phrase
- **When** the user enters the phrase instead of the recovery key
- **Then** Element lists the device "Werkregie" as verified

##### Verified by

- Manual: only the human has the recovery key; checked after the deployment.
- [MatrixMessagesTest "a recovery key is told apart from a security phrase"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#12.03: a wrong key changes nothing

&hellip; so that a typing error does not leave the device half set up.

- **Given** Werkregie is signed in to Matrix
- **When** the user enters a key that does not fit
- **Then** the command says that the key does not fit, and the device stays unverified

##### Verified by

- Manual: only the human has the recovery key; checked after the deployment.

#### Scenario#12.04: older encrypted messages become readable

&hellip; so that the panel shows the text of messages that arrived before the device had their keys.

- **Given** the panel shows a message as "verschlüsselt"
- **When** the relay starts again after the verification
- **Then** the panel shows the start of its text

##### Verified by

- Manual: after the verification and a restart of the relay.
- [MatrixMessagesTest "a row's link gives back room ID and event ID, in each form"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt)

#### Scenario#12.05: no verification while another process syncs

&hellip; so that the verification never writes into a store that another process uses.

- **Given** the relay of the machine syncs Matrix
- **When** the user runs `werkregie-dashboard matrix verify`
- **Then** it refuses and says to stop the relay or the local mode first

##### Verified by

- [MatrixSourceTest "no verification while another process syncs"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

#### Scenario#12.06: no verification before the login

&hellip; so that the user learns the order of the steps.

- **Given** Werkregie is not signed in to Matrix
- **When** the user runs `werkregie-dashboard matrix verify`
- **Then** it says to run `werkregie-dashboard matrix login` first

##### Verified by

- [MatrixSourceTest "no verification before the login"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt)

## Solution

- `werkregie-dashboard matrix verify` takes the lock of the Matrix client, syncs once
  and asks Trixnity for the ways to verify the device itself.
- It reads the recovery key or the security phrase from the terminal, without echo;
  48 characters of base58 count as a key, anything else as a phrase.
- Trixnity decrypts the secrets of the user's secret storage with it:
  the self-signing key signs the device, and the key of the key backup is stored.
- The command waits until the device counts as cross-signed and verified, at most a minute.
- When the relay starts, it asks for the rows shown as "verschlüsselt" once more;
  Trixnity takes missing room keys from the key backup.
- Emoji comparison is no longer needed for the verification; it stays an optional node in the Werkbaum.
- Version 0.5.0.

## Additional Changes

- The PR template writes the purpose of a scenario as "&hellip; so that", in lower case.

## Open Questions

None.
