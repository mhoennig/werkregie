# Pull-Request Documentation in doc/prs

Every pull request (PR) of werkregie has one document in this directory.
It documents the change of that PR at that time; later PRs do not maintain it.

## Workflow

The steps around a PR document are in [docs/agentic-workflow.md](../agentic-workflow.md), "Steps of a PR".

## Naming Convention

`YYYY-MM-DD-PR#999-short-description-of-pr.md`

1. Date of the PR in the format `YYYY-MM-DD`,
2. `-PR#` and the number of the pull request in Gitea,
3. a short description with dashes between the words,
4. `.md`.

## Guidelines

- Markdown, in English.
- One sentence per line, to keep diffs readable.
- Explain the why; keep it short.

## Open Questions Block the Review

werkrevisor checks the section "Open Questions" of every PR document a PR adds or changes.
An item with `[ ]`, or an item or paragraph without a checkbox, makes the review RED, before any model call.
Mark an answered question with `[x]`; put the answer on an indented continuation line of the item.
A paragraph or item that says only "None." (or "Keine.") states that nothing is open and does not count.
The whole item counts as checked, not its source lines.

## Structure

The main (`##`) sections appear in this order; omit the ones that do not apply:

0. Related Links (optional)
1. Problem (required)
2. Non-Goals (required)
3. Specification (required for features): Gherkin features with their scenarios
4. Solution (required)
5. Open Questions (optional)
6. Additional Changes (optional)
7. Prerequisite PRs (optional)
8. Follow-up PRs (optional)
9. Attachments (optional)

For details, see the [template](TEMPLATE.md).
A PR has preferably one Gherkin feature, at most 5 features and 10 scenarios in all; a larger PR gets split.
