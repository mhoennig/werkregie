package de.hoennig.werkregie.dashboard

import java.time.Instant

enum class Section { RUNNING, OPEN, RECENT }

/** A status chip, e.g. a commit status on a pull request. */
data class Badge(
    val label: String,
    val state: String,
    val url: String? = null,
    val title: String? = null,
)

/** One row of the dashboard, whatever source it comes from. */
data class Item(
    val source: String, // claude | codex | werkator | werkrevisor | gitea
    val origin: String, // instance or client, e.g. "javagil", "Desktop"
    val kind: String, // session | build | review | pr | activity
    val section: Section,
    val title: String,
    val state: String,
    val time: Instant?,
    val project: String? = null,
    val detail: String? = null,
    val url: String? = null,
    val command: String? = null,
    val badges: List<Badge> = emptyList(),
    // the repository the project is, where the shown name does not say it, e.g. a folder `hsadmin-ng`
    // checked out from `hs.hsadmin.ng`; the page matches projects by repository name without owner
    val repository: String? = null,
    // what this asks of whom, most urgent first, e.g. a pull request waiting for the user's approval
    val flags: List<Flag> = emptyList(),
    // the order within its panel where the source knows it, lower first, e.g. a task by its configured status
    val rank: Int? = null,
)

/**
 * A marker on a pull request, by urgency: `stuck` (agent and werkrevisor do not get on), `rework`
 * (the user's own PR has a conflict or a red build), `approve` (waits for the user's approval),
 * `merge` (the user's own PR is approved), `review` (the user's own PR waits for another human),
 * `approved` (the user approved it, the merge is still to come).
 */
data class Flag(val kind: String, val reason: String? = null)

data class SourceStatus(
    val id: String,
    val label: String,
    val ok: Boolean,
    val message: String?,
    val url: String?,
    val count: Int,
    val fetchedAt: Instant?,
    // the tools a relay reports, e.g. ["claude", "codex"]; empty for a source that is one tool
    val tools: List<String> = emptyList(),
)

/** Who sees what: `open` in local mode, `granted` with the access token, `demo` without it. */
enum class Access { OPEN, GRANTED, DEMO }

data class Snapshot(
    val version: String,
    val generatedAt: Instant,
    val windowHours: Long,
    val items: List<Item>,
    val sources: List<SourceStatus>,
    val access: Access = Access.OPEN,
)

/** What a relay sends to the server: the full current state of its local sources, every few seconds. */
data class RelayReport(
    val relay: String,
    val version: String,
    val sentAt: Instant,
    val items: List<Item>,
    val sources: List<SourceStatus>,
)
