package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.Flag
import de.hoennig.werkregie.dashboard.GiteaConfig
import de.hoennig.werkregie.dashboard.JsonHttp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GiteaSourceTest : FunSpec({
    val mapper = ObjectMapper()
    val source = GiteaSource(GiteaConfig("javagil", "https://git.example"), JsonHttp(mapper), mapper) { null }

    fun status(context: String, state: String, description: String) = mapper.readTree(
        """{"context":"$context","status":"$state","description":"$description","target_url":"https://w.example/b/1"}""",
    )

    test("the werkrevisor gate shows its verdict") {
        source.statusBadge(status("ai-review/gate", "failure", "RED CHANGES_REQUIRED: open question")) shouldBe
            Badge("werkrevisor Lauf RED", "red", "https://w.example/b/1", "RED CHANGES_REQUIRED: open question")
    }

    test("the werkrevisor assessment is its verdict, told by werkrevisor's status text") {
        source.statusBadge(status("ai-review/assessment", "failure", "rule violated; a new commit that fixes it is required"))
            .let { it.label to it.state } shouldBe ("werkrevisor Urteil RED" to "red")
        source.statusBadge(status("ai-review/assessment", "pending", "human review required; a human approval unlocks the merge"))
            .let { it.label to it.state } shouldBe ("werkrevisor Urteil YELLOW" to "yellow")
    }

    test("without a known text the commit status tells the verdict") {
        source.statusBadge(status("ai-review/assessment", "warning", "something new")).label shouldBe "werkrevisor Urteil ORANGE"
    }

    val now = Instant.parse("2026-10-10T12:00:00Z")
    fun at(context: String, state: String, description: String, minutesAgo: Long) = mapper.readTree(
        """{"context":"$context","status":"$state","description":"$description","updated_at":"${now.minusSeconds(minutesAgo * 60)}"}""",
    )
    val gateDone = status("ai-review/gate", "success", "build succeeded")
    fun foreign(vararg statuses: JsonNode, approvers: List<String> = emptyList()) =
        GiteaSource.PullRequest(own = false, draft = false, conflict = false, statuses = statuses.toList(), approvers = approvers)
    fun own(vararg statuses: JsonNode, conflict: Boolean = false, approvers: List<String> = emptyList(), requested: List<String> = emptyList()) =
        GiteaSource.PullRequest(own = true, draft = false, conflict = conflict, statuses = statuses.toList(), approvers = approvers, requested = requested)
    fun flags(pr: GiteaSource.PullRequest) = source.flags(pr, "mi", now)

    test("a foreign pull request without werkrevisor waits for the user's approval") {
        flags(foreign(status("werkator", "success", "build succeeded"))) shouldBe listOf(Flag("approve", "kein werkrevisor"))
    }

    test("a final verdict of YELLOW or ORANGE waits for the user's approval") {
        flags(foreign(gateDone, status("ai-review/assessment", "pending", "human review required"))) shouldBe
            listOf(Flag("approve", "werkrevisor YELLOW"))
        flags(foreign(gateDone, status("ai-review/assessment", "warning", "AI findings"))) shouldBe
            listOf(Flag("approve", "werkrevisor ORANGE"))
    }

    test("nothing to do while werkrevisor reviews or after it passed the pull request") {
        flags(foreign(status("ai-review/gate", "pending", "running"), status("ai-review/assessment", "failure", "rule violated"))) shouldBe emptyList()
        flags(foreign(gateDone, status("ai-review/assessment", "success", "AI review passed"))) shouldBe emptyList()
    }

    test("a pull request the user approved is marked so, and no longer asks for it") {
        flags(foreign(approvers = listOf("mi"))) shouldBe listOf(Flag("approved"))
    }

    test("a fresh RED is the agent's to fix") {
        flags(foreign(gateDone, at("ai-review/assessment", "failure", "rule violated", minutesAgo = 20))) shouldBe emptyList()
    }

    test("RED without a new commit for an hour is stuck") {
        flags(foreign(gateDone, at("ai-review/assessment", "failure", "rule violated", minutesAgo = 150))) shouldBe
            listOf(Flag("stuck", "RED seit 2 h ohne neuen Commit"))
    }

    test("RED werkrevisor escalated to the humans is stuck at once") {
        flags(foreign(gateDone, at("ai-review/assessment", "failure", "rule violated; escalated to mi", minutesAgo = 1))) shouldBe
            listOf(Flag("stuck", "RED, an Menschen eskaliert"))
    }

    test("a failed review is stuck") {
        flags(foreign(gateDone, status("ai-review/assessment", "error", "review not completed, human action needed"))) shouldBe
            listOf(Flag("stuck", "werkrevisor ERROR"))
        flags(foreign(status("ai-review/gate", "failure", "build failed after 00:56"))) shouldBe
            listOf(Flag("stuck", "werkrevisor ohne Urteil"))
    }

    test("the user's own pull request waits for another human") {
        flags(own()) shouldBe listOf(Flag("review", "niemand angefragt"))
        flags(own(requested = listOf("anna"))) shouldBe listOf(Flag("review", "angefragt: anna"))
    }

    test("the user's own pull request another human approved is the user's to merge; a bot's approval is no human's") {
        flags(own(approvers = listOf("anna"))) shouldBe listOf(Flag("merge", "approved von anna"))
        flags(own(approvers = listOf("ai-reviewer"))) shouldBe listOf(Flag("review", "niemand angefragt"))
    }

    test("a conflict or a red build on the user's own pull request is rework, besides waiting for review") {
        flags(own(status("werkator", "failure", "build failed"), conflict = true)) shouldBe listOf(
            Flag("rework", "Konflikt"),
            Flag("rework", "Build rot: werkator"),
            Flag("review", "niemand angefragt"),
        )
    }

    test("a foreign conflict is the agent's, a draft asks nothing") {
        flags(GiteaSource.PullRequest(own = false, draft = false, conflict = true, statuses = listOf(gateDone, status("ai-review/assessment", "success", "AI review passed")))) shouldBe emptyList()
        flags(GiteaSource.PullRequest(own = true, draft = true, conflict = true, statuses = emptyList())) shouldBe emptyList()
    }
})
