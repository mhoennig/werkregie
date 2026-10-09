package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.WerkatorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class WerkatorSourceTest : FunSpec({
    val mapper = ObjectMapper()
    val source = WerkatorSource(WerkatorConfig("javagil", "https://werkator.example/"), JsonHttp(mapper))
    val now = Instant.parse("2026-10-09T12:00:00Z")
    val since = now.minus(Duration.ofDays(7))

    fun build(name: String, status: String, startedAt: Instant) = mapper.readTree(
        """{"name":"$name","status":"$status","startedAt":"$startedAt","commit":"0123456789abcdef",
           "artifactKey":"key-1","durationSeconds":56,"pullRequest":{"number":130,"url":"https://git.example/pulls/130"}}""",
    )

    test("an ai-review build is a werkrevisor run of the PR branch") {
        val item = source.item("werkator", "werkator", build("feature@ai-review", "success", now.minus(Duration.ofHours(1))), now, since)!!

        item.source shouldBe "werkrevisor"
        item.title shouldBe "feature"
        item.section shouldBe Section.RECENT
        item.url shouldBe "https://werkator.example/repos/werkator/builds/key-1"
        item.badges.single().label shouldBe "PR #130"
    }

    test("a recent pending build is running, an old one is stale") {
        source.item("r", "r", build("b", "pending", now.minus(Duration.ofMinutes(5))), now, since)!!.section shouldBe Section.RUNNING
        source.item("r", "r", build("b", "pending", now.minus(Duration.ofDays(2))), now, since)!!.state shouldBe "stale"
    }

    test("builds before the window are left out") {
        source.item("r", "r", build("b", "success", now.minus(Duration.ofDays(8))), now, since) shouldBe null
    }
})
