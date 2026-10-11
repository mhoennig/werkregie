package de.hoennig.werkregie.dashboard.relay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.RelayReport
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.MultiStatusSource
import de.hoennig.werkregie.dashboard.SourceStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The last report of every relay, kept in a JSON file so a restarted server is filled at once.
 * As a [Source] it yields the relays' items, aged by how long each relay has been silent:
 * stale after [stale], and after [gone] its running and open sessions count as ended.
 */
class RelayRegistry(
    private val file: Path,
    private val mapper: ObjectMapper,
    private val stale: Duration,
    private val gone: Duration,
) : MultiStatusSource {
    override val id = "relays"
    override val label = "Relays"
    override val ttl: Duration = Duration.ZERO

    internal data class Received(val report: RelayReport, val receivedAt: Instant)

    private val relays = ConcurrentHashMap<String, Received>()

    @Volatile
    private var savedAt: Instant = Instant.EPOCH

    init {
        if (Files.exists(file)) {
            runCatching { mapper.readValue<List<Received>>(file.toFile()) }
                .getOrDefault(emptyList())
                .forEach { relays[it.report.relay] = it }
        }
    }

    fun accept(report: RelayReport, now: Instant = Instant.now()) {
        require(NAME.matches(report.relay)) { "invalid relay name" }
        relays[report.relay] = Received(report, now)
        // a report arrives every few seconds; the file only has to survive a restart
        if (savedAt.plus(SAVE_INTERVAL).isBefore(now)) save(now)
    }

    /** Drops a relay that reports no more; true when it was known. A relay still running reports again. */
    fun forget(name: String): Boolean = (relays.remove(name) != null).also { removed -> if (removed) save() }

    @Synchronized
    fun save(now: Instant = Instant.now()) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, ".relays", ".tmp")
        mapper.writeValue(temporary.toFile(), relays.values.sortedBy { it.report.relay })
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        savedAt = now
    }

    override fun fetch(now: Instant, since: Instant): List<Item> =
        relays.values.flatMap { received ->
            val silent = Duration.between(received.receivedAt, now)
            received.report.items
                .map { item -> if (silent > gone) ended(item) else item }
                .filter { item -> item.section != Section.RECENT || (item.time?.isAfter(since) ?: false) }
                .map { item -> item.copy(origin = "${received.report.relay} · ${item.origin}") }
        }

    /** One chip per relay instead of one for the registry. */
    override fun statuses(now: Instant): List<SourceStatus> =
        relays.values.sortedBy { it.report.relay }.map { received ->
            val silent = Duration.between(received.receivedAt, now)
            val failing = received.report.sources.filter { !it.ok }
            SourceStatus(
                id = "relay:${received.report.relay}",
                label = "Relay ${received.report.relay}",
                ok = true,
                message = when {
                    silent > stale -> "zuletzt vor ${ago(silent)}"
                    failing.isNotEmpty() -> failing.joinToString(" · ") { "${it.label}: ${it.message}" }
                    else -> null
                },
                url = null,
                count = received.report.items.size,
                fetchedAt = received.receivedAt,
                tools = received.report.sources.filter { it.ok }.map { it.id.substringBefore(':') }.distinct(),
                failingTools = failing.map { it.id.substringBefore(':') }.distinct(),
                silent = silent > stale,
            )
        }

    private fun ended(item: Item) = when (item.section) {
        Section.RECENT -> item
        else -> item.copy(section = Section.RECENT, state = "closed")
    }

    private fun ago(duration: Duration) = when {
        duration.toMinutes() < 2 -> "${duration.seconds} s"
        duration.toHours() < 2 -> "${duration.toMinutes()} min"
        duration.toDays() < 2 -> "${duration.toHours()} h"
        else -> "${duration.toDays()} d"
    }

    companion object {
        const val FILE_NAME = "relays.json"
        val NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val SAVE_INTERVAL: Duration = Duration.ofSeconds(15)
    }
}
