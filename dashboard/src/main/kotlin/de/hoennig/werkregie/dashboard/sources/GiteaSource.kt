package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.ConfigLoader
import de.hoennig.werkregie.dashboard.GiteaConfig
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.Executors

/**
 * Open pull requests with their commit statuses (Werkator, werkrevisor's `ai-review/gate`, …)
 * and the token user's own recent activity of one Gitea instance.
 */
class GiteaSource(
    private val config: GiteaConfig,
    private val http: JsonHttp,
    private val mapper: ObjectMapper,
    private val tokenLookup: (String) -> String? = { ConfigLoader.teaToken(it, config.url) },
) : Source {
    override val id = "gitea:${config.name}"
    override val label = "Gitea ${config.name}"
    override val url = config.url.trimEnd('/')
    override val ttl: Duration = Duration.ofSeconds(30)

    private val api = "$url/api/v1"

    private fun token(): String =
        config.token
            ?: config.tokenEnv?.let { System.getenv(it) }
            ?: config.teaLogin?.let(tokenLookup)
            ?: throw IllegalStateException(
                config.teaLogin?.let { "tea-Login '$it' für ${config.url} nicht gefunden" }
                    ?: "kein Token konfiguriert (token, tokenEnv oder teaLogin)",
            )

    override fun fetch(now: Instant, since: Instant): List<Item> {
        val headers = mapOf("Authorization" to "token ${token()}")
        val login = http.get("$api/user", headers).text("login") ?: error("unbekannter Benutzer")
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val activities = executor.submit<List<Item>> { activities(login, headers, since) }
            val pullRequests = openPullRequests(headers)
                .map { pr -> executor.submit<Item> { pullRequestItem(pr, headers) } }
            pullRequests.map { it.get() } + activities.get()
        }
    }

    private fun openPullRequests(headers: Map<String, String>): List<JsonNode> {
        val search = "$api/repos/issues/search?type=pulls&state=open&limit=50"
        val filters = if (config.pullRequests == "all") listOf("") else MINE
        return filters.flatMap { filter -> http.get(search + filter, headers).toList() }
            .distinctBy { it["id"]?.asLong() }
            .sortedByDescending { it.text("updated_at") }
            .take(config.maxPullRequests)
    }

    private fun pullRequestItem(issue: JsonNode, headers: Map<String, String>): Item {
        val repo = issue["repository"]?.text("full_name") ?: "?"
        val number = issue["number"]?.asLong()
        // the search result lacks the head commit, the PR itself has it
        val pull = runCatching { http.get("$api/repos/$repo/pulls/$number", headers) }.getOrNull()
        val sha = pull?.get("head")?.text("sha")
        val statuses = sha?.let {
            runCatching { http.get("$api/repos/$repo/commits/$it/status", headers)["statuses"]?.toList() }.getOrNull()
        }.orEmpty()
        val draft = pull?.get("draft")?.asBoolean() == true ||
            issue.text("title")?.let { DRAFT_PREFIX.containsMatchIn(it) } == true
        val combined = statuses.map { it.text("status") ?: "" }.let(::combine)
        return Item(
            source = "gitea",
            origin = config.name,
            kind = "pr",
            section = Section.OPEN,
            title = "#$number ${issue.text("title") ?: ""}".trim(),
            state = if (draft) "draft" else combined,
            time = issue.text("updated_at")?.let(::instant),
            project = repo,
            detail = listOfNotNull(
                issue["user"]?.text("login")?.let { "von $it" },
                pull?.get("head")?.text("ref")?.let { "⎇ $it" },
                pull?.get("mergeable")?.asBoolean()?.takeIf { !it }?.let { "Konflikt" },
            ).joinToString(" · "),
            url = issue.text("html_url"),
            badges = statuses.sortedBy { it.text("context") }.map(::statusBadge),
        )
    }

    internal fun statusBadge(status: JsonNode): Badge {
        val context = status.text("context") ?: "?"
        val description = status.text("description")
        val verdict = description?.let { VERDICT.find(it)?.value }
        return Badge(
            // werkrevisor reports the run of its build as ai-review/gate and its verdict as ai-review/assessment
            label = when {
                context.startsWith(REVIEW_PREFIX) ->
                    listOfNotNull("werkrevisor", REVIEW_ASPECTS[context.removePrefix(REVIEW_PREFIX)], verdict).joinToString(" ")
                else -> context
            },
            state = verdict?.lowercase() ?: status.text("status") ?: "unknown",
            url = status.text("target_url"),
            title = description,
        )
    }

    private fun combine(states: List<String>) = when {
        states.isEmpty() -> "open"
        states.any { it == "error" || it == "failure" } -> "failure"
        states.any { it == "pending" } -> "pending"
        states.all { it == "success" } -> "success"
        else -> states.first()
    }

    /** The user's own feed; consecutive pushes to the same branch collapse into one row. */
    private fun activities(login: String, headers: Map<String, String>, since: Instant): List<Item> {
        val feed = http.get("$api/users/$login/activities/feeds?only-performed-by=true&limit=50", headers)
            .filter { activity -> activity.text("created")?.let(::instant)?.isAfter(since) == true }
        val items = mutableListOf<Item>()
        val pushes = mutableListOf<JsonNode>()
        fun flush() {
            if (pushes.isNotEmpty()) items += pushItem(pushes)
            pushes.clear()
        }
        for (activity in feed) {
            if (activity.text("op_type") == "commit_repo") {
                val previous = pushes.lastOrNull()
                if (previous != null && !samePushTarget(previous, activity)) flush()
                pushes.add(activity)
            } else {
                flush()
                items += activityItem(activity)
            }
        }
        flush()
        return items
    }

    private fun samePushTarget(a: JsonNode, b: JsonNode) =
        a["repo"]?.text("full_name") == b["repo"]?.text("full_name") && a.text("ref_name") == b.text("ref_name")

    private fun pushItem(pushes: List<JsonNode>): Item {
        val latest = pushes.first()
        val branch = latest.text("ref_name")?.removePrefix("refs/heads/") ?: "?"
        val commits = pushes.sumOf { push ->
            runCatching { mapper.readTree(push.text("content"))["Commits"]?.size() }.getOrNull() ?: 1
        }
        val message = runCatching { mapper.readTree(latest.text("content"))["Commits"]?.get(0)?.text("Message") }
            .getOrNull()?.firstLine(100)
        val repoUrl = latest["repo"]?.text("html_url")
        return Item(
            source = "gitea",
            origin = config.name,
            kind = "activity",
            section = Section.RECENT,
            title = "$commits Commit${if (commits == 1) "" else "s"} auf $branch",
            state = "push",
            time = latest.text("created")?.let(::instant),
            project = latest["repo"]?.text("full_name"),
            detail = message,
            url = repoUrl?.let { "$it/src/branch/$branch" },
        )
    }

    private fun activityItem(activity: JsonNode): Item {
        val opType = activity.text("op_type") ?: "?"
        val content = activity.text("content") ?: ""
        val repoUrl = activity["repo"]?.text("html_url")
        // pull request and issue operations carry "<number>|<title>" as content
        val number = content.substringBefore('|', "").toLongOrNull()
        val subject = content.substringAfter('|', "").firstLine(100).takeIf { number != null && it.isNotBlank() }
        val isPull = "pull" in opType
        val branch = activity.text("ref_name")?.removePrefix("refs/heads/")?.removePrefix("refs/tags/")
        return Item(
            source = "gitea",
            origin = config.name,
            kind = "activity",
            section = Section.RECENT,
            title = listOfNotNull(
                OPERATIONS[opType] ?: opType.replace('_', ' '),
                number?.let { (if (isPull) "PR #" else "#") + it },
                subject ?: branch,
            ).joinToString(" "),
            state = opType,
            time = activity.text("created")?.let(::instant),
            project = activity["repo"]?.text("full_name"),
            url = when {
                repoUrl == null -> null
                number != null -> "$repoUrl/${if (isPull) "pulls" else "issues"}/$number"
                else -> repoUrl
            },
        )
    }

    private fun instant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    companion object {
        private const val REVIEW_PREFIX = "ai-review/"
        private val REVIEW_ASPECTS = mapOf("gate" to "Lauf", "assessment" to "Urteil")
        private val VERDICT = Regex("\\b(GREEN|YELLOW|ORANGE|RED|ERROR)\\b")
        private val DRAFT_PREFIX = Regex("^\\s*(WIP|\\[WIP]|Draft)\\b", RegexOption.IGNORE_CASE)
        private val MINE = listOf("&created=true", "&assigned=true", "&review_requested=true")
        private val OPERATIONS = mapOf(
            "create_pull_request" to "eröffnet",
            "merge_pull_request" to "gemergt",
            "auto_merge_pull_request" to "auto-gemergt",
            "close_pull_request" to "geschlossen",
            "reopen_pull_request" to "wiedereröffnet",
            "approve_pull_request" to "approved",
            "reject_pull_request" to "Änderungen angefordert",
            "comment_pull" to "kommentiert",
            "pull_review_dismissed" to "Review verworfen",
            "create_issue" to "Issue eröffnet",
            "close_issue" to "Issue geschlossen",
            "reopen_issue" to "Issue wiedereröffnet",
            "comment_issue" to "kommentiert",
            "push_tag" to "Tag",
            "delete_tag" to "Tag gelöscht",
            "delete_branch" to "Branch gelöscht",
            "create_repo" to "Repo angelegt",
            "publish_release" to "Release",
        )
    }
}
