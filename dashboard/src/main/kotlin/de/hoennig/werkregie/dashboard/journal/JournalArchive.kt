package de.hoennig.werkregie.dashboard.journal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.hoennig.werkregie.dashboard.Item
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What Werkregie saw of one local day: the active time of the sessions, and the items of all sources. */
data class JournalDay(
    val day: LocalDate,
    val zone: String,
    val sessions: List<Activity> = emptyList(),
    val items: List<Item> = emptyList(),
)

/**
 * One JSON file per local day, `<dir>/YYYY-MM-DD.json`, which outlives the sources:
 * a recorded span or item stays when its source forgets it, e.g. after `windowHours`.
 * A span is joined with the spans recorded before; a later state of an item replaces its earlier one.
 */
class JournalArchive(
    private val dir: Path,
    private val zone: ZoneId,
    private val keepDays: Long,
    private val mapper: ObjectMapper,
) {
    private class Cached(var day: JournalDay, var written: ByteArray?)

    // the days of the current window, so a record every minute does not read every file again
    private val cache = object : LinkedHashMap<LocalDate, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, Cached>) = size > CACHED_DAYS
    }
    private var prunedOn: LocalDate? = null

    @Synchronized
    fun record(now: Instant, items: List<Item>, activity: List<Activity>) {
        val sessionsByDay = mutableMapOf<LocalDate, MutableList<Activity>>()
        for (session in activity) {
            session.spans.flatMap { Spans.byDay(it, zone).entries }
                .groupBy({ it.key }, { it.value })
                .forEach { (day, spans) -> sessionsByDay.getOrPut(day) { mutableListOf() } += session.copy(spans = spans) }
        }
        // a session's items are its sessions above, with their active time
        val itemsByDay = items.filter { it.time != null && it.kind != "session" }
            .groupBy { it.time!!.atZone(zone).toLocalDate() }

        for (day in sessionsByDay.keys + itemsByDay.keys) {
            val cached = cached(day)
            cached.day = merge(cached.day, sessionsByDay[day].orEmpty(), itemsByDay[day].orEmpty())
            write(cached)
        }
        prune(now.atZone(zone).toLocalDate())
    }

    @Synchronized
    fun day(day: LocalDate): JournalDay = cached(day).day

    private fun cached(day: LocalDate): Cached = cache.getOrPut(day) {
        val file = fileOf(day)
        val bytes = if (Files.exists(file)) runCatching { Files.readAllBytes(file) }.getOrNull() else null
        val stored = bytes?.let { runCatching { mapper.readValue<JournalDay>(it) }.getOrNull() }
        Cached(stored ?: JournalDay(day, zone.id), bytes)
    }

    private fun merge(day: JournalDay, sessions: List<Activity>, items: List<Item>): JournalDay {
        val byKey = day.sessions.associateByTo(linkedMapOf()) { sessionKey(it) }
        for (session in sessions) {
            val known = byKey[sessionKey(session)]
            byKey[sessionKey(session)] = if (known == null) {
                session.copy(spans = Spans.merge(session.spans))
            } else {
                Activity(
                    tool = session.tool,
                    session = session.session,
                    origin = session.origin ?: known.origin,
                    title = session.title ?: known.title,
                    cwd = session.cwd ?: known.cwd,
                    project = session.project ?: known.project,
                    repository = session.repository ?: known.repository,
                    branches = (known.branches + session.branches).distinct(),
                    spans = Spans.merge(known.spans + session.spans),
                )
            }
        }
        val itemsByKey = day.items.associateByTo(linkedMapOf()) { itemKey(it) }
        items.forEach { itemsByKey[itemKey(it)] = it }
        return day.copy(
            sessions = byKey.values.sortedWith(compareBy({ it.spans.firstOrNull()?.from }, { it.session })),
            items = itemsByKey.values.sortedWith(compareBy({ it.time }, { itemKey(it) })),
        )
    }

    private fun write(cached: Cached) {
        val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cached.day)
        if (cached.written?.contentEquals(bytes) == true) return
        Files.createDirectories(dir)
        val temporary = Files.createTempFile(dir, ".day", ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, fileOf(cached.day.day), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cached.written = bytes
    }

    /** Deletes the days older than [keepDays], once a day. */
    private fun prune(today: LocalDate) {
        if (prunedOn == today || !Files.isDirectory(dir)) return
        prunedOn = today
        val oldest = today.minusDays(keepDays)
        Files.list(dir).use { files ->
            files.toList().forEach { file ->
                val day = DAY_FILE.matchEntire(file.fileName.toString())?.groupValues?.get(1)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                if (day.isBefore(oldest)) {
                    Files.deleteIfExists(file)
                    cache.remove(day)
                }
            }
        }
    }

    private fun fileOf(day: LocalDate): Path = dir.resolve("$day.json")

    private fun sessionKey(session: Activity) = "${session.tool}|${session.session}"

    // an activity is an event, so a merge and a comment on the same pull request are two
    private fun itemKey(item: Item) =
        listOfNotNull(item.source, item.origin, item.kind, item.url ?: item.title, item.state.takeIf { item.kind == "activity" })
            .joinToString("|")

    companion object {
        const val DIR_NAME = "journal"
        private const val CACHED_DAYS = 16
        private val DAY_FILE = Regex("""^(\d{4}-\d{2}-\d{2})\.json$""")
    }
}
