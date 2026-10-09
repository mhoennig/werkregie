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
