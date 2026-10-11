# PR#28: The Restart Page Retries Every 15 Seconds

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

While the service restarts, Apache shows the page "Werkregie startet neu" with "Bitte in einer Minute erneut laden."
The user has to reload by hand, and does not know when the dashboard is back.

## Non-Goals

- No change to the restart itself or to the `.htaccess`.
- The page stays German, as it is a file in the webspace and knows no language choice.

## Specification

### Feature: the restart page waits for the dashboard

#### Scenario#28.01: the page reloads itself every 15 seconds

&hellip; so that the dashboard shows by itself once the service is back.

- **Given** the service does not answer,
  and Apache shows the restart page "Neuer Versuch alle 15 Sekunden …" at the dashboard's address
- **When** the user waits on the page for 15 seconds
- **Then** the browser loads the dashboard's address again

##### Verified by

- [DeploymentTest](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/DeploymentTest.kt):
  "the maintenance page loads the requested address again every 15 seconds".
- Manual: the page of `init server`, served with 503 by a stand-in for Apache that answers 200 from the second request on;
  headless Chrome with 20 s of virtual time asks `/` twice and ends on the dashboard.

## Solution

- `Deployment.maintenancePage()` carries `<meta http-equiv="refresh" content="15">` and says "Neuer Versuch alle 15 Sekunden …".
  Apache serves the page as `ErrorDocument` at the requested address, so the reload asks for the dashboard again;
  it works without JavaScript.
- Version 0.11.1.
- After the merge, the page in the webspace is replaced by the one `init server` writes; a deployment of the bundle alone keeps the old one.

## Open Questions

None.
