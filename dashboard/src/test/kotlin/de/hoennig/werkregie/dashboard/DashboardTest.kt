package de.hoennig.werkregie.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class DashboardTest : FunSpec({
    val now = Instant.parse("2026-10-09T12:00:00Z")

    fun item(title: String, minutesAgo: Long) =
        Item("codex", "CLI", "session", Section.RECENT, title, "done", now.minus(Duration.ofMinutes(minutesAgo)))

    class FakeSource(override val id: String, val result: () -> List<Item>) : Source {
        var calls = 0
        override val label = id
        override val ttl: Duration = Duration.ofMinutes(1)
        override fun fetch(now: Instant, since: Instant) = result().also { calls++ }
    }

    test("merges all sources newest first and reports a failing one without losing the others") {
        val good = FakeSource("good") { listOf(item("older", 30), item("newer", 5)) }
        val bad = FakeSource("bad") { error("unreachable") }

        val snapshot = Dashboard(listOf(good, bad), windowHours = 24).snapshot(now)

        snapshot.items.map { it.title } shouldContainExactly listOf("newer", "older")
        snapshot.sources.map { it.id to it.ok } shouldContainExactly listOf("good" to true, "bad" to false)
        snapshot.sources.last().message shouldBe "unreachable"
    }

    test("serves from the cache within the ttl") {
        val source = FakeSource("s") { listOf(item("a", 1)) }
        val dashboard = Dashboard(listOf(source), windowHours = 24)

        dashboard.snapshot(now)
        dashboard.snapshot(now.plusSeconds(30))

        source.calls shouldBe 1
    }
})
