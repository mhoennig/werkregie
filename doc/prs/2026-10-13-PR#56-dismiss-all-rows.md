# PR#56: Each Row of Du bist dran Has the Dismiss Button

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#44](https://git.javagil.de/mi/werkregie/pulls/44), which brought ✕ for Claude Code sessions.

## Problem

Since PR#44, only a Claude Code session in "Du bist dran" has ✕ at the end of its row.
In the rows without it, the age and the link "Öffnen" stand further right,
so the controls of the rows do not align.

## Non-Goals

- No dismissal of pull requests or messages; Gitea and Matrix settle them.
- No change to the dismissal of sessions.

## Specification

### Feature: each row of Du bist dran has the dismiss button

#### Scenario#56.01: a row that cannot be dismissed shows ✕ as unavailable

&hellip; so that the controls of all rows align, and the user learns why the row stays.

- **Given** a pull request in "Du bist dran"
- **When** the user points at ✕ in its row
- **Then** ✕ shows as unavailable, with the reason "Ein Pull Request erledigt sich in Gitea"

##### Verified by

- Manual: the page with the access token, a pull request, a Matrix message and a Claude Code session in "Du bist dran".

#### Scenario#56.02: an unavailable ✕ does nothing

&hellip; so that a click beside the link does not open the pull request by mistake.

- **Given** a pull request in "Du bist dran"
- **When** the user clicks ✕ in its row
- **Then** nothing happens, and the pull request does not open

##### Verified by

- Manual: as for Scenario#56.01.

## Solution

- Each row of "Du bist dran" ends with ✕.
- A row that cannot be dismissed marks it with `aria-disabled` and dims it; its tooltip names the reason:
  a pull request settles in Gitea, a message by reading it in Matrix, and the demo has no dismissal.
- It is not `disabled`, since some browsers show no tooltip on a disabled button,
  and a click on it would reach the link of the row.
- Version 0.35.0.
