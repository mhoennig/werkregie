# PR#10: werkrevisor Asks the Risk Categories That Fit werkregie

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkrevisor PR#79](https://git.javagil.de/mi/werkrevisor/pulls/79): the host config for werkregie.

## Problem

werkrevisor reviews werkregie with its host config, which asks Jev only whether a file touches security
or weakens the checks.
The risk template `jvm-spring-backend` names the risk more precisely, but some of its categories do not fit a dashboard
with one user.

## Non-Goals

- No own category for werkregie yet.

## Solution

- `.werkrevisor.toml` chooses `jvm-spring-backend` and switches off
  `object_level_authorization`, `transaction_integrity` and `business_invariants`:
  the dashboard has one user and no business transactions.
  It does run SQL on Codex' SQLite state and stores the Matrix session in H2, so the categories on SQL,
  schema and stored data stay.
- Measured with Jev on the 28 changed files of werkregie PR#2, #5, #6, #8 and #9:
  only PR#8, the Matrix login with the user's own account, reached a threshold (`authentication` 0.98, `security` 0.97);
  the closest other value was `error_handling` 0.94 on `dashboard/gradlew.bat`, below its 0.95.
- The Werkbaum sets `#ops.werkator` to `[^]` and `#ops.werkrevisor` to `[~]`.
- Version 0.4.2 of the dashboard.

## Additional Changes

- The first Werkator build of werkregie failed: its build sandbox has Debian's OpenJDK, not Temurin.
  The runtime bundle needs Temurin, whose JRE starts on Hostsharing's hosts with their older glibc.
  As in Werkator, the Foojay resolver in `dashboard/settings.gradle.kts` lets Gradle download Temurin 21;
  version 1.0.0, since 0.8.0 fails on Gradle 9.
