# PR#24: The Dashboard on Temurin 25

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Related Links

- [werkregie PR#20](https://git.javagil.de/mi/werkregie/pulls/20) and [PR#22](https://git.javagil.de/mi/werkregie/pulls/22):
  ADR 0001, Temurin 25 as JDK and runtime of all werk repositories, loaded with Foojay.

## Problem

ADR 0001 decides Temurin 25 for all werk repositories.
The dashboard still builds with Temurin 21, and its runtime bundle carries a Temurin 21 runtime.

## Non-Goals

- No change of the dashboard's behaviour.

## Solution

- Toolchain Temurin 25 and the JVM target 25.
- The runtime bundle carries a runtime jlinked from Temurin 25; its module list stays.
- `.werkator.yml`, `settings.gradle.kts` and `dashboard/README.md` name Temurin 25.
- Checked locally: `build` passes on Temurin 25, and the bundle's launcher starts on its Temurin 25.0.4 runtime.
- Werkbaum: `#ops.jdk` step "werkregie builds and runs on Temurin 25".
- Version 0.9.3.
