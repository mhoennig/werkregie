# PR#19: The Last State Offline, Marked as Stale

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#2](https://git.javagil.de/mi/werkregie/pulls/2): Werkregie as a PWA, step 1, the app shell offline.

## Problem

The installed Werkregie starts without network, but shows empty panels:
the service worker keeps the page only, never the data.
On the road the user wants to see at least what was open the last time.
`mi` decided on 2026-10-10 that the last state, with pull request and session data, may be kept in the browser's storage.

## Non-Goals

- No caching of `/api/` by the service worker.
- No push notifications.

## Specification

### Feature: the last state offline

#### Scenario#19.01: the page keeps each new state

&hellip; so that a later start without network has something to show.

- **Given** the server has a state with real data that the browser does not keep yet
- **When** the page loads `/api/dashboard`
- **Then** `localStorage` holds this state as `werkregie-last`

##### Verified by

- Manual: on port 4748, `localStorage["werkregie-last"]` held the `generatedAt` of the last answer, 166 kB.

#### Scenario#19.02: a start without the server shows the last state under a banner

&hellip; so that the user sees what was open, and how old it is.

- **Given** a kept state and a page without data
- **When** `/api/dashboard` fails
- **Then** the page shows the kept state, the indicator says "offline",
  and an amber banner says "Server nicht erreichbar – letzter Stand von HH:MM, vor …"

##### Verified by

- Manual: server on port 4748 stopped, `state.data` cleared, `load()` called:
  38 pull request links and the banner "letzter Stand von 10:44, gerade eben".

#### Scenario#19.03: losing the server while the page is open keeps the shown state

&hellip; so that a short outage does not empty the page.

- **Given** the page shows live data
- **When** `/api/dashboard` fails
- **Then** the page keeps showing the data and the amber banner appears; it goes when the server answers again

##### Verified by

- Manual: server on port 4748 stopped with the page open: indicator "offline", banner shown, panels kept.

#### Scenario#19.04: demo data deletes the kept state

&hellip; so that no private data stays in a browser whose token the server no longer accepts.

- **Given** a kept state
- **When** the server answers with demo data
- **Then** `localStorage` holds no `werkregie-last`

##### Verified by

- Manual: a stubbed `fetch` answering `access: "DEMO"` deleted the kept state.

#### Scenario#19.05: forgetting the access token deletes the kept state

&hellip; so that no private data stays in a browser that gave its access up.

- **Given** a kept state and an access token in the browser
- **When** the user forgets the token with the key button
- **Then** `localStorage` holds no `werkregie-last`

##### Verified by

- Manual: the key button with a token cookie and a confirmed dialog deleted the kept state and the cookie.

## Solution

- `load()` keeps each new answer in `localStorage` and, when the request fails, falls back to the kept one;
  the banner `#stale-banner` shows its time and age.
- The service worker stays as it was: it never touches `/api/`.
- `dashboard/README.md` and the Werkbaum describe the kept state.
- Version 0.8.0.

## Open Questions

- [x] Is a service worker start without network verified?
  Not in this PR: the embedded test browser does not register service workers.
  The node "Installed and checked on Android, iOS and desktop" stays planned for that check.

## Additional Changes

- `doc/prs/2026-10-11-PR#18-so-that.md` gets its number in the title; it read "PR#0".
