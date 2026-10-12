package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class PullRequestWatchTest : FunSpec({
    val mapper = ObjectMapper()
    fun pr(title: String = "Feature", state: String = "open", merged: Boolean = false, mergeable: Boolean = true, mergeBase: String = "main1") =
        mapper.readTree(
            """{"title":"$title","state":"$state","merged":$merged,"mergeable":$mergeable,"merge_commit_sha":"0472cf9abc",
               "draft":${title.startsWith("WIP: ")},"base":{"sha":"main1"},"merge_base":"$mergeBase"}""",
        )
    fun status(vararg statuses: Triple<String, String, String>): JsonNode = mapper.readTree(
        mapper.writeValueAsString(mapOf("statuses" to statuses.map { (context, state, description) ->
            mapOf("context" to context, "status" to state, "description" to description)
        })),
    )
    val built = Triple("werkator", "success", "build succeeded")
    val gate = Triple("ai-review/gate", "success", "build succeeded after 03:28")

    test("the outcome of a pull request, in order of its states") {
        PullRequestWatch.outcome(pr(state = "closed", merged = true), null) shouldBe "MERGED 0472cf9"
        PullRequestWatch.outcome(pr(state = "closed"), null) shouldBe "CLOSED"
        PullRequestWatch.outcome(pr(mergeable = false), status(built)) shouldBe "CONFLICT"
        PullRequestWatch.outcome(pr(), status(Triple("werkator", "failure", "tests failed"))) shouldBe "FAILED werkator: tests failed"
        PullRequestWatch.outcome(pr(), status(built, gate, Triple("ai-review/assessment", "failure", "RED: open question in the PR document"))) shouldBe
            "REVIEW RED: open question in the PR document"
        PullRequestWatch.outcome(pr("WIP: Feature"), status(built)) shouldBe "READY"
        // Gitea's mergeable is false for every draft; only a merge base behind main counts there
        PullRequestWatch.outcome(pr("WIP: Feature", mergeable = false), status(built)) shouldBe "READY"
        PullRequestWatch.outcome(pr("WIP: Feature", mergeable = false, mergeBase = "main0"), status(built)) shouldBe "BEHIND"
        PullRequestWatch.outcome(pr("WIP: Feature"), status(Triple("werkator", "pending", "running"))) shouldBe null
        PullRequestWatch.outcome(pr(), status(built, gate, Triple("ai-review/assessment", "pending", "human review required; review requested from mi"))) shouldBe
            "REVIEW human review required; review requested from mi"
        PullRequestWatch.outcome(pr(), status(built, gate, Triple("ai-review/assessment", "warning", "AI findings; a developer approval unlocks the merge"))) shouldBe
            "REVIEW AI findings; a developer approval unlocks the merge"
        PullRequestWatch.outcome(pr(), status(built, gate, Triple("ai-review/assessment", "success", "AI review passed; merge waits for 2 open hint(s)"))) shouldBe
            "REVIEW AI review passed; merge waits for 2 open hint(s)"
        // still running, and a green review with the merge scheduled: nothing for the agent yet
        PullRequestWatch.outcome(pr(), status(built, Triple("ai-review/gate", "pending", "review running"))) shouldBe null
        PullRequestWatch.outcome(pr(), status(built, gate, Triple("ai-review/assessment", "success", "AI review passed; merge already scheduled"))) shouldBe null
    }

    test("a conflict counts only when Gitea reports it twice") {
        val reads = ArrayDeque(listOf(pr(mergeable = false), pr(), pr(mergeable = false), pr(mergeable = false)))
        var slept = 0
        val watch = PullRequestWatch({ reads.removeFirst() to status(built) }, Duration.ofSeconds(30), { slept++ })

        watch.run() shouldBe "CONFLICT"
        slept shouldBe 3
    }

    test("ten failed reads in a row end the watch") {
        var reads = 0
        val watch = PullRequestWatch({ reads++; throw IllegalStateException("HTTP 502") }, Duration.ofSeconds(30), {})

        shouldThrow<PullRequestWatch.Failed> { watch.run() }
        reads shouldBe 10
    }

    test("reads the address of a pull request") {
        PullRequestRef.parse("https://git.javagil.de/mi/werkregie/pulls/48") shouldBe PullRequestRef("https://git.javagil.de", "mi", "werkregie", 48)
        PullRequestRef.parse("https://git.javagil.de/mi/werkregie/issues/48") shouldBe null
    }
})
