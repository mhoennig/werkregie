# PR#9: Werkator Builds werkregie

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#7](https://git.javagil.de/mi/werkregie/pulls/7): the shared agentic workflow.

## Problem

werkregie has no CI, so nothing checks a PR before the human merges it,
and the review by werkrevisor needs a Werkator build to run in (`#ops.werkator`, `#ops.werkrevisor`).

## Non-Goals

- No review by werkrevisor yet; the host part follows on mih09, `.werkrevisor.toml` in a later PR.
- No deployment of the dashboard by Werkator.

## Solution

- `.werkator.yml` defines the build of the dashboard: `./gradlew build` in `dashboard/`, on every push.
- The Werkbaum sets `#ops.werkator` to `[~]`: the build runs once mih09 registers the repository.
- Version 0.4.1 of the dashboard.
