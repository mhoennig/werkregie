# Werkregie-Dashboard

A local web app showing what the coding agents and the build pipeline are doing right now
and what happened recently — read-only, links back to the tools.

| Source | Data | Shown |
|---|---|---|
| Claude Code (Desktop and terminal) | `~/.config/Claude/claude-code-sessions/**.json`, `~/.claude/sessions/<pid>.json` | busy / waiting / closed sessions, resume command |
| Codex (ChatGPT Desktop, IDE, CLI) | `~/.codex/state_5.sqlite` (read-only), rollout files | threads, running turns, resume command |
| Werkator, any number of instances | `/api/repos`, `/api/repos/{repo}/builds/history` | running and finished builds; `@ai-review` builds as werkrevisor |
| Gitea, any number of instances | `/api/v1` | open PRs with commit statuses (Werkator, werkrevisor run and verdict), own activity |

The page follows Werkator's design (`werkregie.css` mirrors `werkator.css`: IBM Plex, petrol accent, light/dark with a toggle).
The server binds to `127.0.0.1` only; Gitea tokens and Werkator control tokens stay in the backend.

## Run

```bash
./gradlew installDist
build/install/werkregie-dashboard/bin/werkregie-dashboard   # → http://localhost:4747/
```

or `./gradlew run`. To start it with the session, see `packaging/werkregie-dashboard.service`.

## Configuration

`~/.config/werkregie/dashboard.yml`, or a path as first argument or in `WERKREGIE_DASHBOARD_CONFIG`.
See [dashboard.example.yml](dashboard.example.yml); without a file only Claude Code and Codex are shown.

- **Werkator:** private repositories are redacted for anonymous readers.
  `controlToken` (or `controlTokenEnv`) is sent as the `werkator_token` cookie, the way Werkator's own pages do;
  `tools/remote --env-file .env.<instance> werkator control-token` prints it.
- **Gitea:** a token with read scopes, as `token`, `tokenEnv` or `teaLogin` (reuses a login of `~/.config/tea/config.yml`).
  `pullRequests: mine` lists PRs created by, assigned to or awaiting review from the user, `all` every open PR the user can see.

Local sources refresh every 2–3 s, Werkator every 15 s, Gitea every 30 s;
cached data is served at once while a due source refreshes in the background.
