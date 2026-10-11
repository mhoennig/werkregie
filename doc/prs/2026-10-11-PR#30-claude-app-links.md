# PR#30: Claude Code Sessions Open in Claude Desktop

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The dashboard shows Claude Code sessions, but a click on one leads nowhere;
the user has to find the session in Claude Desktop by its title.
The same holds for the Claude logo in the connection bar, though Claude Desktop has a view of the sessions waiting for the user.

## Non-Goals

- No links into Codex: `codex://threads/<id>` reaches the ChatGPT app on Linux, but the app does not act on it (tried on 2026-10-11).
- No link for a terminal session: `claude://resume` would import it into Claude Desktop as a new session.
- No app links on a phone, where Claude Desktop does not run.

## Specification

### Feature: Claude Code sessions open in Claude Desktop

#### Scenario#30.01: a session opens in Claude Desktop

&hellip; so that the user gets to a waiting session with one click.

- **Given** a Claude Code session of Claude Desktop in the dashboard on the desktop
- **When** the user clicks its card or its row
- **Then** Claude Desktop shows that session

##### Verified by

- [ClaudeCodeSourceTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/ClaudeCodeSourceTest.kt):
  "a Desktop session links into Claude Desktop, a terminal session does not".
- Manual: `xdg-open "claude://code/continue?session=local_3f695a1e-…"` made Claude Desktop log
  `setFocusedSession: sessionId=local_3f695a1e-…`, the Werkrevisor session;
  on the desktop the page's session cards carry these links without `target`, at the mobile preset none.

#### Scenario#30.02: the Claude logo opens the sessions waiting for the user

&hellip; so that the user sees at once which sessions need an answer.

- **Given** the connection bar on the desktop
- **When** the user clicks the Claude logo
- **Then** Claude Desktop shows its view "Sitzungen, die auf dich warten"

##### Verified by

- Manual: `xdg-open "claude://code/needs-input?source=werkregie"` made Claude Desktop log `setFocusedSession: sessionId=null`,
  its list of waiting sessions; on the desktop the Claude tile links there, at the mobile preset it has no link.

## Solution

- `ClaudeCodeSource` gives a Desktop session the link `claude://code/continue?session=<id>`,
  for IDs of the form `local_…`, which Claude Desktop accepts; a terminal session gets none.
- The page links the Claude tile of the connection bar to `claude://code/needs-input`.
  On a phone app links are dropped; an app link opens without `target="_blank"`.
- The session cards follow the same rule as the rows.
- Version 0.11.3.

## Open Questions

None.
