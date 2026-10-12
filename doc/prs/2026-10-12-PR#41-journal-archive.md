# PR#41: Werkregie Keeps the Work of Each Day

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The user wants a proposed import file for Werkjournal per calendar day, built from what Werkregie knows about the work of that day.
Werkregie keeps no history: each poll reads the sources again, at most `windowHours` back,
and several sources forget much earlier:

- a Claude Code terminal session disappears as soon as its process ends;
- the Gitea feed gives only the last 50 actions, Matrix keeps 200 messages, and Taiga lists only open items;
- an item has one point in time, no start and no active time.

Werkjournal books whole hours per day and project.
For that, Werkregie needs the active time of each session per day, and a record of each day that outlives the sources.

## Non-Goals

- No proposal, no mapping to Werkjournal customers and projects, and no page yet; these follow in a second PR.
- No recovery of days before the deployment.
- No change to the items the page shows.

## Specification

### Feature: Werkregie keeps the work of each day

#### Scenario#41.01: a pause splits the active time of a session

&hellip; so that a session open for hours counts only the time it was worked in.

- **Given** a Claude Code transcript with entries at 09:00, 09:10, 09:20 and 10:00, and `journal.gapMinutes` 15
- **When** the relay reads the transcripts
- **Then** it reports the session with the active spans 09:00–09:20 and 10:00–10:00

##### Verified by

- [TranscriptActivityTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/TranscriptActivityTest.kt): "a pause longer than the gap splits the active time of a session".
- Manual: on think, the 64 sessions of 7 days gave 345 spans; the first read took 4.8 s, each later one 18 ms.

#### Scenario#41.02: an ended terminal session still counts

&hellip; so that the work of a closed terminal is not lost.

- **Given** a Claude Code terminal session whose process has ended, with its transcript written today
- **When** the relay reads the transcripts
- **Then** it reports the session with its active spans, its working directory, its repository and its branches

##### Verified by

- [TranscriptActivityTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/TranscriptActivityTest.kt): "a transcript counts without a running process, with its directory, repository, branches and title",
  "reads a growing transcript on, and a line still being written only once it is complete".
- [RelayClientTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/relay/RelayClientTest.kt): "reports the active time of the local sessions along".

#### Scenario#41.03: the active time of a Codex thread

&hellip; so that Codex work counts like Claude Code work.

- **Given** a Codex thread whose rollout file has entries at 14:00 and 14:05
- **When** the relay reads the threads
- **Then** it reports the thread with the active span 14:00–14:05 and its name

##### Verified by

- [TranscriptActivityTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/TranscriptActivityTest.kt): "the active time of a Codex thread comes from its rollout file".

#### Scenario#41.04: the server files the spans by local day

&hellip; so that a day of the user's calendar holds exactly its own work.

- **Given** a relay report with a span from 23:30 to 00:20 local time
- **When** the server records the report
- **Then** the day file of the first day holds 23:30–24:00, and that of the second day 00:00–00:20

##### Verified by

- [JournalArchiveTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalArchiveTest.kt): "a span over midnight falls into the files of both days".

#### Scenario#41.05: a recorded span stays

&hellip; so that a day keeps its work after the relay forgets it, e.g. after `windowHours`.

- **Given** a day file with the span 09:00–09:20 of a session
- **When** the relay reports the session with the span 10:00–10:30 only
- **Then** the day file holds both spans

##### Verified by

- [JournalArchiveTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalArchiveTest.kt): "a recorded span stays when the relay no longer reports it".

#### Scenario#41.06: the items of the sources are kept by day

&hellip; so that pull requests, builds, reviews, tasks and messages of a day remain after the sources forget them.

- **Given** a merge in the Gitea feed, a Werkator build, a werkrevisor review, a Taiga task and a Matrix message, each of today
- **When** the server records the items
- **Then** the day file of today holds each of them, and a later state of an item replaces its earlier one

##### Verified by

- [JournalArchiveTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalArchiveTest.kt): "keeps the items of each day, a later state replacing the earlier one, the sessions apart".

#### Scenario#41.07: the record needs no viewer

&hellip; so that a day is complete even when nobody opened the page.

- **Given** a running server that nobody polls
- **When** a minute passes
- **Then** the server has recorded the current items and spans

##### Verified by

- [JournalArchiveTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalArchiveTest.kt): "the recorder fills the archive every interval, without anybody asking for the page".

#### Scenario#41.08: old days are deleted

&hellip; so that the record does not grow without end.

- **Given** `journal.keepDays` 400 and a day file 401 days old
- **When** the server records
- **Then** the old day file is deleted

##### Verified by

- [JournalArchiveTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/journal/JournalArchiveTest.kt): "deletes the days older than keepDays".

#### Scenario#41.09: a day only with the access token

&hellip; so that the record of the user's work stays private.

- **Given** a server with a recorded day
- **When** a request asks for `/api/journal/<day>` with and without the access token
- **Then** it gets the day with the token, and HTTP 401 without it

##### Verified by

- [WebServerTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt): "gives the record of a day only with the token".

## Solution

- `TranscriptActivity` reads the time stamps of the Claude Code transcripts, `claude.projectsDir`,
  and of the Codex rollout files of the threads in `state_5.sqlite`.
  It parses only the top-level fields of a line and reads each file on from where it stopped,
  at most every 30 s.
  A session's title is its last `customTitle`, for Codex the thread's name; the branches come from `gitBranch` and `git_branch`.
- `RelayReport` gets `activity`, the sessions with their spans within `windowHours`;
  the registry names them after the relay, as the items.
  A server older than 0.22.0 skips the field, an older relay sends none.
- `JournalArchive` keeps one file per local day in `<stateDir>/journal/`, with the sessions and the items.
  It joins spans with those recorded before and replaces an item by its later state;
  the key of an item is source, origin, kind and URL or title, for a Gitea activity also its operation.
  Session items stay out, since the sessions with their active time stand there.
- `JournalRecorder` records every minute in the modes `server` and `local`; the relay only reports.
- `GET /api/journal/YYYY-MM-DD` returns a day; on a server only with the access token, as cookie or bearer.
- Configuration `journal` with `enabled`, `zone`, `gapMinutes` and `keepDays`; the server on mih09 gets `zone: Europe/Berlin`.
- Version 0.22.0.

## Open Questions

- [x] Which entries form a Werkjournal entry?
  One per Taiga story and day where a Taiga ID is known, else one per project and day; decided by the human, for the second PR.
- [x] Where do the hours come from?
  From the active time of the sessions, split by a pause over 15 min; decided by the human.
- [x] How far back must a day be available?
  Each day since the deployment, from the record of this PR; decided by the human.
- [x] Does the record keep the text of Matrix messages?
  Only what the relay already sends the server: room, sender, time and the start of the text.

## Follow-up PRs

- The proposal of a Werkjournal import file for a selected day, with the mapping in `journal:` of `dashboard.yml`,
  a day picker, and copying and downloading the file.
