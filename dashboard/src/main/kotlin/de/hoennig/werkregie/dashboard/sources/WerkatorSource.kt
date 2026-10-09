package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.WerkatorConfig
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Builds of one Werkator instance, all its repositories. The `@ai-review` builds are the
 * werkrevisor runs Werkator starts for each ready PR head, so they appear as werkrevisor.
 */
class WerkatorSource(private val config: WerkatorConfig, private val http: JsonHttp) : Source {
    override val id = "werkator:${config.name}"
    override val label = "Werkator ${config.name}"
    override val url = config.url.trimEnd('/')
    override val hint = config.hint
    override val ttl: Duration = Duration.ofSeconds(15)

    // the control token as cookie, the way Werkator's own pages send it
    private val headers = (config.controlToken ?: config.controlTokenEnv?.let { System.getenv(it) })
        ?.let { mapOf("Cookie" to "werkator_token=$it") }
        .orEmpty()

    @Volatile
    private var hiddenRepos = 0

    override fun note() = hiddenRepos.takeIf { it > 0 }
        ?.let { (if (it == 1) "1 privates Repo" else "$it private Repos") + " verborgen – controlToken konfigurieren" }

    override fun fetch(now: Instant, since: Instant): List<Item> {
        val all = http.get("$url/api/repos", headers).toList()
        // a private repository shows to a visitor without name and url
        val repos = all.mapNotNull { repo -> repo.text("name")?.takeIf { repo.text("url") != null } }
        hiddenRepos = all.size - repos.size
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            repos.map { repo -> executor.submit<List<Item>> { builds(repo, now, since) } }
                .flatMap { it.get() }
        }
    }

    private fun builds(repo: String, now: Instant, since: Instant): List<Item> {
        val encoded = URLEncoder.encode(repo, Charsets.UTF_8)
        return http.get("$url/api/repos/$encoded/builds/history", headers)
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
    }
}
