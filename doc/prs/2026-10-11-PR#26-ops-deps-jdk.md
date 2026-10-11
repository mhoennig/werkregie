# PR#26: The Werkbaum No Longer Says That JDK 21 Stays

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#24](https://git.javagil.de/mi/werkregie/pulls/24): the dashboard on Temurin 25.

## Problem

werkrevisor found in PR#24 that `#ops.deps` still says "JDK 21 stays, as Werkator's".
The fix reached the branch of PR#24 only after its merge, so `main` lacks it.

## Non-Goals

- No other change.

## Solution

- `#ops.deps` points to `#ops.jdk` for the JDK.
- Version 0.9.4.
