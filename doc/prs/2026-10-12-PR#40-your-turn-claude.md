# PR#40: Du bist dran Shows a Claude Code Session That Waits for the User

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

"Du bist dran" lists pull requests and Matrix messages, but no Claude Code session that waits for the user.
A session that asks a question stays unnoticed until the user looks into Claude Desktop.

A measurement on think on 2026-10-12, over the turns of 7 days and a sample checked by hand, showed:

- Claude Code marks a session that blocks on the user in its process file: `status` `waiting`, with `waitingFor`,
  e.g. `input needed` for an open AskUserQuestion, or `dialog open`.
  This was right in both live cases.
- A closed session keeps its unanswered AskUserQuestion only in its transcript.
- About 450 of 1566 turns ended with an AskUserQuestion; many more asked in the text,
  e.g. with options A, B and C, or "Schick mir die Ausgabe".
- Jev rated the last message of an idle session well: all 40 turns with a question or a request rated at least 0.79,
  11 of the 12 others that it rated at least 0.8 asked the user indeed, and all below 0.5 did not.
  One call takes about 350 ms and costs about 0.00004 USD.
- A session whose last activity is newer than its last focus asked the user in only 2 of 5 cases;
  "unread" is no criterion for "your turn".

## Non-Goals

- No change to the session cards below "Du bist dran".
  As before this PR, they mark every idle session with the words "wartet auf dich", whether it asks something or not.
- No Codex threads.
- No rating of sessions that are archived or older than the window.
- No reply to a session from the dashboard.

## Specification

### Feature: Du bist dran shows a Claude Code session that waits for the user

#### Scenario#40.01: a question of Claude is due

&hellip; so that the user answers a question without looking into Claude Desktop.

- **Given** a Claude Code session that waits with `input needed`
- **When** the dashboard reads the sessions
- **Then** "Du bist dran" lists the session under "Antworten" with "Frage offen"

##### Verified by

- [ClaudeCodeSourceTest "a session that waits for the user asks for an answer"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.02: an open dialog is due

&hellip; so that a permission prompt does not hold a session unnoticed.

- **Given** a Claude Code session that waits with `dialog open`
- **When** the dashboard reads the sessions
- **Then** "Du bist dran" lists the session under "Antworten" with "Dialog offen"

##### Verified by

- [ClaudeCodeSourceTest "a session that waits for the user asks for an answer"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.03: a closed session keeps its question

&hellip; so that a question is not lost when its process ends.

- **Given** a closed Desktop session whose transcript ends with an unanswered AskUserQuestion
- **When** the dashboard reads the sessions
- **Then** "Du bist dran" lists the session with "Frage offen"

##### Verified by

- [ClaudeCodeSourceTest "an unanswered question in the transcript asks for an answer"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.04: a question in the text is due

&hellip; so that a question without AskUserQuestion reaches the user too.

- **Given** an idle Claude Code session whose last message asks the user a question in its text
- **When** Jev rates this message at 0.9, above the threshold of 0.75
- **Then** "Du bist dran" lists the session under "Antworten" with "wartet auf dich"

##### Verified by

- [ClaudeCodeSourceTest "Jev decides on the last message of an idle session"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.05: a report is not due

&hellip; so that finished work does not crowd "Du bist dran".

- **Given** an idle Claude Code session whose last message only reports finished work
- **When** Jev rates this message at 0.3, below the threshold of 0.75
- **Then** "Du bist dran" does not list the session

##### Verified by

- [ClaudeCodeSourceTest "Jev decides on the last message of an idle session"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.06: the user's own message is not rated

&hellip; so that a session where the user spoke last costs nothing and shows nothing.

- **Given** an idle session whose transcript ends with a message of the user
- **When** the dashboard reads the sessions
- **Then** the session has no marker, and Jev is not asked

##### Verified by

- [ClaudeCodeSourceTest "only the last message of Claude is rated, once"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.07: each message is rated once

&hellip; so that the polling every few seconds does not cost a call each time.

- **Given** an idle session whose last message Jev rated before
- **When** the dashboard reads the sessions again
- **Then** Jev is not asked again

##### Verified by

- [ClaudeCodeSourceTest "only the last message of Claude is rated, once"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#40.08: without Jev, the process file and the transcript decide

&hellip; so that the dashboard sends no chat text unless configured.

- **Given** a dashboard whose configuration does not name Jev
- **When** an idle Claude Code session ends with the text "Soll ich den PR anlegen?"
- **Then** "Du bist dran" does not list the session

##### Verified by

- [ClaudeCodeSourceTest "Jev decides on the last message of an idle session"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

## Solution

- `ClaudeCodeSource` gives a session that is not busy the marker `ask`, by the first that applies:
  1. the process file says `waiting`: "Frage offen" for `input needed`, "Dialog offen" for `dialog open`, else the raw `waitingFor`;
  2. the tail of the transcript holds an AskUserQuestion without a result: "Frage offen";
  3. Claude spoke last and Jev rates the last text at least `threshold`, default 0.75: "wartet auf dich".
- The transcript is `~/.claude/projects/*/<session>.jsonl`; only its last 512 KiB are read.
- An archived session carries no marker; archiving it in Claude Desktop removes it from "Du bist dran".
- Jev gets the last 3000 characters of the text as `message`, with one `noul` question.
  Its answer is kept per message, so each message costs one call.
  After a failure, Jev is asked again only after 60 s; until then, rule 3 does not apply.
- Configuration `claude.jev` with `apiKeyEnv`, e.g. `TYPESAFE_API_KEY` in the relay's `dashboard.env`;
  without it, no chat text leaves the machine.
- The page lists sessions with `ask` in the group "Antworten" of "Du bist dran" and opens them in Claude Desktop.
- Version 0.21.0.

## Open Questions

- [x] Hard criteria only, or Jev too?
  Both, chosen by the human after the measurement above;
  the hard criteria cover only about a third of the turns that ask the user.
- [x] Which threshold?
  0.75: in the sample it kept the real requests at 0.78 and 0.79, and let the optional hints at 0.54 and 0.55 pass.

## Follow-up PRs

- The session cards could say "wartet auf dich" only for a session with the marker `ask`.
