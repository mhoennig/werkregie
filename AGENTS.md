# werkregie Agent Instructions

## Project

werkregie orchestrates software development with AI agents.
So far it is the dashboard in `dashboard/`, a Kotlin web app that shows what the coding agents
and the build pipeline are doing right now; see [dashboard/README.md](dashboard/README.md).
[doc/system-architektur.md](doc/system-architektur.md) describes the system,
[doc/werkregie.werkbaum](doc/werkregie.werkbaum) holds the plan.

## Build and Test

Run from `dashboard/`:

```sh
./gradlew build         # compile and test
./gradlew installDist   # build/install/werkregie-dashboard/bin/werkregie-dashboard → http://localhost:4747/
```

## Workflow

Follow [doc/agentic-workflow.md](doc/agentic-workflow.md), the workflow of all Werk repositories:
roles, steps of a PR, review rounds, PR documents, the plan, versions and the writing style.
In addition, for werkregie:

- Its documents live in `doc/`, not `docs/`: `doc/agentic-workflow.md`, `doc/prs/`, `doc/werkregie.werkbaum`.
- werkregie holds the master copy of the shared workflow.
- The version stands in `dashboard/build.gradle.kts`.
- GitHub `mhoennig/werkregie` is only a mirror of `main`; branches go to Gitea only.
