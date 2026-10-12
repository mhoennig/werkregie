package de.hoennig.werkregie.dashboard

import de.hoennig.werkregie.dashboard.journal.Activity
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
    // the source's own colour of the state, a CSS hex colour such as #rgb or #rrggbb, e.g. a Taiga status
    val color: String? = null,
)

/**
 * A marker on a pull request, by urgency: `stuck` (the agent does not get on with werkrevisor or `main`), `rework`
 * (the user's own PR has a conflict or a red build), `approve` (waits for the user's approval),
 * `merge` (the user's own PR is approved), `review` (the user's own PR waits for another human),
 * `approved` (the user approved it, the merge is still to come).
 */
data class Flag(
    val kind: String,
    val reason: String? = null,
    // for `ask`: the user's last input to the session and what Claude asks, each short enough for a tooltip
    val prompt: String? = null,
    val question: String? = null,
)

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
    // the tools a relay reads but fails to, e.g. ["codex"]
    val failingTools: List<String> = emptyList(),
    // a relay that has not reported for a while; its tools may no longer be current
    val silent: Boolean = false,
)

/** Who sees what: `open` in local mode, `granted` with the access token, `demo` without it. */
enum class Access { OPEN, GRANTED, DEMO }

/** The load of a build host, e.g. a Werkator instance: its own verdict, and small charts of its most critical metrics. */
data class HostLoad(
    val source: String,
    val label: String,
    val url: String?,
    // healthy, pressure or overloaded, as Werkator judges it
    val level: String,
    val reasons: List<String>,
    val charts: List<LoadChart> = emptyList(),
    val at: Instant?,
    // the running builds of its slots; ok, warn with one slot free, bad with none
    val builds: Int? = null,
    val buildSlots: Int? = null,
    val buildsLevel: String? = null,
)

/**
 * One metric of a [HostLoad], e.g. `cpu`, `load` or `cpuPressure`: [value] is the share of its total from 0 to 1,
 * except for `load`, which is the load average; [level] is ok, warn or bad.
 * [history] holds the last 24 hours in equal steps, oldest first, from 0 to 1 of the chart's height.
 */
data class LoadChart(
    val key: String,
    // only a disk has a name of its own, e.g. SSD; the page names the others
    val label: String? = null,
    val value: Double?,
    val level: String,
    val history: List<Double?> = emptyList(),
)

/** What the reviews cost in US dollars, e.g. werkrevisor's; [perDay] holds the last 7 days, oldest first. */
data class Costs(
    val source: String,
    val label: String,
    val url: String?,
    val day: Double?,
    val week: Double?,
    val reviews: Int?,
    val pullRequests: Int?,
    val perDay: List<Double> = emptyList(),
)

data class Snapshot(
    val version: String,
    val generatedAt: Instant,
    val windowHours: Long,
    val items: List<Item>,
    val sources: List<SourceStatus>,
    val access: Access = Access.OPEN,
    val loads: List<HostLoad> = emptyList(),
    val costs: List<Costs> = emptyList(),
)

/** What a relay sends to the server: the full current state of its local sources, every few seconds. */
data class RelayReport(
    val relay: String,
    val version: String,
    val sentAt: Instant,
    val items: List<Item>,
    val sources: List<SourceStatus>,
    // the active time of the local sessions within the window; a relay older than 0.22.0 sends none
    val activity: List<Activity> = emptyList(),
)
