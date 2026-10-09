# Werkregie-Dashboard

A web app showing what the coding agents and the build pipeline are doing right now
and what happened recently — read-only, links back to the tools.

| Source | Data | Shown |
|---|---|---|
| Claude Code (Desktop and terminal) | `~/.config/Claude/claude-code-sessions/**.json`, `~/.claude/sessions/<pid>.json` | busy / waiting / closed sessions, resume command |
| Codex (ChatGPT Desktop, IDE, CLI) | `~/.codex/state_5.sqlite` (read-only), rollout files | threads, running turns, resume command |
| Werkator, any number of instances | `/api/repos`, `/api/repos/{repo}/builds/history` | running and finished builds; `@ai-review` builds as werkrevisor |
| Gitea, any number of instances | `/api/v1` | open PRs with commit statuses (Werkator, werkrevisor run and verdict), own activity |

The page follows Werkator's design (`werkregie.css` mirrors `werkator.css`: IBM Plex, petrol accent, light/dark with a toggle).

## Modes

One program, three ways to run it:

```
            ┌──────────── Linux machine with coding agents ─────────────┐
            │  Claude Code, Codex ──► werkregie-dashboard relay ──┐     │
            └─────────────────────────────────────────────────────┼─────┘
                                       HTTPS POST every 5 s,      │  outgoing only:
                                       bearer token               ▼  nothing reaches in
            ┌──────────── host, e.g. Hostsharing Managed Webspace ──────┐
  browser ─►│ Apache (.htaccess) ─► werkregie-dashboard server ◄── Werkator, Gitea
  (cookie)  └───────────────────────────────────────────────────────────┘
```

| Mode | Reads | Serves |
|---|---|---|
| `local` (default) | Claude Code, Codex, Werkator, Gitea | `127.0.0.1` only, no token |
| `server` | Werkator, Gitea, and whatever the relays report | real data only with the access token, invented demo data without |
| `relay` | Claude Code, Codex | nothing: posts its state to `relay.serverUrl` |

- **Access token:** the server creates it on its first start in `<stateDir>/access-token` (mode 0600);
  `werkregie-dashboard token` prints it, deleting the file rotates it.
  The browser enters it with the key button and keeps it as the `werkregie_token` cookie;
  relays send it as `Authorization: Bearer`. The relay endpoint does not accept the cookie,
  so no other site can make a logged-in browser post to it.
- **Silent relays:** after `server.relayStaleSeconds` (30) the relay's chip turns yellow with "zuletzt vor …";
  after `server.relayGoneSeconds` (120) its running and open sessions move to the history.
- **State:** the last report of every relay is kept in `<stateDir>/relays.json`, so a restarted server is filled at once.
  `stateDir` defaults to `${XDG_STATE_HOME:-~/.local/state}/werkregie`.

## Run locally

```bash
./gradlew installDist
build/install/werkregie-dashboard/bin/werkregie-dashboard   # → http://localhost:4747/
```

or `./gradlew run`. To start it with the session, see `packaging/werkregie-dashboard.service`.

## Server on a host (Hostsharing Managed Webspace)

The same shape as Werkator's: a runtime bundle with its own JRE, a systemd user service on the
port of the booked "eigener Serverdienst", and the platform's Apache as TLS-terminating proxy via `.htaccess`.

1. Build the bundle on a Linux x86_64 machine (the JRE is Temurin 21, built against an old glibc):
   ```bash
   ./gradlew runtimeBundle   # → build/distributions/werkregie-dashboard-runtime-linux-x64.tar.gz
   ```
2. On the host, unpack it to `~/opt` and write the configuration, see [dashboard.server.example.yml](dashboard.server.example.yml)
   (`port` is the assigned port, `server.publicBaseUrl` the domain).
3. Generate and install the unit and the `.htaccess`; the command prints the remaining steps:
   ```bash
   ~/opt/werkregie-dashboard/bin/werkregie-dashboard init server ~/werkregie-init
   ```
4. `~/opt/werkregie-dashboard/bin/werkregie-dashboard token` prints the access token for the browser and the relays.

To update: stop the service, unpack the new bundle over `~/opt/werkregie-dashboard`, start the service.

## Relay on a machine with coding agents

Configuration as in [dashboard.relay.example.yml](dashboard.relay.example.yml) — `relay.serverUrl` and the token,
best in a file of its own (`relay.tokenFile`, mode 0600). Then:

```bash
build/install/werkregie-dashboard/bin/werkregie-dashboard init relay ~/werkregie-init   # prints how to enable the unit
```

## Configuration

`~/.config/werkregie/dashboard.yml`, or `--config FILE`, or `WERKREGIE_DASHBOARD_CONFIG`.
See [dashboard.example.yml](dashboard.example.yml) for the local mode; without a file only Claude Code and Codex are shown.

- **Werkator:** private repositories are redacted for anonymous readers.
  `controlToken` (or `controlTokenEnv`) is sent as the `werkator_token` cookie, the way Werkator's own pages do;
  `tools/remote --env-file .env.<instance> werkator control-token` prints it.
- **Gitea:** a token with read scopes, as `token`, `tokenEnv` or `teaLogin` (a login of `~/.config/tea/config.yml`, by name or user).
  `pullRequests: mine` lists PRs created by, assigned to or awaiting review from the user, `all` every open PR the user can see.

Local sources refresh every 2–3 s, Werkator every 15 s, Gitea every 30 s;
cached data is served at once while a due source refreshes in the background.
