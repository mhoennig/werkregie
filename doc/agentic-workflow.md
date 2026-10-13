# Agentic Workflow

This document is the same in all Werk repositories; each one keeps it as `docs/agentic-workflow.md`.
The `AGENTS.md` of a repository refers to it and holds only what differs or adds:
build and test commands, architecture, hard invariants, and exceptions to this document.
werkregie holds the master copy, as `doc/agentic-workflow.md`.
A change goes into the master copy first, then into each repository with its next PR.

## Goal

The coding agent writes the change, the review agent reviews every PR,
and the two settle the findings between them within the guardrails.
An uncritical PR merges without a human.
A critical PR, e.g. one that touches security or weakens the checks, waits for the human.

## Roles and Accounts

- **Coding agent**, usually Claude Code: works as the Gitea account `claude-code`, never as the human.
  It commits as `claude-code <claude-code@javagil.de>`, with `user.name` and `user.email` set in the worktree.
- **Review agent**, usually werkrevisor: works as the Gitea account `ai-reviewer`.
  It never approves a PR that it opened itself.
- **Human** (`mi`): resolves a review that the two agents could not settle within the guardrails.
  The human is never the author of an agent's PR.
- The coding agent reads its token `GITEA_CLAUDE_CODE_TOKEN` from `~/.config/ai-coding-agents/secrets.env`.
  A token never appears in an output, a commit or a file of the repository.
- Only the human grants rights, e.g. collaborator access or branch protection rules.
- The working state is Gitea (git.javagil.de).
  A GitHub repository is at most a mirror of `main`; the human decides and sets it up per repository.
- If another agent works in the same checkout, the coding agent uses a worktree of its own.

## Steps of a PR

1. Create the branch `claude-code/<topic>` from the current `main`.
2. The first commit is the PR document (see below); push the branch at once.
3. Open the PR at once, with the title prefix `WIP: `; the prefix keeps the review agent away.
   Once the PR number is known, the file name of the PR document gets it.
4. Implement in focused commits and push each one.
   Start long local test runs only after the push; the CI runs meanwhile.
   After each push, watch the PR until it needs the agent (see "Watching a PR").
5. Keep the PR document current: decisions, findings, open points.
6. Remove `WIP: ` only when everything is done:
   the CI is green, tests cover the change, and the PR document matches the code.
   Removing it starts the review: `PATCH /api/v1/repos/<owner>/<repo>/pulls/<n>` with the new `title`.
7. Answer every review round (see "Review Rounds").
8. After the merge, deploy where the repository has a deployment, and check that it works.

A PR opened without `WIP: ` gets no review until it was set to `WIP: ` and back.

`main` takes squash merges only.
After the merge of a PR, a branch that builds on it gets rebased onto `main` without the already merged commits:
`git rebase --onto main <last commit of the merged branch>`.

## Watching a PR

From its opening to its merge, the PR is the coding agent's to keep mergeable.

- After each push, the agent watches its PR until it must act: a merge, a conflict or a new `main`, a red build,
  a green build of a `WIP: ` PR, or a review that asks for work.
  werkregie provides the watch: `werkregie-dashboard watch-pr <URL of the PR>` prints one line and ends.
- A conflict with `main` is the agent's to fix at once, without waiting for the human:
  rebase onto `main`, raise the version to the next free one, push.
- The next free version is above the one of `main` and above those of the open PRs.
  When another PR merges first, the agent rebases and raises it again.
- The watch ends with the agent's session; then "Du bist dran" in werkregie shows the human a conflict that the agent leaves.

## Review Rounds

- Before each push, check that the PR is still open and not merged.
  An automerge can merge in the middle of a round; a later push then lands on a dead branch.
- Reply in each line-comment thread: "fixed in `<commit>`", or why not.
- Resolve each thread that is fixed or answered.
- Fix the findings in the same PR, also each YELLOW reason that can be fixed, e.g. a scenario that is hard to read.
  Only structural reasons, e.g. a risky path, are left to the human.
- A wrong finding gets a reply with evidence, no change.
- A security fix needs a red/green proof: the test fails without the fix and passes with it.

The verdicts of werkrevisor:

| Verdict | Meaning                                                         | Way to the merge                                |
|---------|-----------------------------------------------------------------|-------------------------------------------------|
| GREEN   | nothing risky found                                             | automerge; open hints hold it until resolved    |
| YELLOW  | issues or risks found by Jev or the rules                       | approval of the human                           |
| ORANGE  | findings that Jev rates as probably real and severe             | approval of the human after checking them       |
| RED     | a rule is violated, or a risk and a finding meet in one place   | a new commit that fixes the cause               |
| ERROR   | the review did not complete                                     | the human checks the cause                      |

## For the Human

- Approve only after the result comment of the review agent for the current head exists.
  An earlier approval does not count; the review agent then requests the review again.
- After a rebase or a merge of `main`, an approval stays valid when the changed blocks stayed the same.
  Jev judges new blocks first; only risky ones go back to the human.
- A PR in review is frozen: the coding agent does not change it until the human has decided.

## The PR Document

Each PR has exactly one document under `docs/prs/`, named `YYYY-MM-DD-PR#<n>-<topic>.md`.
A pure release PR (version and release notes, no code) needs none.
The document describes the PR at its time; later PRs do not maintain it.
`docs/prs/README.md` and the template next to it hold its structure.

The README is the only source of the sections and their order;
the core ones are Problem, Non-Goals, Specification, Solution and Open Questions.

Rules for Gherkin features:

- A Gherkin feature holds one or more scenarios, each `Scenario#<n>.<nn>` with one aspect
  and a purpose line `&hellip; so that …`, in lower case after the ellipsis.
- **Given** names the state, **When** exactly one action, **Then** the observable result.
- A scenario describes behaviour, not its implementation.
- "Verified by" links the tests that check the scenario.
- Prefer one feature per PR; at most 5 features and 10 scenarios in all.
  A PR that needs more gets split.

Rules for open questions:

- An item under "Open Questions" without `[x]` makes the review RED.
- An answered question gets `[x]` and its answer on an indented continuation line.
- Deferred work is a `- [ ] TODO.<topic>: …`, which is only a hint.
- Planned follow-up work is listed under "Follow-up PRs".

## The Plan in Werkbaum

Each repository keeps its plan as a Werkbaum file (https://werkbaum.javagil.de), e.g. `docs/<repo>.werkbaum`.
Each PR updates it: the status of its nodes, new plans, and dropped approaches with their reason.

| Status | Meaning                    |
|--------|----------------------------|
| `[^]`  | deployed and in use        |
| `[x]`  | merged, not yet deployed   |
| `[~]`  | in work                    |
| `[ ]`  | planned                    |
| `[?]`  | idea                       |
| `[-]`  | dropped                    |

- A PR sets its nodes to `[x]`; if the deployment follows the merge at once, it sets `[^]`.
- Titles stay short; an explanation goes into a `"` line (at most 2 lines)
  or into a block behind `---`, addressed by the `#id` of the node.
- A node with children has no size.
- The file parses without warnings.

Outlook, with concurrent writes still open: an MCP server for Werkbaum keeps a live plan and promotes the nodes of a release.

## Versions, Release Notes, Decisions

- Each PR raises the version: patch for a fix, minor for new behaviour.
  The version identifies what runs; no change reaches `main` without one of its own.
- Only a change that users notice gets release notes;
  where a repository needs an entry per version, any other change gets one marked as internal.
- Decisions are only added, never deleted, e.g. as an ADR or in `docs/DECISIONS.md`.
  A deviation gets a new entry with its reason.
- A new runtime dependency needs the consent of the human.
- A new technology, e.g. another language or runtime, needs the explicit consent of the human:
  as a decision of its own, with the alternatives from the technologies in use.

## Scope of a PR

- A PR delivers one thing completely: code, tests, documentation, plan, version.
- Avoid unnecessary mini PRs and stacked PRs; they cause merge chaos.
- A by-catch stays in the PR and is listed under "Additional Changes".
- A finding is fixed in the PR that caused it; only an incomplete new feature continues in a follow-up PR.
- A refactoring that prepares the existing code for a feature goes first, as a prerequisite PR.
- A refactoring that the feature makes useful afterwards, e.g. a move or a rename, goes into a follow-up PR.
- Such a split may also come up while the feature PR is in work.
- A PR that changes the steps or decisions of a workflow updates its diagrams.

## Review Rules of the Repository

`.werkrevisor.toml` holds the repository part of the review configuration, e.g. the risk template and its categories.
werkrevisor reads it at the head of `main`, never from the PR.
A PR that changes it always waits for the approval of the human.

## Code and Comments

- A comment explains why, never what or how; when in doubt, no comment.
- New code follows the surrounding code: naming, comment density, idiom.

## Writing Style

These rules apply to documentation, PR documents, commit messages and replies in Gitea:

- Be terse; omit obvious details, implementation narration, and summaries.
- Remove all mannered prose; use literal language.
- Use ASD-STE100 as guidance: simple words, active voice, consistent terminology.
- Preserve code identifiers and established technical terms.
- Write at most one sentence per line.
- Wrap sentences longer than 128 characters at sensible points,
  preferably after commas, colons or semicolons, or before "and" or "or".
- Paragraphs comprise at most 5 sentences; add blank lines between paragraphs.
- Format enumerations as such instead of clunky lists.
- Each repository states the language of its documents; German documents avoid anglicisms in the prose.

## Working with the Human

- The coding agent asks one question at a time, as multiple choice, with the recommended answer first.
- After each settled topic it proposes the next step, also as multiple choice.
- It reports outcomes faithfully: failed tests with their output, skipped steps as skipped.
- It gives instructions for manual steps one step at a time and waits for feedback after each.

## Equipment of a Repository

| File                                         | Content                                                        |
|----------------------------------------------|----------------------------------------------------------------|
| `AGENTS.md`                                  | reference to this document; build, architecture, invariants    |
| `CLAUDE.md`                                  | `@AGENTS.md`, `@docs/agentic-workflow.md`, Claude Code rules   |
| `docs/agentic-workflow.md`                   | this document                                                  |
| `docs/prs/README.md` and a template          | structure and template of the PR documents                     |
| `docs/<repo>.werkbaum`                       | the plan                                                       |
| `.werkrevisor.toml`                          | repository part of the review configuration                    |

Optional, where it helps the repository:

- Skills under `.claude/skills/`: detailed guides that an agent loads when needed.
- Release notes or a `CHANGELOG.md` for users.
- A map from acceptance IDs to the tests that verify them.
