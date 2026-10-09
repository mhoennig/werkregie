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
)

data class Snapshot(
    val generatedAt: Instant,
    val windowHours: Long,
    val items: List<Item>,
    val sources: List<SourceStatus>,
)
