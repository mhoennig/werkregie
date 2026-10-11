# PR#27: The Overview as an Ampel with a Cockpit (Design A3)

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [Design canvas](https://claude.ai/artifact/ECev4QAANhtKQmRZV3NT5j): drafts A to C and the variants A2 and A3; the user chose A3.

## Problem

The overview is a column of equal panels.
The user has to read all of them to answer the two questions that matter most:
is everything still connected, and where must I act?
On a phone this takes much scrolling.
The load of the Werkator hosts and the review costs of werkrevisor are not shown at all.
The page speaks German only.

## Non-Goals

- No new data of Claude Code, Codex, Gitea, Taiga or Matrix; the overview arranges what the sources deliver.
- No translation of texts the sources compose, e.g. Gitea activity titles; only the page's own texts, the markers and their reasons.
- No actions that change something in a tool; the links lead there.

## Specification

### Feature: the overview at a glance

#### Scenario#27.01: the connection bar names the sources and the one that fails

&hellip; so that the user sees at once whether everything is connected.

- **Given** eight sources, one of them a Werkator instance that does not answer
- **When** the user opens the overview
- **Then** the bar says "7 von 8 verbunden" with the error, shows each source as its tool's logo with a status dot,
  and the failing one in clay with its name

##### Verified by

- [RelayRegistryTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/relay/RelayRegistryTest.kt):
  "reports a chip per relay with the tools that work and the ones that fail", "a silent relay turns stale, …".
- Manual: a Werkator `nirgends` at `http://127.0.0.1:9` shows "7 von 8 verbunden · Werkator nirgends: ConnectException",
  its tile in clay with "nirgends", and the badge 1 on the tab Quellen.

#### Scenario#27.02: "Du bist dran" groups the next steps by action

&hellip; so that the most urgent step stands first and each one is a click away.

- **Given** pull requests marked stuck, approve and merge, and two unread messages, one direct and one mentioning the user
- **When** the user opens the overview
- **Then** the panel groups them as Festgefahren, Freigeben, Mergen and Antworten with their counts,
  each row with the logo of its tool, its reason, its age and, where the item has a link, a slim link to act

##### Verified by

- Manual: the demo shows Festgefahren 1, Freigeben 1, Mergen 1 and Antworten 2, with the Gitea and Matrix logos;
  with real data a merge row of werkcrappit PR#65 carried "Merge ↗" to the pull request.

#### Scenario#27.03: the load of each Werkator host

&hellip; so that the user notices a host under pressure before builds slow down.

- **Given** a Werkator instance whose `/api/system` reports the level "pressure"
- **When** the user opens the overview
- **Then** its panel says "unter Druck" with the reasons, the CPU line of 24 hours, and the use of CPU, RAM and disk

##### Verified by

- [WerkatorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkatorSourceTest.kt):
  "the load: Werkator's verdict and the shares of CPU, RAM and the first disk",
  "a load without numbers says nothing rather than zero",
  "the CPU history: 48 means of equal steps, as a share of the CPUs; a step without samples is null".
- [DashboardTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/DashboardTest.kt): "carries the load and the costs the sources report".
- Manual: Werkator javagil showed "unter Druck" with "swap 2.62 of 4.00 GiB in use", CPU, RAM and SSD; vm4006 "läuft rund".

#### Scenario#27.04: running and failed builds

&hellip; so that a red build is seen without opening Werkator.

- **Given** a running build and a build that failed in the last 24 hours
- **When** the user opens the overview
- **Then** the panel shows both, linked to Werkator, above a bar per hour coloured by outcome

##### Verified by

- Manual: werkcrappit `main` running and werkator `main@mutation` failed at 02:38, linked to their build pages,
  above 24 bars, counted as "2 läuft · 2 rot · 150 grün".

#### Scenario#27.05: the review costs of werkrevisor

&hellip; so that the user keeps an eye on what the reviews cost.

- **Given** a configured werkrevisor statistics service
- **When** the user opens the overview
- **Then** a small tile shows the costs of 24 hours and 7 days, the reviews of 24 hours, and a bar per day

##### Verified by

- [WerkrevisorSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/WerkrevisorSourceTest.kt):
  "the costs of 24 hours and 7 days, and the last 7 days oldest first",
  "a service without sums yet reports no costs rather than zero".
- Manual: "≈ 2,95 $ · 24 h" and "99 Reviews · 7 Tage ≈ 29,81 $" with 7 bars, as on werkrevisor.javagil.de.

#### Scenario#27.06: what runs and which sessions wait, as cards

&hellip; so that the user sees which agent works and which one waits for an answer.

- **Given** busy sessions, running builds and idle sessions
- **When** the user opens the overview
- **Then** the running work shows as cards with the tool's logo, the waiting sessions as dashed cards "wartet auf dich",
  and beyond eight cards the rest as "+N weitere", which leads to the tab Sessions

##### Verified by

- Manual: 5 running and 7 waiting showed as 7 cards and "+5 weitere".

#### Scenario#27.07: the history as a lane per tool

&hellip; so that the user sees when what happened without reading every entry.

- **Given** sessions, builds, reviews and Gitea activity in the last 24 hours
- **When** the user opens the overview
- **Then** each tool has a lane with a cell per hour, the stronger the more happened; a failure in clay, running work light

##### Verified by

- Manual: lanes Claude, Codex, Werkator, werkrevisor and Gitea with 247 events; the hours with failed builds in clay.

### Feature: tabs for the details

#### Scenario#27.08: a tab per kind of item

&hellip; so that the overview stays short and the full lists are one click away.

- **Given** the overview
- **When** the user clicks the tab "Pull Requests"
- **Then** the page shows all open pull requests with the filters, the address ends in `#prs`, and a reload keeps the tab

##### Verified by

- Manual: after a reload of `#prs` the view shows 24 of "24 open" pull requests with source chips, project, period and search.
  Tasks and messages have a tab only where Taiga or Matrix is configured, as their panels before.

#### Scenario#27.09: on a phone the overview needs little scrolling

&hellip; so that the user can check the state on the way.

- **Given** a viewport 375 px wide
- **When** the user opens the overview
- **Then** the tabs form a bar at the bottom, the connection bar shows logos only, and nothing scrolls sideways

##### Verified by

- Manual: at 375 × 812 the first screen holds the connection bar, Du bist dran and the first Werkator panel;
  `scrollWidth` equals `clientWidth`, 375.

### Feature: German or English

#### Scenario#27.10: the language switch

&hellip; so that the page speaks the user's language.

- **Given** the page in German
- **When** the user clicks "EN" in the header
- **Then** the page's texts, the markers and their reasons are English, and the browser keeps the choice

##### Verified by

- Manual: after "EN" and a reload the title is "Pull requests — Werkregie", the tabs "Overview, Pull requests, Sessions, History, Sources",
  `lang` of the page is `en`, and `werkregie-settings` holds `"lang":"en"`.
  Without a choice the page follows the browser's language.

## Solution

- The server reads two more things.
  `WerkatorSource` reads `/api/system` with each fetch and the CPU line `/api/system/history?range=24h` every 5 minutes,
  as 48 means; an older Werkator without `/api/system` still shows its builds.
  `WerkrevisorSource` reads `/api/stats` of werkrevisor's statistics service, configured as `werkrevisors`.
  Both reach the page as `loads` and `costs` of the snapshot.
- A relay's status names the tools that fail and says whether the relay is silent,
  so that the connection bar counts them as not connected.
- `index.html` opens on the overview: connection bar, Du bist dran, load, builds, costs, cards and lanes.
  The lists moved unchanged into the tabs, with the filters, and show all rows.
  The tab is the fragment of the address, e.g. `#prs`.
- The page's texts stand in one table for German and English;
  the reasons that the server writes in German are translated by pattern.
- The tools' own logos are in `static/logos/`: Claude, Codex (OpenAI's mark), Matrix, Taiga and Gitea;
  Werkator and werkrevisor keep their line marks.
  The black ones turn white in the dark mode; the service worker keeps the logos for offline starts.
- Version 0.11.0.
- After the merge, the server's configuration gets `werkrevisors` with `https://werkrevisor.javagil.de`.

## Open Questions

None.

## Additional Changes

- The panels no longer close at three and a half rows; the overview shows what matters, the tabs the complete lists.
- The source line above the panels is gone; the connection bar and the tab Quellen replace it.

## Follow-up PRs

- Texts the sources compose, e.g. Gitea activity titles and relay messages, in both languages: the server sends keys, not German.
