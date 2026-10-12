package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.sources.text
import java.time.Duration

/** A pull request of a Gitea instance, by its address, e.g. https://git.example.org/owner/repo/pulls/7. */
data class PullRequestRef(val base: String, val owner: String, val repo: String, val number: Int) {
    val api: String get() = "$base/api/v1/repos/$owner/$repo"

    companion object {
        private val URL = Regex("""^(https?://[^/]+)/([^/]+)/([^/]+)/pulls/(\d+)/?$""")

        fun parse(url: String): PullRequestRef? =
            URL.matchEntire(url.trim())?.destructured?.let { (base, owner, repo, number) -> PullRequestRef(base, owner, repo, number.toInt()) }
    }
}

/**
 * `watch-pr`: checks a pull request until its coding agent must act, and names why in one line.
 * [check] reads the pull request and the combined status of its head; [sleep] waits between checks.
 */
class PullRequestWatch(
    private val check: () -> Pair<JsonNode, JsonNode?>,
    private val interval: Duration,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it) },
) {
    class Failed(message: String) : RuntimeException(message)

    fun run(): String {
        var conflictBefore = false
        var failures = 0
        while (true) {
            val read = runCatching(check)
            if (read.isFailure) {
                if (++failures >= MAX_FAILURES) throw Failed("$MAX_FAILURES Abfragen in Folge fehlgeschlagen: ${read.exceptionOrNull()?.message}")
            } else {
                failures = 0
                val (pr, status) = read.getOrThrow()
                val result = outcome(pr, status)
                // Gitea computes mergeable in the background after a push or a merge; one false may be stale
                if (result == CONFLICT && !conflictBefore) conflictBefore = true
                else if (result != null) return result
                else conflictBefore = false
            }
            sleep(interval)
        }
    }

    companion object {
        const val CONFLICT = "CONFLICT"
        private const val MAX_FAILURES = 10
        private val WAITING = setOf("pending", "warning")
        private val RED = setOf("failure", "error")
        private const val REVIEW_CONTEXT = "ai-review/"

        /** What the agent must do now, by the first that applies; null while nothing is due. */
        fun outcome(pr: JsonNode, status: JsonNode?): String? {
            if (pr["merged"]?.asBoolean() == true) return "MERGED ${pr.text("merge_commit_sha")?.take(7) ?: ""}".trim()
            if (pr.text("state") == "closed") return "CLOSED"
            // Gitea reports a draft, i.e. a `WIP: ` pull request, as never mergeable; only its merge base tells that main moved on
            val draft = pr["draft"]?.asBoolean() == true
            if (draft && pr["base"]?.text("sha")?.let { it != pr.text("merge_base") } == true) return "BEHIND"
            if (!draft && pr["mergeable"]?.isBoolean == true && !pr["mergeable"].asBoolean()) return CONFLICT
            val statuses = status?.get("statuses")?.toList().orEmpty()
            val (review, checks) = statuses.partition { it.text("context")?.startsWith(REVIEW_CONTEXT) == true }
            // a red build is the agent's to repair; a red verdict of werkrevisor, e.g. RED, is a review round
            checks.firstOrNull { it.text("status") in RED }
                ?.let { return "FAILED ${it.text("context")}: ${it.text("description") ?: ""}".trim() }
            review.firstOrNull { it.text("status") in RED }?.let { return "REVIEW ${it.text("description") ?: ""}".trim() }
            if (draft) {
                return if (statuses.isNotEmpty() && statuses.all { it.text("status") == "success" }) "READY" else null
            }
            val gate = statuses.firstOrNull { it.text("context") == "ai-review/gate" }
            val assessment = statuses.firstOrNull { it.text("context") == "ai-review/assessment" }
            if (gate == null || gate.text("status") in WAITING || assessment == null) return null
            val description = assessment.text("description") ?: ""
            // after the review only GREEN is a success, e.g. YELLOW is pending and ORANGE a warning;
            // a GREEN one still waits for the agent while open hints hold the merge, in werkrevisor's words
            val asks = assessment.text("status") != "success" || "open hint" in description
            return if (asks) "REVIEW $description" else null
        }

        /** The token of the `giteas` entry for the pull request's instance, as the dashboard resolves it. */
        fun token(config: DashboardConfig, ref: PullRequestRef, env: (String) -> String? = System::getenv): String? =
            config.giteas.firstOrNull { it.url.trimEnd('/') == ref.base }?.let { gitea ->
                gitea.token ?: gitea.tokenEnv?.let(env) ?: gitea.teaLogin?.let { ConfigLoader.teaToken(it, gitea.url) }
            }
    }
}
