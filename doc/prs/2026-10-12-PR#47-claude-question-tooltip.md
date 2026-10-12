# PR#47: Du bist dran Shows the Last Input and Claude's Question in the Tooltip

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

"Du bist dran" lists a Claude Code session that waits for the user with its title and "Frage offen" or "wartet auf dich".
With several sessions waiting, the user does not see which question is due where,
nor what the user asked the session last.

## Non-Goals

- No texts on the session cards below "Du bist dran".
- No texts for Codex threads.
- No reply to a session from the dashboard.

## Specification

### Feature: the tooltip of a waiting Claude Code session shows the user's last input and Claude's question

#### Scenario#47.01: short texts appear as they are

&hellip; so that the user sees at once what the session is about and what it asks.

- **Given** a session waiting for the user, whose last input of the user is "Baue den Export" and whose last text of Claude is "Soll ich den PR anlegen?"
- **When** the user points at its row in "Du bist dran"
- **Then** the tooltip shows "Du: Baue den Export" and "Claude: Soll ich den PR anlegen?"

##### Verified by

- [ClaudeCodeSourceTest "a waiting session carries the last input and Claude's question"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt)

#### Scenario#47.02: an open AskUserQuestion is the question

&hellip; so that the tooltip shows the question the user has to answer, not the text before it.

- **Given** a session whose transcript ends with an unanswered AskUserQuestion "Welcher Text soll im Tooltip stehen?"
- **When** the dashboard reads the sessions
- **Then** Claude's question of the session is "Welcher Text soll im Tooltip stehen?"

##### Verified by

- [TranscriptTest "the question of an open AskUserQuestion and the last input of the user"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/TranscriptTest.kt)

#### Scenario#47.03: a long text is summarized

&hellip; so that a long prompt or answer fits into a tooltip.

- **Given** a configured summary model and a waiting session whose last input is 400 characters long
- **When** the dashboard reads the sessions
- **Then** the session carries the model's summary of at most 160 characters instead of the input

##### Verified by

- [SummariesTest "a text over the limit is summarized"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/SummariesTest.kt)
- [SummariesTest "the model gets the text between tags, without reasoning, and its answer is the summary"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/SummariesTest.kt)

#### Scenario#47.04: a restart does not summarize again

&hellip; so that each text costs one call, also when the relay restarts.

- **Given** a waiting session whose long input the model summarized before the relay restarted
- **When** the restarted relay reads the sessions
- **Then** the session carries the earlier summary without a new call to the model

##### Verified by

- [SummariesTest "each text is summarized once, also after a restart"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/SummariesTest.kt)

#### Scenario#47.05: without a model, a long text is cut

&hellip; so that the tooltip works without a key and while the model fails.

- **Given** no configured summary model, or a model that fails
- **When** a waiting session's last input is 400 characters long
- **Then** the session carries the first 159 characters of the input and "…"

##### Verified by

- [SummariesTest "without a model or after a failure the text is cut"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/SummariesTest.kt)

## Solution

- `Transcript.end` also returns the last input of the user and the questions of an open AskUserQuestion.
- A flag `ask` carries `prompt`, the user's last input, and `question`:
  the open AskUserQuestion, else Claude's last text if Claude spoke last.
- `Summaries` shortens each text over 160 characters with `claude.summary`, OpenRouter's chat API;
  the default model is `anthropic/claude-haiku-5.5`, so a cheaper model is a change of `model`.
- `ChatSummarizer` sends the text between `<message>` tags, else Haiku answers it instead of shortening it.
  It asks for 120 characters, since Haiku overshoots a limit by about a fifth.
  An answer over 160 characters, e.g. for a long instruction, is shortened once more, and cut only after that.
  Reasoning is off: on OpenRouter, Haiku 5.5 thinks by default and spent all 200 tokens on it.
  A long text goes to the model as its first and last 2000 characters.
  A summary of a sample of 3 texts cost about 0.00007 USD.
- A summary is kept by the SHA-256 of its text in `summaries.json` in the state directory
  and dropped one day after its last use.
- After a failure, the model is asked again only after 60 s; meanwhile the text is cut.
- The page shows both texts in the tooltip of the row in "Du bist dran".
- Both texts go with the relay's report to the server, which shows real data only with its access token.
- Version 0.27.0.

## Open Questions

- [x] Which text: the user's last input, Claude's question, or both?
  Both, chosen by the human.
- [x] Which access to the model?
  OpenRouter with the existing `OPENROUTER_API_KEY`, chosen by the human, so that the model can be exchanged;
  first Haiku 5.5.
