# PR#45: A Werkjournal Import File for a Picked Day

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#41](https://git.javagil.de/mi/werkregie/pulls/41): the record of each day, which this PR reads.
- Werkjournal's import: `src/main/kotlin/de/hoennig/werkjournal/export/ImportService.kt` in the werkjournal repository.

## Problem

The user books the work of each day in Werkjournal, as whole hours per customer and project.
Werkregie records what happened on each day since PR#41, but offers nothing to book from it.
The user wants a proposed import file for a picked calendar day, to correct and import into Werkjournal.

Werkjournal's import, schema version 1, sets the frame:

- an entry has a date, a customer, a project of that customer, whole hours from 1 to 12, and a description;
- a day holds at most 12 hours, existing entries included; a day after today is refused;
- the import only adds: it matches an entry by its content, so a second import of a changed entry adds a second one;
- a customer or project is matched by its id, else by its name; unknown fields fail the whole file;
- the file needs no `period`, so a single day is fine.

## Non-Goals

- No import into Werkjournal from Werkregie; the user imports the file in Werkjournal.
- No AI summary of the descriptions; it follows in a PR of its own.
- No mapping maintained in the page; it lives in `dashboard.yml`.
- No knowledge of the entries Werkjournal already holds for the day.

## Specification

### Feature: A Werkjournal import file for a picked day

#### Scenario#45.01: the proposal of a picked day

&hellip; so that the user books any day since the record began.

- **Given** a recorded day 2026-10-12 with sessions and items
- **When** the user picks 2026-10-12 in the tab "Journal"
- **Then** the page shows the proposed entries of that day with customer, project, hours and description

##### Verified by

- [WebServerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt): "proposes the Werkjournal entries of a day only with the token".
- Manual: a local dashboard with the recorded days 2026-10-11 and 2026-10-12 of the server on mih09:
  the tab showed 4 entries of 2026-10-12, and ‹ switched to 2026-10-11 with 5 entries of 12 h; checked on the desktop and at 375 px.

#### Scenario#45.02: one entry per Taiga story

&hellip; so that the work on a story is booked as one entry, whichever task it was.

- **Given** a session on the branch `mihoe/Taiga545-hardening` and a Taiga task #546 of story #545, both in project "HSAdmin NG"
- **When** the server proposes the day
- **Then** there is one entry for story #545, with its subject in the description

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "one entry per Taiga story, whichever task it was".

#### Scenario#45.03: one entry per project without a Taiga ID

&hellip; so that work without a story is still booked.

- **Given** two sessions and a merge in the repository `werkregie`, none naming a Taiga ID, and `werkregie` mapped to project "Werkregie"
- **When** the server proposes the day
- **Then** there is one entry for project "Werkregie" that names the pull request and the sessions

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "one entry per project without a Taiga ID, naming the pull request and the sessions".

#### Scenario#45.04: the hours from the active time

&hellip; so that parallel and repeated work is not counted twice.

- **Given** sessions of one entry active 09:00–10:00 and 09:30–10:20, and a merge at 11:00
- **When** the server proposes the day
- **Then** the entry has 80 + 15 = 95 minutes and 2 hours

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "the hours are the union of the active spans and a quarter of an hour before each action, rounded half up".

#### Scenario#45.05: less than half an hour is no entry

&hellip; so that a short glance does not become a booked hour.

- **Given** a project with 20 minutes of activity
- **When** the server proposes the day
- **Then** the import file has no entry for it, and the page lists it as a hint with its minutes

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "less than half an hour is a hint, not an entry of the file".

#### Scenario#45.06: unmapped work goes to the fallback project

&hellip; so that no work is lost because the mapping lacks a repository.

- **Given** a session in a repository that no project of `journal.customers` names, and `journal.fallback` "Intern / Sonstiges"
- **When** the server proposes the day
- **Then** its time counts for an entry of "Intern / Sonstiges"

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "unmapped work goes to the fallback project, or without one stays a hint".

#### Scenario#45.07: a day holds at most 12 hours

&hellip; so that Werkjournal accepts the file.

- **Given** entries of 8, 4 and 3 hours
- **When** the server proposes the day
- **Then** the entries hold 12 hours in all, each at least 1, the largest reduced first

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "a day holds at most 12 hours, the largest entry giving first".

#### Scenario#45.08: the same day gives the same file

&hellip; so that importing a day twice adds nothing in Werkjournal.

- **Given** a recorded day
- **When** the server proposes it twice
- **Then** both files are equal, ids and descriptions included

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "the same day gives the same file, ids and descriptions included".

#### Scenario#45.09: corrections go into the file

&hellip; so that the user books what was really done.

- **Given** a proposal of 2026-10-12
- **When** the user changes the hours and the description of an entry and downloads the file
- **Then** the file `werkjournal-2026-10-12.json` holds the changed values, and an entry set to 0 hours is left out

##### Verified by

- Manual: a local dashboard with the recorded days 2026-10-11 and 2026-10-12 of the server on mih09: an entry set from 3 to 2 hours with a new text, and one set to 0 hours,
  gave a file of 3 entries and 8 hours with the new text; the download is named `werkjournal-2026-10-12.json`.

#### Scenario#45.10: the file holds only the fields Werkjournal allows

&hellip; so that Werkjournal imports it without an error.

- **Given** a proposal with customers, projects and entries
- **When** the server builds the import file
- **Then** each object of the file holds only the fields Werkjournal allows

##### Verified by

- [JournalProposerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalProposerTest.kt): "the file holds only the fields Werkjournal allows, each project of its customer, whole hours from 1 to 12".

## Solution

- `JournalProposer` turns a `JournalDay` into a `Proposal`: the entries with their evidence, and the Werkjournal file.
  - A session, a Gitea item, a build or a review counts for the project that names its repository, a Taiga item by its slug,
    a Matrix message by its room; else for `journal.fallback`.
  - A Taiga ID in a session's title or branches, newest branch first, or in a Gitea title, link or commit message
    picks the story within the project's first Taiga slug.
  - The minutes are the union of the sessions' spans and of 15 min before each Gitea activity, Taiga item and Matrix message.
  - The description names the story, then the pull requests without `#n` and `WIP:`, the Taiga items, the sessions and the rooms,
    each sorted, at most 2000 characters.
  - Ids are name-based UUIDs of customer, project, day and story; Werkjournal matches customers and projects by name anyway.
- `TaigaSource` resolves a ref by Taiga's `/resolver`, a task to its `user_story_extra_info`, and keeps the answers;
  an unreachable Taiga leaves the work with its project.
- `GET /api/journal/YYYY-MM-DD/proposal`, behind the access token as the day itself.
- Configuration `journal.customers` with projects of `repositories`, `taiga`, `rooms` and `paths`, and `journal.fallback`.
- The tab *Journal* fetches the proposal only when it opens or the day changes, never with the polling, so corrections stay.
  The page builds the file from the corrected entries; hours are clamped to 0 to 12, and a total over 12 h shows a warning.
- Version 0.28.0.

## Open Questions

- [x] Which entries form a Werkjournal entry?
  One per Taiga story and day where a Taiga ID is known, else one per project and day; decided by the human.
- [x] Where do the hours come from?
  The active time of the sessions, a pause over 15 min splitting it, and 15 min for each action in Gitea, Taiga and Matrix;
  overlaps count once; decided by the human.
- [x] What happens to an entry below one hour?
  Rounded half up; below 30 min it is no entry, only a hint; decided by the human.
- [x] What happens to work without a mapping?
  It goes to `journal.fallback`; decided by the human.
- [x] How is a Taiga ID found?
  By patterns in branches, titles and commit messages, resolved by Taiga, a task to its story; decided by the human.

## Follow-up PRs

- An AI summary as the description of an entry.
