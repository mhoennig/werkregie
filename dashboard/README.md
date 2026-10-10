# Werkregie-Dashboard

A web app showing what the coding agents and the build pipeline are doing right now
and what happened recently — read-only, links back to the tools.

| Source | Data | Shown |
|---|---|---|
| Claude Code (Desktop and terminal) | `~/.config/Claude/claude-code-sessions/**.json`, `~/.claude/sessions/<pid>.json` | busy / waiting / closed sessions, resume command |
| Codex (ChatGPT Desktop, IDE, CLI) | `~/.codex/state_5.sqlite` (read-only), rollout files | threads, running turns, resume command |
| Werkator, any number of instances | `/api/repos`, `/api/repos/{repo}/builds/history` | running and finished builds; `@ai-review` builds as werkrevisor |
| Gitea, any number of instances | `/api/v1` | open PRs with commit statuses (Werkator, werkrevisor run and verdict), own activity |
| Matrix, with the user's own account | client-server API through Trixnity (`de.connect2x.trixnity`) | direct messages, mentions in every room, every message of configured rooms |

The page follows Werkator's design (`werkregie.css` mirrors `werkator.css`: IBM Plex, petrol accent, light/dark with a toggle).

The page installs as an app (PWA): `manifest.webmanifest`, icons and the service worker `sw.js` in `static/`.
The service worker keeps the page, styles and fonts for a start without network, fetching them anew first whenever it can;
it never touches `/api/`, so no data outlives the page. The PNG icons are rendered from the SVGs, e.g.
`inkscape icon-maskable.svg -w 512 -h 512 -o icon-maskable-512.png`.

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
- **Retired relays:** a relay that reports no more stays as stale until it is forgotten:
  `werkregie-dashboard forget-relay NAME` on the server host asks the running server (with the token from its state),
  or edits `relays.json` when no server runs. A relay still running simply reports again.
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

## Matrix

Werkregie reads Matrix as a device of the user's own account,
so that end-to-end encrypted rooms are readable once the device has their keys.
It runs in the modes `local` and `relay`, never on the server: the device keys stay on the user's machine.

1. Configure `matrix.homeserver`; there is no default.
2. Stop the relay or the local mode, then sign in; it asks for user and password:
   ```bash
   werkregie-dashboard matrix login
   ```
3. Start the relay or the local mode again.

The session and the keys live in `<stateDir>/matrix/` (mode 0700):
an H2 database `client.mv.db` and the shown rows in `messages.json`.
To sign in anew, delete that directory and remove the device "Werkregie <host>" in Element.
A relay reports only the start of each text, at most 80 characters, to the server.
Until the device is verified and has the keys, an encrypted message shows as "verschlüsselt", with room, sender and time.

## Configuration

`~/.config/werkregie/dashboard.yml`, or `--config FILE`, or `WERKREGIE_DASHBOARD_CONFIG`.
See [dashboard.example.yml](dashboard.example.yml) for the local mode; without a file only Claude Code and Codex are shown.

- **Werkator:** private repositories are redacted for anonymous readers.
  `controlToken` (or `controlTokenEnv`) is sent as the `werkator_token` cookie, the way Werkator's own pages do;
  `tools/remote --env-file .env.<instance> werkator control-token` prints it.
- **Gitea:** a token with read scopes, as `token`, `tokenEnv` or `teaLogin` (a login of `~/.config/tea/config.yml`, by name or user).
  `pullRequests: mine` lists PRs created by, assigned to or awaiting review from the user, `all` every open PR the user can see.
  Each PR is marked by what it asks of whom, most urgent first:
  ⚠ *Festgefahren* (werkrevisor escalated, RED for an hour without a new commit, ERROR, or a run without verdict),
  🔧 *Nacharbeit* (conflict or red build on the user's own PR), ✋ *Dein Approval* (a foreign PR without werkrevisor
  or with a final YELLOW/ORANGE), ➜ *Mergen* (the user's own PR approved by another human),
  ⏳ *Wartet auf Review* (the user's own PR waits for another human), ✓ *Von dir approved*. Drafts carry no marker.
  `reviewBots` (default `[ai-reviewer]`) names accounts whose approval is no human's.

Local sources refresh every 2–3 s, Werkator every 30 s (three repositories at a time), Gitea every 30 s;
cached data is served at once while a due source refreshes in the background.
