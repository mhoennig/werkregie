# PR#36: The Page Loads Itself Again for a New Version

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The page loads its code only when it opens; it asks `/api/dashboard` every 5 s.
The footer shows the version from that answer, i.e. the version of the server.
After a deployment, an open tab or the installed app shows the new version but runs the old code.
After the deployment of 0.14.0, the user saw "v0.14.0" and waited in vain for the inverted cards of PR#33.

## Non-Goals

- No hint and no button; the page loads itself again without asking.
- No check of the relays' versions.

## Specification

### Feature: the page runs the version of its server

#### Scenario#36.01: a new version loads the page again

&hellip; so that an open page shows what the server delivers.

- **Given** an open overview of version 0.16.0
- **When** the server answers with version 0.17.0
- **Then** the page runs the code of 0.17.0, and its footer shows "v0.17.0"

##### Verified by

- Manual: see "Check in the Browser".

#### Scenario#36.02: the footer shows the version of the page

&hellip; so that the footer says which code runs.

- **Given** a page of version 0.17.0
- **When** the server answers with version 9.9.9
- **Then** the footer shows "v0.17.0"

##### Verified by

- [WebServerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt):
  "the page carries the version of the build": the version the footer takes; `version()` is the one the server reports.
- Manual, for the footer's text: see "Check in the Browser".

#### Scenario#36.03: no endless reloads

&hellip; so that a page that cannot get the new code stays usable.

- **Given** a page that loaded itself again for version 0.17.0 and still runs 0.16.0, e.g. from the offline cache
- **When** the server answers with version 0.17.0 again
- **Then** the page does not load itself again and shows the data

##### Verified by

- Manual: see "Check in the Browser".

### Check in the Browser

The dashboard 0.17.0 ran in local mode in Claude's browser pane; a stand-in for `fetch` changed the version in the answers.

- The footer showed "v0.17.0" while the server answered "9.9.9" (#36.02).
- The answer "9.9.9" loaded the page again once; a marker set on the old page was gone afterwards (#36.01).
- After that, further answers with "9.9.9" did not load it again; the page stayed live with 8 cards (#36.03).

## Solution

- `processResources` writes the version into `<meta name="werkregie-version">` of `index.html`, as it does with the repository's address.
- `load()` compares the version of each live answer with it; on a difference it calls `location.reload()`.
  The service worker answers the page network first, so the reload gets the new code.
- `sessionStorage` keeps the version a tab loaded itself again for; the same version does not do it twice.
  Where `sessionStorage` is blocked, the guard cannot work, so the page does not load itself again and shows the new data.
- The kept state of the offline view does not count: its version is old by nature.
- The footer shows the version of the page, set once at the start.
- `version()` in `Main.kt` is internal, so `WebServerTest` compares the page with it.
- Version 0.17.0.

## Open Questions

- [x] Load the page again at once, show a hint with a button, or load it only while it is hidden?
  At once, decided by the user: the page only reads, and the reload keeps the view in the address's `#`.
  `localStorage` keeps the source chips, the project, the time window and the language (`werkregie-settings`, see `persist()`)
  and the theme (`werkregie-theme`); only a typed search text is lost.
- [ ] TODO.js-tests: the reload and its guard are checked only by hand;
  the repository has no runtime for tests of the page's JavaScript, and a new one needs the consent of the human.

## Additional Changes

- A card with new content shows inverted for 12 s instead of 7 s (PR#33); 7 s were too short for the user.
