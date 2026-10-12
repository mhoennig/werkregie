# PR#52: The Page Loads Robustly After Waking Up and Shows a Late Relay

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

On the phone, the installed page showed only one entry under "Du bist dran" after waking up, a PR;
several sessions of Claude Code were waiting.
A manual reload showed all of them.

The cause is not certain; two candidates:

1. The page loaded during the deployment at 18:07, while server and relay restarted;
   the relay `think` reported again only about 13 s later.
   A relay that is late or missing is not visible in the header: its sessions are silently missing.
2. After waking up, the network was not ready; the first `load()` failed or hung, and the page kept the old state.
   `load()` runs on `visibilitychange` and every 5 s; a request that hangs never ends,
   and a return from the back/forward cache fires no `visibilitychange`.

## Non-Goals

- No new server endpoint and no push channel; the page keeps polling.
- No change of `relayStaleSeconds` or `relayGoneSeconds`.
- No memory of the relays in the browser; the server alone knows which relays report.

## Specification

### Feature: the page loads the current state after waking up

#### Scenario#52.01: a return to the page loads at once

&hellip; so that a page from the back/forward cache does not show an old state for 5 s.

- **Given** the user left the page, and the browser kept it in its back/forward cache
- **When** the user goes back to the page
- **Then** the page asks the server at once

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#52.02: the network coming back loads at once

&hellip; so that the page shows the current state as soon as it can.

- **Given** an open page whose last load failed
- **When** the network is back (`online`)
- **Then** the page asks the server at once

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#52.03: a failed load is retried after 1 s

&hellip; so that a network that is not ready yet after waking up costs 1 s, not 5 s.

- **Given** an open page that showed the current state
- **When** the server does not answer one load because the network is not ready
- **Then** the page asks the server again after 1 s instead of 5 s

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#52.04: a hanging request counts as failed

&hellip; so that a dead connection after waking up does not keep the old state.

- **Given** an open page
- **When** the server does not answer within 10 s
- **Then** the page aborts the request and shows "offline"

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#52.05: an older answer does not replace a newer one

&hellip; so that two loads at the same time, e.g. on `pageshow` and `visibilitychange`, do not show an old state.

- **Given** two loads in progress
- **When** the answer of the first one arrives after the answer of the second one
- **Then** the page ignores the answer of the first one

##### Verified by

- Manual: see "Check in the Browser".

### Feature: the header shows a late or missing relay

#### Scenario#52.06: a late relay shows in the header

&hellip; so that sessions that are missing or old do not go unnoticed.

- **Given** the relay `think`, silent for longer than `relayStaleSeconds`
- **When** the page shows the answer of the server
- **Then** the indicator in the header shows "think still" in amber, with the time of its last report as tooltip

##### Verified by

- [RelayRegistryTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/relay/RelayRegistryTest.kt):
  the existing tests of the `silent` status.
- Manual, for the header: see "Check in the Browser".

#### Scenario#52.07: a server without any relay shows it

&hellip; so that a lost relay state, e.g. after a deployment, does not look like a quiet day.

- **Given** a server whose relay registry is empty
- **When** the page shows the answer of the server
- **Then** the indicator in the header shows "kein Relay" in amber, and the sources list a failing "Relays"

##### Verified by

- [RelayRegistryTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/relay/RelayRegistryTest.kt):
  "an empty registry reports that no relay reports".
- Manual, for the header: see "Check in the Browser".

### Check in the Browser

The dashboard 0.32.0 ran in server mode with `relayStaleSeconds: 8` in Claude's browser pane;
`curl` posted the reports of a relay `think`, and a stand-in for `fetch` failed, hung or delayed the answers.

- Without any relay, the header showed "no relay" in amber, the overview "Relays: kein Relay meldet" (#52.07).
- With fresh reports it showed "live"; 8 s after the last report "think silent" in amber,
  with "Relay think: last seen 29 s ago" as tooltip (#52.06).
- At 375 px width the header showed "THINK SILENT" in amber beside its dot, without a horizontal scroll bar.
- `online` and a `pageshow` with `persisted` each loaded at once; a `pageshow` without `persisted` did not (#52.01, #52.02).
- Both failing at once: the page loaded again after 1, 2 and 3 s, then no more; it showed "offline" and the banner (#52.03).
- A request that never ended: "live" after 9 s, "offline" after 10.5 s (#52.04).
- Of two loads at once, the first answered 1.5 s after the second; the page kept the answer of the second (#52.05).

## Solution

- `load()` aborts its request after 10 s with `AbortSignal.timeout`, where the browser has it.
- After a failure, `load()` sets one retry after 1 s, at most 3 in a row; two loads that fail at once share it.
  Any load cancels a pending retry, since it takes its place.
- `visibilitychange`, `pageshow` from the back/forward cache and `online` call `loadAfterWaking()`:
  it resets the retries, which a failure before sleeping may have used up.
- Each load has a number; an answer older than the one shown is ignored.
- `setLive()` shows the late relays instead of "live", in amber: a relay with `silent`, or "kein Relay".
  On a phone the indicator otherwise shows its dot only; for a late relay it shows its text, too.
- `RelayRegistry.statuses()` reports a failing status `relays` with "kein Relay meldet" when it holds no relay.
  So the overview and the tab *Quellen* show it, too, through the existing `connections()`.
- Version 0.32.0; PR#51 holds 0.31.0.

## Open Questions

- [ ] TODO.js-tests: the loading and the header are checked only by hand;
  the repository has no runtime for tests of the page's JavaScript, and a new one needs the consent of the human.
