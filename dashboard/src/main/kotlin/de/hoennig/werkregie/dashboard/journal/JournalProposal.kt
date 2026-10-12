package de.hoennig.werkregie.dashboard.journal

import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JournalConfig
import de.hoennig.werkregie.dashboard.JournalProject
import de.hoennig.werkregie.dashboard.expandHome
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What an entry rests on, e.g. a session, a merge or a build, for the user to judge the proposal. */
data class Evidence(val kind: String, val title: String, val url: String? = null, val time: Instant? = null)

/**
 * One proposed entry: a Taiga story of a project, or a project without story.
 * An entry of 0 hours, or one without a Werkjournal project, is only a hint and stays out of the file.
 */
data class ProposedEntry(
    val id: String,
    val customer: String,
    val project: String,
    val customerId: String,
    val projectId: String,
    // false when neither a project nor journal.fallback takes the work
    val mapped: Boolean,
    val story: Story?,
    val minutes: Long,
    val hours: Int,
    val description: String,
    val evidence: List<Evidence>,
)

data class Proposal(val day: LocalDate, val entries: List<ProposedEntry>, val file: WerkjournalFile)

/** Werkjournal's import file, schema version 1, with only the fields it allows. */
data class WerkjournalFile(
    val schemaVersion: Int = 1,
    val customers: List<WerkjournalCustomer>,
    val projects: List<WerkjournalProject>,
    val workEntries: List<WerkjournalEntry>,
)

data class WerkjournalCustomer(val id: String, val name: String)

data class WerkjournalProject(val id: String, val customerId: String, val name: String)

data class WerkjournalEntry(
    val id: String,
    val date: LocalDate,
    val customerId: String,
    val projectId: String,
    val hours: Int,
    val description: String,
)

/**
 * Proposes the Werkjournal entries of a recorded day: one per Taiga story where a Taiga ID is known, else one per project.
 * The minutes are the union of the sessions' active spans and of [ACTION] before each action in Gitea, Taiga and Matrix;
 * builds, reviews and pull requests are evidence only, since they run without the user.
 * The same day gives the same proposal: ids derive from names, the description from sorted evidence,
 * because Werkjournal matches an entry by its content and adds a changed one as a new one.
 */
class JournalProposer(private val config: JournalConfig, private val stories: TaigaStories) {
    private data class Target(val customer: String, val project: String, val settings: JournalProject?, val mapped: Boolean)

    private class Group(val target: Target, val story: Story?) {
        val spans = mutableListOf<Span>()
        val evidence = mutableListOf<Evidence>()
    }

    private val projects = config.customers.flatMap { customer -> customer.projects.map { Target(customer.name, it.name, it, true) } }

    fun propose(day: JournalDay): Proposal {
        val groups = linkedMapOf<Pair<Target, Story?>, Group>()
        fun groupOf(target: Target, texts: List<String?>, taigaSlug: String? = null, ref: Long? = null): Group {
            val story = storyOf(target, texts, taigaSlug, ref)
            return groups.getOrPut(target to story) { Group(target, story) }
        }

        for (session in day.sessions) {
            val target = targetOf(repository = session.repository, cwd = session.cwd)
            // a branch names the task more often than a title; the newest branch counts first
            val group = groupOf(target, listOf(session.title) + session.branches.reversed())
            group.spans += session.spans
            group.evidence += Evidence("session", session.title ?: session.project ?: session.session, null, session.spans.firstOrNull()?.from)
        }
        for (item in day.items) {
            val time = item.time ?: continue
            when (item.source) {
                "gitea" -> {
                    val group = groupOf(targetOf(repository = item.project?.substringAfterLast('/')), listOf(item.title, item.detail, item.url))
                    if (item.kind == "activity") group.spans += action(time)
                    // an open pull request carries its number only in its link
                    val number = item.url?.substringAfterLast("/pulls/", "")?.toLongOrNull()?.takeIf { item.kind == "pr" }
                    group.evidence += Evidence(item.kind, number?.let { "PR #$it ${item.title}" } ?: item.title, item.url, time)
                }
                "werkator", "werkrevisor" ->
                    groupOf(targetOf(repository = item.project), listOf(item.title, item.detail)).evidence += Evidence(item.kind, item.title, item.url, time)
                "taiga" -> {
                    val target = targetOf(taiga = item.project)
                    val ref = item.detail?.substringAfter('#', "")?.toLongOrNull()
                    val group = groupOf(target, emptyList(), item.project, ref)
                    group.spans += action(time)
                    group.evidence += Evidence(item.kind, listOfNotNull(item.detail, item.title).joinToString(" "), item.url, time)
                }
                "matrix" -> {
                    val group = groupOf(targetOf(room = item), emptyList())
                    group.spans += action(time)
                    group.evidence += Evidence("message", listOfNotNull(item.detail, item.title).joinToString(": "), item.url, time)
                }
            }
        }

        val proposed = groups.values.map { entry(day.day, it) }
        val entries = withinDailyLimit(proposed)
            .sortedWith(compareBy({ !it.mapped }, { it.hours == 0 }, { it.customer }, { it.project }, { it.story?.ref ?: 0 }))
        return Proposal(day.day, entries, file(day.day, entries))
    }

    private fun entry(day: LocalDate, group: Group): ProposedEntry {
        val minutes = Spans.merge(group.spans).sumOf { Duration.between(it.from, it.to).toMinutes() }
        val target = group.target
        val evidence = group.evidence.distinct().sortedWith(compareBy({ it.time }, { it.kind }, { it.title }))
        return ProposedEntry(
            id = uuid("entry:$day:${target.customer}:${target.project}:${group.story?.let { "${it.slug}#${it.ref}" } ?: ""}"),
            customer = target.customer,
            project = target.project,
            customerId = customerId(target.customer),
            projectId = projectId(target.customer, target.project),
            mapped = target.mapped,
            story = group.story,
            minutes = minutes,
            // half an hour and more counts as an hour, less as none
            hours = if (target.mapped) ((minutes + 30) / 60).toInt().coerceAtMost(DAILY_LIMIT) else 0,
            description = description(target, group.story, evidence),
            evidence = evidence,
        )
    }

    /** Werkjournal takes at most 12 hours a day: the largest entry gives an hour first, and no entry drops below 1. */
    private fun withinDailyLimit(entries: List<ProposedEntry>): List<ProposedEntry> {
        val hours = entries.map { it.hours }.toMutableList()
        while (hours.sum() > DAILY_LIMIT) {
            val reducible = hours.indices.filter { hours[it] > 1 }
            // more than 12 entries of an hour each: the shortest ones become hints
            val index = reducible.maxWithOrNull(compareBy({ hours[it] }, { -entries[it].minutes }))
                ?: hours.indices.filter { hours[it] > 0 }.minBy { entries[it].minutes }
            hours[index] -= 1
        }
        return entries.mapIndexed { index, entry -> entry.copy(hours = hours[index]) }
    }

    private fun description(target: Target, story: Story?, evidence: List<Evidence>): String {
        // Gitea's own title of an open pull request starts with its number, a draft's with WIP
        val pulls = evidence.mapNotNull { PULL.find(it.title)?.let { match -> match.groupValues[1].toLong() to match.groupValues[2] } }
            .map { (number, subject) -> number to subject.trim().removePrefix("#$number").trim().removePrefix("WIP:").trim() }
            .groupBy({ it.first }, { it.second }).toSortedMap()
            .map { (number, subjects) -> "PR#$number" + (subjects.firstOrNull { it.isNotEmpty() }?.let { " $it" } ?: "") }
        val parts = pulls +
            evidence.filter { it.kind == "task" || it.kind == "issue" || it.kind == "story" }.map { it.title }.distinct().sorted() +
            evidence.filter { it.kind == "session" }.map { "Sitzung „${it.title}“" }.distinct().sorted() +
            evidence.filter { it.kind == "message" }.map { "Matrix ${it.title.substringBefore(" · ").substringBefore(": ")}" }.distinct().sorted()
        val head = story?.let { "Taiga#${it.kind}-${it.ref} ${it.subject}" }
        val text = when {
            head != null && parts.isNotEmpty() -> "$head: ${parts.joinToString("; ")}"
            head != null -> head
            parts.isNotEmpty() -> parts.joinToString("; ")
            else -> target.project
        }
        return if (text.length > MAX_DESCRIPTION) text.take(MAX_DESCRIPTION - 1) + "…" else text
    }

    private fun file(day: LocalDate, entries: List<ProposedEntry>): WerkjournalFile {
        val booked = entries.filter { it.mapped && it.hours > 0 }
        return WerkjournalFile(
            customers = booked.map { WerkjournalCustomer(it.customerId, it.customer) }.distinct(),
            projects = booked.map { WerkjournalProject(it.projectId, it.customerId, it.project) }.distinct(),
            workEntries = booked.map { WerkjournalEntry(it.id, day, it.customerId, it.projectId, it.hours, it.description) },
        )
    }

    private fun storyOf(target: Target, texts: List<String?>, taigaSlug: String?, ref: Long?): Story? {
        val slug = taigaSlug ?: target.settings?.taiga?.firstOrNull() ?: return null
        val number = ref ?: texts.firstNotNullOfOrNull { text -> text?.let { TAIGA_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() } }
            ?: return null
        // an unreachable Taiga must not keep the user from the proposal: the work counts for the project
        return runCatching { stories.story(slug, number) }.getOrNull()
    }

    private fun targetOf(repository: String? = null, cwd: String? = null, taiga: String? = null, room: Item? = null): Target {
        val match = projects.firstOrNull { target ->
            val project = target.settings!!
            repository != null && project.repositories.any { it.equals(repository, ignoreCase = true) } ||
                cwd != null && project.paths.any { cwd.startsWith(expandHome(it).toString()) } ||
                taiga != null && project.taiga.any { it.equals(taiga, ignoreCase = true) } ||
                room != null && project.rooms.any { inRoom(room, it) }
        }
        return match
            ?: config.fallback?.let { fallback -> projects.firstOrNull { it.customer == fallback.customer && it.project == fallback.project } ?: Target(fallback.customer, fallback.project, null, true) }
            ?: Target("?", repository ?: taiga ?: room?.detail?.substringBefore(" · ") ?: cwd ?: "?", null, false)
    }

    // a message knows its room by the ID in its link and by the name before its sender
    private fun inRoom(item: Item, room: String) =
        item.url?.let { URLDecoder.decode(it, Charsets.UTF_8) }?.contains(room) == true ||
            item.detail?.substringBefore(" · ")?.equals(room, ignoreCase = true) == true

    private fun action(time: Instant) = Span(time.minus(ACTION), time)

    private fun customerId(customer: String) = uuid("customer:$customer")

    private fun projectId(customer: String, project: String) = uuid("project:$customer:$project")

    private fun uuid(name: String) = UUID.nameUUIDFromBytes("werkregie:$name".toByteArray()).toString()

    companion object {
        private val ACTION: Duration = Duration.ofMinutes(15)
        private const val DAILY_LIMIT = 12
        private const val MAX_DESCRIPTION = 2000
        // Taiga#US-591, Taiga#591, Taiga545, US-591 and Taiga's own TG-591
        val TAIGA_ID = Regex("""(?i)\b(?:taiga\s*#?\s*(?:us-?)?|us-|tg-)(\d+)""")
        private val PULL = Regex("""PR #(\d+)\s*(.*)""")
    }
}
