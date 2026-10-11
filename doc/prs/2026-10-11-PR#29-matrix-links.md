# PR#29: Matrix Links Open Element on the Desktop and on the Phone

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

A click on a Matrix message opens matrix.to in the browser on the desktop, which offers to install a client,
although Element Desktop is installed.
On Linux no app can take over `https://matrix.to` from the browser, as the Element app does on a phone;
only Element Desktop's own schemes, e.g. `io.element.desktop://`, leave the browser.
`matrix.link: io.element.desktop` sets such a scheme, but it holds for every device, and a phone without Element Desktop cannot open it.
Messages kept in `messages.json` also keep the link they got when they came in, whatever `link` says later.

## Non-Goals

- No registration of Element Desktop for `https://matrix.to`; Linux has no such thing for a single domain.
- No setting per device: `link` names the desktop's client, a phone always gets matrix.to.

## Specification

### Feature: Matrix links that open Element

#### Scenario#29.01: on the desktop a message opens Element Desktop, also a kept one

&hellip; so that a click leads to the message in the installed client.

- **Given** `matrix.link: io.element.desktop`, and a message kept in `messages.json` from before with a matrix.to link
- **When** the user clicks the message in the dashboard on the desktop
- **Then** Element Desktop opens at the message

##### Verified by

- [MatrixMessagesTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixMessagesTest.kt):
  "a kept row takes the configured form of the link; a row without IDs keeps its link".
- [MatrixSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/MatrixSourceTest.kt):
  "the local mode shows the relay's messages, without a session of its own" reads a kept matrix.to row
  as `io.element.desktop://vector/webapp/#/room/…`.
- Manual: on the desktop the page keeps `io.element.desktop://…` and opens it without a new tab;
  `xdg-mime` hands `x-scheme-handler/io.element.desktop` to `element-desktop.desktop`.

#### Scenario#29.02: on a phone a message opens the Element app

&hellip; so that the same dashboard works on the phone, too.

- **Given** `matrix.link: io.element.desktop`
- **When** the user clicks a message in the dashboard on a phone
- **Then** the link is the message's matrix.to address, which the Element app takes over

##### Verified by

- Manual: at the mobile preset (Android) the page turns `io.element.desktop://vector/webapp/#/room/!r:x/$e`
  into `https://matrix.to/#/!r:x/$e` in a new tab; the demo's direct message and mention link to matrix.to.

## Solution

- `MatrixMessages.relinked` gives a kept row the link of `matrix.link`;
  `MatrixSource` applies it to every row read from `messages.json`, whether it syncs or reads along.
- The page links a Matrix message on a phone to its matrix.to address.
  A link with an app's scheme opens without `target="_blank"`, so no blank tab stays.
- The example configurations and `dashboard/README.md` say which client each `link` opens.
- Version 0.11.2.
- On think, the old local dashboard of 14:04 held the Matrix client with the configuration of then;
  it is stopped, and the relay holds the client now.
  After the merge, the relay on think and the server get the new bundle.

## Open Questions

None.
