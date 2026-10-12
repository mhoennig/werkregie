# PR#44: A Claude Code Session Can Be Dismissed from Du bist dran

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#40](https://git.javagil.de/mi/werkregie/pulls/40), which lists sessions that wait for the user.

## Problem

Since PR#40, "Du bist dran" lists a Claude Code session when Jev rates its last message as waiting for the user.
Some of these messages ask the user only formally, e.g. "Entfernen kann ich es, sobald du sagst, dass er fertig ist."
about a pull request that waits for the review of another person.
A stricter question to Jev did not help:
it rated this message at 0.84 as before, but dropped a real request, to merge a pull request and report back, from 0.91 to 0.64.
Only the user knows that nothing is to do, and the session stays in "Du bist dran" until it changes.

## Non-Goals

- No dismissal of pull requests or messages; Gitea and Matrix settle them.
- No list of dismissed sessions and no undo; a new message of the session brings it back.
- No change to the rating by Jev.

## Specification

### Feature: a Claude Code session can be dismissed from Du bist dran

#### Scenario#44.01: a dismissed session leaves Du bist dran

&hellip; so that a session without a real request does not hold the user's attention.

- **Given** a Claude Code session that "Du bist dran" lists under "Antworten"
- **When** the user dismisses it with ✕
- **Then** "Du bist dran" no longer lists it, also on the other devices of the user

##### Verified by

- [DismissalsTest "a dismissed session loses its marker until it changes"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/DismissalsTest.kt)

#### Scenario#44.02: a new message brings the session back

&hellip; so that a later real request is not lost.

- **Given** a dismissed Claude Code session
- **When** Claude writes a new message in it
- **Then** "Du bist dran" lists the session again

##### Verified by

- [DismissalsTest "a dismissed session loses its marker until it changes"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/DismissalsTest.kt)

#### Scenario#44.03: only the holder of the access token dismisses

&hellip; so that a visitor of the demo cannot change what the user sees.

- **Given** a server with an access token
- **When** a request without the token dismisses a session
- **Then** the server refuses it with HTTP 401, and the session stays listed

##### Verified by

- [WebServerTest "a dismissal needs the access token as bearer header"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt)

#### Scenario#44.04: a dismissal survives a restart

&hellip; so that a deployment does not bring back all dismissed sessions.

- **Given** a dismissed Claude Code session
- **When** the server restarts
- **Then** "Du bist dran" still does not list the session

##### Verified by

- [DismissalsTest "dismissals survive a restart"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/DismissalsTest.kt)

## Solution

- `POST /api/dismiss` with `{"key", "time"}` keeps that the session with this key is dismissed up to this time.
  The key is the session's link to Claude Desktop, or its resume command for a terminal session.
- On a server the request needs the access token as bearer header; the page reads it from its cookie.
  A cookie alone does not count, so that no other site can make a logged-in browser dismiss a session.
  The body must be JSON, so that a plain HTML form of another site cannot post it to the local mode either.
- The dashboard removes the marker `ask` from a dismissed session as long as the session's time is not newer.
  A newer time, e.g. after a new message, ends the dismissal and removes it.
- The dismissals live in `dismissed.json` in the state directory, beside `relays.json`.
- The page shows ✕ on each session of "Du bist dran", not in the demo.
- Version 0.26.0.

## Open Questions

- [x] Where are dismissals kept?
  On the server, for all devices of the user, with the existing access token; chosen by the human.
- [x] Sessions only, or every row of "Du bist dran"?
  Sessions only, chosen by the human.

## Additional Changes

- Werkbaum warned that the plan node `#dash.claude` has children and a size; its size (S) is removed.
