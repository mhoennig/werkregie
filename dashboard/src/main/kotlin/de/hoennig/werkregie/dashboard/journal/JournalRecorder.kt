package de.hoennig.werkregie.dashboard.journal

import de.hoennig.werkregie.dashboard.Dashboard
import java.time.Duration
import java.time.Instant

/**
 * Records the dashboard's items and the sessions' active time into the [archive] every [interval],
 * whether or not anybody looks at the page, so a day is complete even when it was never opened.
 */
class JournalRecorder(
    private val archive: JournalArchive,
    private val dashboard: Dashboard,
    private val activity: ActivitySource,
    private val windowHours: Long,
    private val interval: Duration = Duration.ofMinutes(1),
    private val log: (String) -> Unit = { println("${Instant.now()} $it") },
) {
    @Volatile
    private var lastError: String? = null

    fun start(): Thread = Thread.ofVirtual().name("journal").start {
        while (!Thread.currentThread().isInterrupted) {
            recordOnce()
            runCatching { Thread.sleep(interval) }.onFailure { return@start }
        }
    }

    fun recordOnce(now: Instant = Instant.now()) {
        val error = runCatching {
            val since = now.minus(Duration.ofHours(windowHours))
            archive.record(now, dashboard.snapshot(now).items, activity.activity(now, since))
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        // a failure every minute would flood the log: only a change is worth a line
        if (error != lastError) log(error?.let { "Journal: $it" } ?: "Journal: schreibt wieder")
        lastError = error
    }
}
