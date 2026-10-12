# PR#37: Du bist dran Follows a Requested Review and Waits for werkrevisor's Start

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [PR#34](https://git.javagil.de/mi/werkregie/pulls/34), where both weaknesses showed on 2026-10-12.

## Problem

"Du bist dran" lists a foreign pull request under "Dein Approval" by werkrevisor's commit statuses of its head.
Two cases go wrong:

1. werkrevisor requests the review of the user again when an approval came before its result.
   The approval of the head stays valid in Gitea, so the pull request shows "Von dir approved".
   While werkrevisor runs again after a new push, the pull request shows nothing at all.
   The user does not see that the review is requested.
2. Right after a push, werkrevisor has not yet set a status on the new head.
   For this time the pull request shows "Dein Approval: kein werkrevisor", a false alarm.

Gitea's `requested_reviewers` of a pull request does not tell a pending request:
it lists each user with a review of the pull request, e.g. `ai-reviewer` after its comment,
and `mi` after the approval of the current head.

## Non-Goals

- No change to the other markers, e.g. "Festgefahren" or "Mergen".
- No dismissal of the user's approval; that is werkrevisor's job.
- No knowledge of which repositories werkrevisor reviews.

## Specification

### Feature: Dein Approval follows a requested review and waits for werkrevisor's start

#### Scenario#37.01: a requested review asks for the approval while werkrevisor runs

&hellip; so that the user sees a requested review also during the next run of werkrevisor.

- **Given** a foreign pull request with a pending review request to the user
- **When** werkrevisor reviews its new head
- **Then** the pull request shows "Dein Approval: Review angefragt"

##### Verified by

- [GiteaSourceTest "a review requested from the user asks for the approval, also while werkrevisor runs"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

#### Scenario#37.02: a request after the user's approval asks again

&hellip; so that the user approves again when werkrevisor dismissed the early approval.

- **Given** a foreign pull request with werkrevisor's verdict YELLOW and the user's approval of the head
- **When** werkrevisor requests the review of the user again
- **Then** the pull request shows "Dein Approval: werkrevisor YELLOW", not "Von dir approved"

##### Verified by

- [GiteaSourceTest "a review requested from the user asks for the approval, also while werkrevisor runs"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

#### Scenario#37.03: an earlier review is no request

&hellip; so that an earlier review of the user does not count as a request.

- **Given** the reviews of a pull request: an approval by the user and a comment by `ai-reviewer`
- **When** the dashboard reads the requested reviewers
- **Then** it finds none

##### Verified by

- [GiteaSourceTest "only a pending REQUEST_REVIEW is a requested review"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

#### Scenario#37.04: a pending request counts

&hellip; so that a request of werkrevisor after the user's approval reaches the user.

- **Given** the reviews of a pull request: an approval by the user and a `REQUEST_REVIEW` of the user
- **When** the dashboard reads the requested reviewers
- **Then** it finds the user

##### Verified by

- [GiteaSourceTest "only a pending REQUEST_REVIEW is a requested review"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

#### Scenario#37.05: right after a push, werkrevisor's status is awaited

&hellip; so that a push does not cause a false alarm.

- **Given** a foreign pull request without a status of werkrevisor, last changed 30 s ago
- **When** the dashboard reads it
- **Then** the pull request shows no marker

##### Verified by

- [GiteaSourceTest "werkrevisor's first status is awaited for 2 minutes after the last change"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

#### Scenario#37.06: without werkrevisor the approval is due after 2 minutes

&hellip; so that a pull request without werkrevisor still reaches the user.

- **Given** a foreign pull request without a status of werkrevisor, last changed 3 min ago
- **When** the dashboard reads it
- **Then** the pull request shows "Dein Approval: kein werkrevisor"

##### Verified by

- [GiteaSourceTest "werkrevisor's first status is awaited for 2 minutes after the last change"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/sources/GiteaSourceTest.kt)

## Solution

- The requested reviewers come from the reviews of the pull request with the state `REQUEST_REVIEW`.
  Gitea removes such a review when the user submits a review,
  so it exists only while the request is pending, e.g. review 501 of PR#34 after the approval 500.
- A pending request of the user takes the place of the user's approval of the head.
- While werkrevisor runs, a pending request of the user shows "Dein Approval: Review angefragt".
  After the run the verdict decides, as before; RED is the agent's to fix.
- Without a status of werkrevisor, a pull request counts as running until 2 min after its `updated_at`.
  Gitea moves `updated_at` with each push and title change;
  werkrevisor's first status came 6 to 10 s after them in PR#32 to PR#35.
  If werkrevisor does not start, the marker "kein werkrevisor" comes after 2 min.
- Version 0.18.1.

## Open Questions

- [x] Which rule avoids the false alarm right after a push?
  A grace of 2 min after the last change of the pull request, chosen by the human;
  it also covers the first run after `WIP: ` is removed, and fails safe if werkrevisor does not start.
  A rule by earlier reviews of a review bot would miss the first run and hide the pull request for good if werkrevisor fails.

## Additional Changes

- "Wartet auf Review" of the user's own pull request names only the pending requests, no reviewer who already reviewed.
