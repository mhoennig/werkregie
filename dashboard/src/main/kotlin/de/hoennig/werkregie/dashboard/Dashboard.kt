package de.hoennig.werkregie.dashboard

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface Source {
    val id: String
    val label: String
    val url: String? get() = null

    // remote sources are cached longer than the cheap local ones
    val ttl: Duration

    // a hint shown next to an error, e.g. how to open a tunnel
    val hint: String? get() = null

    fun fetch(now: Instant, since: Instant): List<Item>

    // something worth knowing about the last successful fetch, e.g. hidden private repositories
    fun note(): String? = null

    // the load of the host behind the source, e.g. a Werkator instance's, as of the last fetch
    fun load(): HostLoad? = null

    // the costs the source reports, e.g. werkrevisor's, as of the last fetch
    fun costs(): Costs? = null
}

/** A source standing for several things, e.g. the relays: it reports one status per relay. */
interface MultiStatusSource : Source {
    fun statuses(now: Instant): List<SourceStatus>
}

/**
 * Fetches all sources in parallel, each with its own cache. Cached items are served at once
 * while a due source refreshes in the background; only a source never fetched before is waited for.
 * A failing source keeps its last good items and reports the error, so one unreachable server never blanks the page.
 */
class Dashboard(
    private val sources: List<Source>,
    private val windowHours: Long,
    val version: String = "dev",
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
    private val dismissals: Dismissals? = null,
) {
    private class Entry(
        var items: List<Item> = emptyList(),
        var fetchedAt: Instant? = null,
        var attemptedAt: Instant? = null,
        var error: String? = null,
        var refreshing: Future<*>? = null,
    )

    private val entries = sources.associate { it.id to Entry() }

    fun snapshot(now: Instant = Instant.now(), access: Access = Access.OPEN): Snapshot {
        val since = now.minus(Duration.ofHours(windowHours))
        val firstFetches = sources.mapNotNull { source -> refreshIfDue(source, now, since) }
        firstFetches.forEach { runCatching { it.get(FIRST_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) } }

        val statuses = sources.flatMap { source ->
            if (source is MultiStatusSource) return@flatMap source.statuses(now)
            val entry = entries.getValue(source.id)
            synchronized(entry) {
                listOf(SourceStatus(
                    id = source.id,
                    label = source.label,
                    ok = entry.error == null,
                    message = entry.error?.let { error -> source.hint?.let { "$error — $it" } ?: error } ?: source.note(),
                    url = source.url,
                    count = entry.items.size,
                    fetchedAt = entry.fetchedAt,
                ))
            }
        }
        val items = sources.flatMap { source -> entries.getValue(source.id).let { synchronized(it) { it.items } } }
            .sortedByDescending { it.time ?: Instant.EPOCH }
            .let { dismissals?.apply(it) ?: it }
        return Snapshot(version, now, windowHours, items, statuses, access,
            loads = sources.mapNotNull { it.load() }, costs = sources.mapNotNull { it.costs() })
    }

    /** Starts a background refresh when the ttl is over; returns it only if nothing was fetched yet. */
    private fun refreshIfDue(source: Source, now: Instant, since: Instant): Future<*>? {
        val entry = entries.getValue(source.id)
        // a source without a ttl holds its data in memory already, e.g. the relays: read it now
        if (source.ttl.isZero) return refresh(source, entry, now, since).let { null }
        synchronized(entry) {
            entry.refreshing?.takeIf { !it.isDone }?.let { return if (entry.fetchedAt == null) it else null }
            // failures wait for the ttl as well, so a dead server is not asked on every poll
            val attemptedAt = entry.attemptedAt
            if (attemptedAt != null && attemptedAt.plus(source.ttl).isAfter(now)) return null
            entry.attemptedAt = now
            val refresh = executor.submit { refresh(source, entry, now, since) }
            entry.refreshing = refresh
            return if (entry.fetchedAt == null) refresh else null
        }
    }

    private fun refresh(source: Source, entry: Entry, now: Instant, since: Instant) {
        val result = runCatching { source.fetch(now, since) }
        synchronized(entry) {
            result.onSuccess { items ->
                entry.items = items
                entry.fetchedAt = now
                entry.error = null
            }.onFailure { e ->
                entry.error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    companion object {
        private const val FIRST_FETCH_TIMEOUT_SECONDS = 20L
    }
}
