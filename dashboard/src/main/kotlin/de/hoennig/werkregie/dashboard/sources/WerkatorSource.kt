package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.HostLoad
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.LoadChart
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.WerkatorConfig
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Builds of one Werkator instance, all its repositories. The `@ai-review` builds are the
 * werkrevisor runs Werkator starts for each ready PR head, so they appear as werkrevisor.
 */
class WerkatorSource(private val config: WerkatorConfig, private val http: JsonHttp) : Source {
    override val id = "werkator:${config.name}"
    override val label = "Werkator ${config.name}"
    override val url = config.url.trimEnd('/')
    override val hint = config.hint
    // a build history takes seconds on a loaded host; asking more often only adds to the load
    override val ttl: Duration = Duration.ofSeconds(30)

    // the control token as cookie, the way Werkator's own pages send it
    private val headers = (config.controlToken ?: config.controlTokenEnv?.let { System.getenv(it) })
        ?.let { mapOf("Cookie" to "werkator_token=$it") }
        .orEmpty()

    @Volatile
    private var hiddenRepos = 0

    override fun note() = hiddenRepos.takeIf { it > 0 }
        ?.let { (if (it == 1) "1 privates Repo" else "$it private Repos") + " verborgen – controlToken konfigurieren" }

    @Volatile
    private var load: HostLoad? = null

    @Volatile
    private var steps: Map<String, List<Double?>> = emptyMap()

    @Volatile
    private var historyAt: Instant? = null

    override fun load() = load

    override fun fetch(now: Instant, since: Instant): List<Item> {
        // an older Werkator without the system API still shows its builds
        load = runCatching { readLoad(now) }.getOrNull()
        val all = http.get("$url/api/repos", headers).toList()
        // a private repository shows to a visitor without name and url
        val repos = all.mapNotNull { repo -> repo.text("name")?.takeIf { repo.text("url") != null } }
        hiddenRepos = all.size - repos.size
        // a few at a time: Werkator may share the host with this server and is building meanwhile
        val slots = Semaphore(PARALLEL_REQUESTS)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            repos.map { repo ->
                executor.submit<List<Item>> {
                    slots.acquire()
                    try { builds(repo, now, since) } finally { slots.release() }
                }
            }.flatMap { it.get() }
        }
    }

    private fun readLoad(now: Instant): HostLoad {
        val system = http.get("$url/api/system", headers)
        // the history is long; its lines change slowly, so it is read less often than the snapshot
        if (historyAt?.isAfter(now.minus(HISTORY_TTL)) != true) {
            // a failed read keeps the last lines and is tried again at the next fetch, not in 5 minutes
            runCatching { steps(http.get("$url/api/system/history?range=24h", headers, REQUEST_TIMEOUT)) }.onSuccess {
                steps = it
                historyAt = now
            }
        }
        return load(system, steps)
    }

    /** Werkator's verdict and the charts of its [CHARTS] most critical metrics, in Werkator's order. */
    internal fun load(system: JsonNode, steps: Map<String, List<Double?>>): HostLoad {
        val cpus = system.total("cpuCount")
        val swap = system.total("swapTotalGib")
        val disks = system["disks"]?.map {
            utilization("disk", "disk.${it.text("key")}.usedGib", it["usedGib"]?.number("current"), it.total("totalGib"), it.text("label"))
        }.orEmpty()
        val metrics = listOf(
            utilization("cpu", "cpuUsed", system["cpuUsed"]?.number("current"), cpus),
            utilization("ram", "ramUsedGib", system["ramUsedGib"]?.number("current"), system.total("ramTotalGib")),
        ) + disks + listOf(
            Metric("load", "load1", system.number("load1"), cpus,
                warn = cpus?.times(LOAD_PRESSURE_PER_THREAD), bad = cpus?.times(LOAD_OVERLOAD_PER_THREAD)),
            Metric("swap", "swapUsedGib", system["swapUsedGib"]?.number("current"), swap, warn = swap?.times(SWAP_PRESSURE_SHARE)),
            Metric("cpuPressure", "cpuPressure", system.number("cpuPressure"), PERCENT, warn = CPU_PRESSURE_PERCENT),
            Metric("memoryPressure", "memoryPressure", system.number("memoryPressure"), PERCENT,
                warn = MEMORY_PRESSURE_PERCENT, bad = MEMORY_OVERLOAD_PERCENT),
            // Werkator gives disk wait no level; for the ranking it counts like CPU wait
            Metric("ioPressure", "ioPressure", system.number("ioPressure"), PERCENT, warn = null, reference = CPU_PRESSURE_PERCENT),
        )
        val critical = metrics.filter { it.used != null }
            .sortedWith(compareByDescending<Metric> { LEVELS.indexOf(it.level) }.thenByDescending { it.weight })
            .take(CHARTS).toSet()
        return HostLoad(
            source = id,
            label = label,
            url = url,
            level = system["health"]?.text("level") ?: "unknown",
            reasons = system["health"]?.get("reasons")?.mapNotNull { it.asText(null) }.orEmpty(),
            charts = metrics.filter { it in critical }.map { it.chart(steps[it.series].orEmpty()) },
            at = system.text("timestamp")?.let(Instant::parse),
        )
    }

    /** Each series of the 24 hours in [HISTORY_STEPS] equal steps, each the mean of its samples; a step without any is null. */
    internal fun steps(history: JsonNode): Map<String, List<Double?>> =
        history["values"]?.properties()?.associate { (series, samples) ->
            val values = samples.map { if (it.isNumber) it.asDouble() else null }
            val size = values.size.toDouble() / HISTORY_STEPS
            series to if (values.isEmpty()) emptyList() else (0 until HISTORY_STEPS).map { step ->
                values.subList((step * size).toInt(), ((step + 1) * size).toInt().coerceAtMost(values.size))
                    .filterNotNull().takeIf { it.isNotEmpty() }?.average()
            }
        }.orEmpty()

    private fun utilization(key: String, series: String, used: Double?, total: Double?, label: String? = null) =
        Metric(key, series, used, total, warn = total?.times(WARN_SHARE), bad = total?.times(BAD_SHARE), label = label)

    private fun JsonNode.total(field: String) = number(field)?.takeIf { it > 0 }

    /** One metric of `/api/system` in the unit of its history series, with Werkator's thresholds. */
    private class Metric(
        val key: String,
        val series: String,
        val used: Double?,
        val total: Double?,
        val warn: Double?,
        val bad: Double? = null,
        val label: String? = null,
        // where it starts to matter, to rank the metrics of equal level
        reference: Double? = warn,
    ) {
        val level = when {
            used == null -> "ok"
            bad != null && used >= bad -> "bad"
            warn != null && used >= warn -> "warn"
            else -> "ok"
        }
        val weight = if (used != null && reference != null && reference > 0) used / reference else 0.0

        // like on Werkator's home rail: the scale is the total, or the highest value if it is above it
        fun chart(steps: List<Double?>): LoadChart {
            val scale = total?.let { maxOf(it, steps.filterNotNull().maxOrNull() ?: 0.0) }
            return LoadChart(
                key = key,
                label = label,
                value = if (key == "load") used else total?.let { all -> used?.let { (it / all).coerceIn(0.0, 1.0) } },
                level = level,
                history = scale?.let { s -> steps.map { it?.div(s)?.coerceIn(0.0, 1.0) } }.orEmpty(),
            )
        }
    }

    private fun builds(repo: String, now: Instant, since: Instant): List<Item> {
        val encoded = URLEncoder.encode(repo, Charsets.UTF_8)
        return http.get("$url/api/repos/$encoded/builds/history", headers, REQUEST_TIMEOUT)
            .mapNotNull { build -> item(repo, encoded, build, now, since) }
            .take(MAX_PER_REPO)
    }

    internal fun item(repo: String, encodedRepo: String, build: JsonNode, now: Instant, since: Instant): Item? {
        val status = build.text("status") ?: "unknown"
        val startedAt = build.text("startedAt")?.let(Instant::parse)
        // the history keeps superseded builds as pending forever; only recent ones are really waiting
        val running = status in ACTIVE && startedAt?.isAfter(now.minus(STALE_AFTER)) == true
        val finishedAt = build["durationSeconds"]?.takeIf { it.isNumber }
            ?.let { startedAt?.plusSeconds(it.asLong()) }
        val time = finishedAt ?: startedAt
        if (!running && (time == null || time.isBefore(since))) return null
        val name = build.text("name") ?: build.text("branch") ?: "?"
        val review = name.endsWith(REVIEW_SUFFIX)
        val pullRequest = build["pullRequest"]?.takeIf { it.text("number") != null }
        return Item(
            source = if (review) "werkrevisor" else "werkator",
            origin = config.name,
            kind = if (review) "review" else "build",
            section = if (running) Section.RUNNING else Section.RECENT,
            title = name.removeSuffix(REVIEW_SUFFIX),
            state = if (status in ACTIVE && !running) "stale" else status,
            time = if (running) startedAt else time,
            project = repo,
            detail = listOfNotNull(
                build.text("commit")?.take(8),
                build["durationSeconds"]?.takeIf { it.isNumber }?.let { duration(it.asLong()) },
            ).joinToString(" · "),
            url = build.text("artifactKey")?.let { "$url/repos/$encodedRepo/builds/$it" },
            badges = listOfNotNull(
                pullRequest?.let { pr ->
                    Badge(label = "PR #${pr.text("number")}", state = "link", url = pr.text("url"))
                },
            ),
        )
    }

    private fun duration(seconds: Long) = if (seconds < 120) "$seconds s" else "${seconds / 60} min"

    companion object {
        private const val REVIEW_SUFFIX = "@ai-review"
        private const val MAX_PER_REPO = 40
        private val ACTIVE = setOf("running", "pending", "queued")
        private val STALE_AFTER = Duration.ofHours(12)
        private const val PARALLEL_REQUESTS = 3
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val HISTORY_TTL: Duration = Duration.ofMinutes(5)
        private const val HISTORY_STEPS = 48
        private const val CHARTS = 3
        private val LEVELS = listOf("ok", "warn", "bad")
        // Werkator's thresholds, see its SystemHealth
        private const val WARN_SHARE = 0.8
        private const val BAD_SHARE = 0.9
        private const val LOAD_PRESSURE_PER_THREAD = 1.0
        private const val LOAD_OVERLOAD_PER_THREAD = 2.0
        private const val SWAP_PRESSURE_SHARE = 0.5
        private const val CPU_PRESSURE_PERCENT = 20.0
        private const val MEMORY_PRESSURE_PERCENT = 2.0
        private const val MEMORY_OVERLOAD_PERCENT = 10.0
        private const val PERCENT = 100.0
    }
}
