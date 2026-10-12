# Werkregie-Dashboard

A web app showing what the coding agents and the build pipeline are doing right now
and what happened recently — read-only, links back to the tools.

| Source | Data | Shown |
|---|---|---|
| Claude Code (Desktop and terminal) | `~/.config/Claude/claude-code-sessions/**.json`, `~/.claude/sessions/<pid>.json`, the end of `~/.claude/projects/*/<session>.jsonl` | busy / waiting / closed sessions, resume command, sessions waiting for the user |
| Codex (ChatGPT Desktop, IDE, CLI) | `~/.codex/state_5.sqlite` (read-only), rollout files | threads, running turns, resume command |
| Werkator, any number of instances | `/api/repos`, `/api/repos/{repo}/builds/history`, `/api/system`, `/api/system/history` | running and finished builds; `@ai-review` builds as werkrevisor; the host's load |
| werkrevisor's statistics service | `/api/stats` | review costs of 24 hours and 7 days |
| Gitea, any number of instances | `/api/v1` | open PRs with commit statuses (Werkator, werkrevisor run and verdict), own activity |
| Matrix, with the user's own account | client-server API through Trixnity (`de.connect2x.trixnity`) | direct messages, mentions in every room, every message of configured rooms |

The page follows Werkator's design (`werkregie.css` mirrors `werkator.css`: IBM Plex, petrol accent, light/dark with a toggle).
It opens on the overview, which answers two questions first: is every source connected, and where must the user act?

- The connection bar shows each tool as its logo with a status dot, and names a source that fails or is silent.
  On the desktop the Claude logo opens Claude Desktop's sessions waiting for the user (`claude://code/needs-input`),
  and a session of Claude Desktop opens there (`claude://code/continue?session=…`); a phone gets no such links.
  Codex has no such link yet: the ChatGPT app on Linux takes `codex://threads/…` but does not act on it.
- *Du bist dran* groups what asks something of the user: Festgefahren, Nacharbeiten, Freigeben, Mergen, Antworten.
  Antworten holds the Matrix messages to the user and the Claude Code sessions that wait for the user.
- Beside it: the load of each Werkator host, Werkator's builds of 24 hours, werkrevisor's costs.
  A Werkator panel charts 24 hours of its 3 most critical metrics,
  out of CPU, RAM, each disk, load, swap, CPU wait, memory wait and disk wait.
  Before its verdict it shows the running builds of its slots, e.g. `1/2`, coloured as on Werkator's tile "Builds".
- Below it: what runs and which sessions wait, as cards; the history of 24 hours as a lane per tool.

The tabs *Pull Requests*, *Sessions*, *Aufgaben*, *Nachrichten*, *Verlauf* and *Quellen* hold the full lists, with the filters;
the address keeps the tab, e.g. `#prs`.
On a phone the tabs are a bar at the bottom.
The page speaks German or English (DE/EN in the header); the browser keeps the choice.
Texts that the sources compose, e.g. Gitea activity titles, stay as the server writes them.
The logos in `static/logos/` are the tools' own; the black ones turn white in the dark mode.

The page installs as an app (PWA): `manifest.webmanifest`, icons and the service worker `sw.js` in `static/`.
The service worker keeps the page, styles and fonts for a start without network, fetching them anew first whenever it can;
it never touches `/api/`.
The page itself keeps the last state of `/api/dashboard` in the browser's `localStorage` (`werkregie-last`):
when the server does not answer, it shows that state under an amber banner with its age.
Demo data is never kept, and forgetting the access token deletes the state.
The PNG icons are rendered from the SVGs, e.g. `inkscape icon-maskable.svg -w 512 -h 512 -o icon-maskable-512.png`.

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
- **Journal:** every minute the server, or the local mode, records the work of each day in `<stateDir>/journal/YYYY-MM-DD.json`:
  the active time of each Claude Code session and Codex thread, and the items of all sources, sessions apart.
  A recorded span or item stays when its source forgets it; days older than `journal.keepDays` (400) are deleted.
  The relay reads the active time from the time stamps in the transcripts, `claude.projectsDir` and Codex' rollout files;
  entries apart by at most `journal.gapMinutes` (15) count as one span, a longer pause splits it.
  `journal.zone` (default the machine's zone) sets where a day begins.
  `GET /api/journal/YYYY-MM-DD` returns a day, on a server only with the access token.

## Run locally

```bash
./gradlew installDist
build/install/werkregie-dashboard/bin/werkregie-dashboard   # → http://localhost:4747/
```

or `./gradlew run`. To start it with the session, see `packaging/werkregie-dashboard.service`.

## Server on a host (Hostsharing Managed Webspace)

The same shape as Werkator's: a runtime bundle with its own JRE, a systemd user service on the
port of the booked "eigener Serverdienst", and the platform's Apache as TLS-terminating proxy via `.htaccess`.

1. Build the bundle on a Linux x86_64 machine (the JRE is Temurin 25, built against an old glibc; werkregie ADR 0001):
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
3. Verify the device with the recovery key, formerly "Security Key", or the security phrase;
   this also unlocks the key backup, so that older encrypted messages become readable:
   ```bash
   werkregie-dashboard matrix verify
   ```
4. Start the relay or the local mode again; it decrypts the rows shown as "verschlüsselt" once more.

The session and the keys live in `<stateDir>/matrix/` (mode 0700):
an H2 database `client.mv.db` and the shown rows in `messages.json`.
To sign in anew, delete that directory and remove the device "Werkregie <host>" in Element.
A relay reports only the start of each text, at most 80 characters, to the server.
One process per machine runs the client, the one that holds `sync.lock`:
a relay and the local mode share the state directory, and two clients with the same keys would spoil each other's sessions.
The other process shows the same messages from `messages.json` and takes over when the first one ends.
Until the device has the keys, an encrypted message shows as "verschlüsselt", with room, sender and time.
Each direct chat and configured room keeps its last message in the panel, however old.
A message the user has not read yet is marked "Neu"; read ones are subdued.
Read means up to the later of the user's read receipts and Element's read marker `m.fully_read`,
since an Element without read receipts only moves its marker.
A click opens the message by `matrix.link`: `matrix.to` (default) in the browser,
`io.element.desktop` in Element Desktop, `element` in its nightly build; kept messages take the setting, too.
On a phone the page links to matrix.to, which the Element app takes over:
unlike Android, a Linux desktop has no app that takes `https://matrix.to` from the browser.

## Configuration

`~/.config/werkregie/dashboard.yml`, or `--config FILE`, or `WERKREGIE_DASHBOARD_CONFIG`.
See [dashboard.example.yml](dashboard.example.yml) for the local mode; without a file only Claude Code and Codex are shown.

- **Claude Code:** a session that is not busy is marked 💬 *Claude fragt* when it waits for the user:
  *Frage offen* or *Dialog offen* by its process file, *Frage offen* by an unanswered AskUserQuestion in its transcript,
  *wartet auf dich* when Jev rates Claude's last text at least `claude.jev.threshold` (default 0.75).
  Only with `claude.jev` does the last text, at most 3000 characters, go to Jev, once per message;
  its key comes from `apiKey` or `apiKeyEnv`, e.g. `TYPESAFE_API_KEY` in the relay's `dashboard.env`.
  An archived session carries no marker.
- **Werkator:** private repositories are redacted for anonymous readers.
  `controlToken` (or `controlTokenEnv`) is sent as the `werkator_token` cookie, the way Werkator's own pages do;
  `tools/remote --env-file .env.<instance> werkator control-token` prints it.
- **werkrevisor:** `werkrevisors` with `name` and `url` of the statistics service; its costs are read anonymously.
- **Gitea:** a token with read scopes, as `token`, `tokenEnv` or `teaLogin` (a login of `~/.config/tea/config.yml`, by name or user).
  `pullRequests: mine` lists PRs created by, assigned to or awaiting review from the user, `all` every open PR the user can see.
  Each PR is marked by what it asks of whom; the panel sorts by these markers, in this order:
  ⚠ *Festgefahren* (werkrevisor escalated, RED for an hour without a new commit, ERROR, or a run without verdict),
  ✋ *Dein Approval* (a foreign PR with a final ORANGE, then YELLOW, then without werkrevisor 2 min after its last change,
  or one whose review is requested from the user, also while werkrevisor runs and after an approval of the user),
  ➜ *Mergen* (the user's own PR approved by another human, or a foreign PR approved by anyone but its author,
  with all checks green and no automerge scheduled), 🔧 *Nacharbeit* (conflict or red build on the user's own PR),
  ⏳ *Wartet auf Review* (the user's own PR waits for another human), ✓ *Von dir approved*.
  Unmarked PRs follow, drafts come last; drafts carry no marker.
  Within each group the PR that waits longest comes first.
  `reviewBots` (default `[ai-reviewer]`) names accounts whose approval is no human's.

- **Taiga:** `taskboards` with `type: taiga` list the open user stories, tasks and issues assigned to the user
  in the configured `projects`, in the panel *Aufgaben*.
  There is no default instance or project.
  The API address comes from the field `api` of `<url>/conf.json`, as for Taiga's own web app, else `<url>/api/v1`;
  `apiUrl` overrides it.
  Werkregie logs in with the user and the password of the variable `passwordEnv` and logs in again when the token expires;
  on a server the variable stands in `dashboard.env`, in the local mode it has to be in the environment.
  `statuses` names the most important statuses first, within one the latest change first;
  a list in one place holds equal names of different projects, e.g. `[In Arbeit, In Progress]`.
  An open status named nowhere comes first, marked *Status unbekannt*, so that it gets added.
  `hiddenStatuses`, e.g. `[Erledigt, Verworfen]`, are hidden until the panel's count shows them.
  Each status carries its colour from Taiga.

Local sources refresh every 2–3 s, Werkator every 30 s (three repositories at a time, its lines every 5 min),
Gitea every 30 s, Taiga every 60 s, werkrevisor every 2 min;
cached data is served at once while a due source refreshes in the background.
