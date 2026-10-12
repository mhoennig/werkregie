# PR#35: The Footer Links into the Gitea Repository

> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR documentation is not maintained along with new PRs; treat it as a snapshot.

## Problem

The footer shows "Werkregie" and the version as plain text.
Werkator's footer links its version to its release notes;
in Werkregie the user finds neither the code nor the releases from the page.

## Non-Goals

- No release notes of its own, as Werkator has under `/releases`.
- No tags or Gitea releases; the release page stays as Gitea shows it.
- No setting for the repository URL; the build takes it from the checkout.

## Specification

### Feature: the footer links into the Gitea repository

#### Scenario#35.01: the name opens the repository

&hellip; so that the user gets to the code from the page.

- **Given** the dashboard, also as an installed app
- **When** the user clicks "Werkregie" in the footer
- **Then** https://git.javagil.de/mi/werkregie opens in a new tab of the browser

##### Verified by

- [WebServerTest "the footer links the repository and its releases"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt)
- Manual: see "Check in the Browser".

#### Scenario#35.02: the version opens the releases

&hellip; so that the version leads to what changed, as in Werkator.

- **Given** the dashboard with the version v0.16.0
- **When** the user clicks "v0.16.0" in the footer
- **Then** https://git.javagil.de/mi/werkregie/releases opens in a new tab of the browser

##### Verified by

- [WebServerTest "the footer links the repository and its releases"](../../dashboard/src/test/kotlin/de/hoennig/werkregie/dashboard/WebServerTest.kt)
- Manual: see "Check in the Browser".

#### Scenario#35.03: the build takes the repository from its checkout, without a token

&hellip; so that a fork or the GitHub mirror links itself, and no credential reaches the page.

- **Given** a checkout whose `origin` is `https://claude-code:secret@git.javagil.de/mi/werkregie.git`
- **When** Gradle builds the dashboard
- **Then** the footer links https://git.javagil.de/mi/werkregie

##### Verified by

- [build.gradle.kts, task `checkRepositoryWebUrl`](../../dashboard/build.gradle.kts), part of `check`:
  scp, ssh and https remotes, a token, a local path, an SSH host alias and a quote character.

### Check in the Browser

The dashboard ran in local mode in Claude's browser pane, built from this branch.
The footer showed "Werkregie v0.16.0 · © 2026 Michael Hönnig" as before, the version in the accent colour.
"Werkregie" pointed to https://git.javagil.de/mi/werkregie, "v0.16.0" to its `/releases`,
both with `target="_blank"` (#35.01, #35.02).

## Solution

- `index.html`: "Werkregie" and the version are links with `target="_blank"`;
  an installed app opens a page outside its scope in the browser.
- `build.gradle.kts`: `processResources` puts the repository into `index.html` in place of `@repositoryUrl@`.
  `repositoryWebUrl` takes `git remote get-url origin`: scp and ssh become https, user, token, port and `.git` go.
  Without git or with a remote that gives no web page, e.g. a local path, the fallback is https://git.javagil.de/mi/werkregie.
  The result must match `https://host/path` of letters, digits and `._~-`, so nothing else reaches the HTML.
- The link is in the page itself, not in `/api/dashboard`: it works before the first answer and in the kept app shell offline.
  The shell of `sw.js` keeps the same files.
- Version 0.16.0.

## Open Questions

- [x] Which element links where?
  Decided by mi on 2026-10-12: the name to the repository, the version to its releases.
- [x] Does the repository URL come from the configuration or the build?
  Decided by mi on 2026-10-12: the build takes it from `origin`, cleaned, with https://git.javagil.de/mi/werkregie as fallback.
