package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.ConfigLoader
import de.hoennig.werkregie.dashboard.Flag
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
                .map { pr -> executor.submit<Item> { pullRequestItem(pr, login, headers) } }
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

    private fun pullRequestItem(issue: JsonNode, login: String, headers: Map<String, String>): Item {
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
        val author = issue["user"]?.text("login")
        val conflict = conflictOf(pull, draft)
        val reviews = if (draft || sha == null) emptyList() else
            runCatching { http.get("$api/repos/$repo/pulls/$number/reviews", headers).toList() }.getOrDefault(emptyList())
        val approvers = reviews.filter { it.isApprovalOf(sha) }.mapNotNull { it["user"]?.text("login") }
        // the timeline costs a request per pull request; only one that could be merged needs it
        val automerge = approvers.isNotEmpty() && combined == "success" && !conflict &&
            runCatching { automergeScheduled(http.get("$api/repos/$repo/issues/$number/timeline?limit=100", headers).toList()) }
                .getOrDefault(false)
        val conflictSince = if (conflict && author != login) pull?.let { conflictSince(repo, it, headers) } else null
        val pullRequest = PullRequest(
            own = author == login,
            draft = draft,
            conflict = conflict,
            statuses = statuses,
            approvers = approvers,
            requested = requestedReviewers(reviews),
            author = author,
            automerge = automerge,
            updated = issue.text("updated_at")?.let(::instant),
            conflictSince = conflictSince,
        )
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
                author?.let { "von $it" },
                pull?.get("head")?.text("ref")?.let { "⎇ $it" },
                "Konflikt".takeIf { conflict },
            ).joinToString(" · "),
            url = issue.text("html_url"),
            badges = statuses.sortedBy { it.text("context") }.map(::statusBadge),
            flags = flags(pullRequest, login, Instant.now()),
        )
    }

    /**
     * What [flags] needs to know of a pull request; [approvers] approved its head commit,
     * [requested] are asked for their review, [automerge] tells that Gitea merges it once its checks pass,
     * [updated] is its last push or other change, and [conflictSince] tells since when a conflict waits for the agent.
     */
    internal data class PullRequest(
        val own: Boolean,
        val draft: Boolean,
        val conflict: Boolean,
        val statuses: List<JsonNode>,
        val approvers: List<String> = emptyList(),
        val requested: List<String> = emptyList(),
        val author: String? = null,
        val automerge: Boolean = false,
        val updated: Instant? = null,
        val conflictSince: Instant? = null,
    )

    /**
     * The users whose review is pending: Gitea removes a `REQUEST_REVIEW` once the user reviews.
     * The pull request's `requested_reviewers` cannot tell this, as it lists everyone who reviewed.
     */
    internal fun requestedReviewers(reviews: List<JsonNode>): List<String> =
        reviews.filter { it.text("state") == "REQUEST_REVIEW" }.mapNotNull { it["user"]?.text("login") }.distinct()

    /** Gitea's `mergeable` is false for each work in progress too; only for a ready pull request it tells a conflict. */
    internal fun conflictOf(pull: JsonNode?, draft: Boolean) = !draft && pull?.get("mergeable")?.asBoolean() == false

    /**
     * Since when a conflict waits for the agent: the later of its [head] commit and the first of the [mainCommits]
     * since the base of that head; Gitea tells only that a pull request conflicts, not since when.
     */
    internal fun conflictSince(head: Instant?, mainCommits: List<JsonNode>): Instant? {
        val mainLeft = mainCommits.mapNotNull { it.commitTime() }.minOrNull() ?: return null
        return listOfNotNull(head, mainLeft).max()
    }

    /** [conflictSince] of an agent's pull request; two requests, so only for one that conflicts. */
    private fun conflictSince(repo: String, pull: JsonNode, headers: Map<String, String>): Instant? = runCatching {
        val head = pull["head"]?.text("sha") ?: return null
        val base = pull["base"]?.text("sha") ?: return null
        val headTime = http.get("$api/repos/$repo/git/commits/$head?stat=false&files=false&verification=false", headers).commitTime()
        conflictSince(headTime, http.get("$api/repos/$repo/compare/$head...$base", headers)["commits"]?.toList().orEmpty())
    }.getOrNull()

    private fun JsonNode.commitTime(): Instant? =
        (get("commit")?.get("committer")?.text("date") ?: text("created"))?.let(::instant)

    /** The last word on a scheduled merge in the timeline of a pull request, oldest event first. */
    internal fun automergeScheduled(timeline: List<JsonNode>): Boolean =
        timeline.lastOrNull { it.text("type") in AUTOMERGE_EVENTS }?.text("type") == "pull_scheduled_merge"

    /** What the pull request asks of whom, most urgent first; nothing for a draft. */
    internal fun flags(pr: PullRequest, login: String, now: Instant): List<Flag> {
        if (pr.draft) return emptyList()
        val gate = pr.statuses.firstOrNull { it.text("context") == GATE }
        val assessment = pr.statuses.firstOrNull { it.text("context") == ASSESSMENT }
        val reviewed = gate != null || assessment != null
        // werkrevisor sets its first status some seconds after a push; until then it counts as running
        val starting = !reviewed && pr.updated != null && Duration.between(pr.updated, now) < REVIEW_START
        val running = gate?.text("status") == "pending" || starting
        val verdict = assessment?.let(::verdictOf)
        val humans = pr.approvers.filter { approver -> config.reviewBots.none { it.equals(approver, ignoreCase = true) } }
        // a human has to look: no werkrevisor reviews the PR, or its review has finished with YELLOW or ORANGE
        val humanNeeded = !reviewed && !starting || !running && verdict in setOf("YELLOW", "ORANGE")
        // werkrevisor requests the review again when an approval came before its result; Gitea keeps that approval
        val askedForReview = login in pr.requested
        val approved = login in pr.approvers && !askedForReview
        val werkrevisor = if (reviewed) "werkrevisor $verdict" else "kein werkrevisor"
        val failedBuilds = pr.statuses
            .filter { it.text("context")?.startsWith(REVIEW_PREFIX) == false && it.text("status") in setOf("failure", "error") }
            .mapNotNull { it.text("context") }
        // an agent's pull request that passed everything waits for the user alone, unless Gitea merges it
        val approvedBy = pr.approvers.filter { it != pr.author }
        val mergeable = !pr.own && approvedBy.isNotEmpty() && !pr.conflict && !pr.automerge &&
            pr.statuses.isNotEmpty() && pr.statuses.all { it.text("status") == "success" }
        return buildList {
            if (reviewed && !running) stuckReason(verdict, assessment, now)?.let { add(Flag("stuck", it)) }
            val conflicting = pr.conflictSince?.let { Duration.between(it, now) }?.takeIf { !pr.own && pr.conflict && it >= CONFLICT_AFTER }
            if (conflicting != null) add(Flag("stuck", "Konflikt seit ${duration(conflicting)} ohne neuen Commit"))
            if (pr.own && pr.conflict) add(Flag("rework", "Konflikt"))
            if (pr.own && failedBuilds.isNotEmpty()) add(Flag("rework", "Build rot: ${failedBuilds.joinToString(", ")}"))
            // a requested review waits for werkrevisor's result too: only then can the user approve the head
            if (!pr.own && !approved && humanNeeded) add(Flag("approve", werkrevisor))
            if (mergeable && isEmpty()) add(Flag("merge", "approved von ${approvedBy.joinToString(", ")}"))
            val others = humans.filter { it != login }
            if (pr.own && others.isNotEmpty()) add(Flag("merge", "approved von ${others.joinToString(", ")}"))
            if (pr.own && others.isEmpty() && humanNeeded) {
                val asked = pr.requested.filter { it != login }
                add(Flag("review", if (asked.isEmpty()) "niemand angefragt" else "angefragt: ${asked.joinToString(", ")}"))
            }
            if (!pr.own && approved && none { it.kind == "merge" }) add(Flag("approved"))
        }
    }

    /** Agent and werkrevisor do not get on: werkrevisor escalated, RED stays without a new commit, or the review failed. */
    private fun stuckReason(verdict: String?, assessment: JsonNode?, now: Instant): String? {
        val since = assessment?.let { it.text("updated_at") ?: it.text("created_at") }?.let(::instant)
        return when (verdict) {
            null -> "werkrevisor ohne Urteil"
            "ERROR" -> "werkrevisor ERROR"
            "RED" -> when {
                assessment?.text("description")?.contains("escalated to") == true -> "RED, an Menschen eskaliert"
                since != null && Duration.between(since, now) >= STUCK_AFTER ->
                    "RED seit ${duration(Duration.between(since, now))} ohne neuen Commit"
                else -> null
            }
            else -> null
        }
    }

    private fun duration(duration: Duration) = when {
        duration.toHours() < 1 -> "${duration.toMinutes()} min"
        duration.toDays() < 1 -> "${duration.toHours()} h"
        else -> "${duration.toDays()} d"
    }

    /** An approval of this very head commit that is neither stale nor dismissed. */
    private fun JsonNode.isApprovalOf(sha: String?) =
        text("state") == "APPROVED" && text("commit_id") == sha &&
            get("stale")?.asBoolean() != true && get("dismissed")?.asBoolean() != true

    /** werkrevisor's verdict in its assessment status: named in the text, or else told by the commit status. */
    private fun verdictOf(status: JsonNode): String? =
        status.text("description")?.let { VERDICT.find(it)?.value }
            ?: status.text("description")?.let { text -> VERDICT_TEXTS.entries.firstOrNull { text.startsWith(it.key) }?.value }
            ?: VERDICT_STATES[status.text("status")]

    internal fun statusBadge(status: JsonNode): Badge {
        val context = status.text("context") ?: "?"
        val description = status.text("description")
        val verdict = if (context == ASSESSMENT) verdictOf(status) else description?.let { VERDICT.find(it)?.value }
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
        private const val GATE = "ai-review/gate"
        private const val ASSESSMENT = "ai-review/assessment"
        // in the agents' ping-pong a fix for RED never takes longer
        private val STUCK_AFTER: Duration = Duration.ofHours(1)
        // an agent that watches its pull request rebases it within this time
        private val CONFLICT_AFTER: Duration = Duration.ofMinutes(30)
        // werkrevisor's first status came 6 to 10 s after a push or the end of WIP
        private val REVIEW_START: Duration = Duration.ofMinutes(2)
        // werkrevisor's report.STATUS_TEXTS and STATUS_STATES
        private val VERDICT_TEXTS = mapOf(
            "AI review passed" to "GREEN",
            "human review required" to "YELLOW",
            "AI findings" to "ORANGE",
            "rule violated" to "RED",
            "review not completed" to "ERROR",
        )
        private val VERDICT_STATES = mapOf(
            "success" to "GREEN", "pending" to "YELLOW", "warning" to "ORANGE", "failure" to "RED", "error" to "ERROR",
        )
        private val REVIEW_ASPECTS = mapOf("gate" to "Lauf", "assessment" to "Urteil")
        private val VERDICT = Regex("\\b(GREEN|YELLOW|ORANGE|RED|ERROR)\\b")
        private val DRAFT_PREFIX = Regex("^\\s*(WIP|\\[WIP]|Draft)\\b", RegexOption.IGNORE_CASE)
        private val AUTOMERGE_EVENTS = setOf("pull_scheduled_merge", "pull_cancel_scheduled_merge")
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
