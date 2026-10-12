# PR#50: The Last Input in the Tooltip Includes an Answer to AskUserQuestion

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

Since PR#47, the tooltip of a waiting Claude Code session in "Du bist dran" shows the user's last input.
Live, on 2026-10-12, both waiting sessions showed no input:
the user had answered only through AskUserQuestion since the last typed prompt,
and that prompt lay before the last 512 KiB of the transcript, the part that is read.

## Non-Goals

- No change to Claude's question in the tooltip.
- No reading of a whole transcript of many megabytes.

## Specification

### Feature: the tooltip shows the user's last input, also an answer to AskUserQuestion

#### Scenario#50.01: an answer to AskUserQuestion is the last input

&hellip; so that the tooltip shows what the user decided last.

- **Given** a session whose user last answered an AskUserQuestion with "OpenRouter"
- **When** the dashboard reads the sessions
- **Then** the last input of the session is "OpenRouter"

##### Verified by

- [TranscriptTest "an answer to AskUserQuestion is the last input"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TranscriptTest.kt)

#### Scenario#50.02: an older prompt is found

&hellip; so that a long run of tool output does not hide the last input.

- **Given** a session whose last prompt "Baue den Export" lies before the last 512 KiB of its transcript
- **When** the dashboard reads the sessions
- **Then** the last input of the session is "Baue den Export"

##### Verified by

- [TranscriptTest "a prompt before the read end is found"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TranscriptTest.kt)

## Solution

- `Transcript.end` takes the answers of an AskUserQuestion from the entry's `toolUseResult.answers` as input of the user.
- Without an input in the last 512 KiB, it reads the transcript backwards in parts of 512 KiB, up to 8 MiB,
  until it finds one.
  The parts end at the start of a line, so no line is cut;
  a part inside one long line holds no whole line, and the search goes on before it.
- A local check on think with the live transcripts showed an input for all 4 waiting sessions, 2 of them answers.
- Version 0.30.0.

## Open Questions

- [x] A typed prompt only, or also an answer to AskUserQuestion?
  Both, chosen by the human; the later one counts.
