package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code sessions the user dismissed from "Du bist dran", each up to the session's time then:
 * a session that changes afterwards, e.g. with a new message, asks again.
 * Kept in a JSON file, so that a restart keeps them.
 */
class Dismissals(private val file: Path, private val mapper: ObjectMapper) {
    private val until = ConcurrentHashMap<String, Instant>()

    init {
        if (Files.exists(file)) {
            runCatching { mapper.readValue<Map<String, Instant>>(file.toFile()) }.getOrDefault(emptyMap()).let(until::putAll)
        }
    }

    fun dismiss(key: String, time: Instant, now: Instant = Instant.now()) {
        until[key] = time
        // a session that never comes back, e.g. an archived one, would stay forever
        until.entries.removeIf { it.value.isBefore(now.minus(KEEP)) }
        save()
    }

    /** The items, a dismissed session without its marker `ask`; a session newer than its dismissal ends it. */
    fun apply(items: List<Item>): List<Item> {
        var ended = false
        val result = items.map { item ->
            val key = keyOf(item) ?: return@map item
            val time = until[key] ?: return@map item
            if (item.time != null && item.time.isAfter(time)) {
                ended = until.remove(key, time) || ended
                item
            } else {
                item.copy(flags = item.flags.filterNot { it.kind == ASK })
            }
        }
        if (ended) save()
        return result
    }

    @Synchronized
    private fun save() {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, ".dismissed", ".tmp")
        mapper.writeValue(temporary.toFile(), until.toSortedMap())
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val FILE_NAME = "dismissed.json"
        private const val ASK = "ask"
        private val KEEP: Duration = Duration.ofDays(30)

        /** A session's link to Claude Desktop, else its resume command; null for anything but a session. */
        fun keyOf(item: Item): String? = if (item.kind == "session") item.url ?: item.command else null

        /** Whether [items] hold a session with [key] that asks the user, the only thing worth dismissing. */
        fun asks(items: List<Item>, key: String): Boolean = items.any { keyOf(it) == key && it.flags.any { f -> f.kind == ASK } }
    }
}
