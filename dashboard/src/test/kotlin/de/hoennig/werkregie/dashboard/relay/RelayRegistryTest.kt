package de.hoennig.werkregie.dashboard.relay

import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.RelayReport
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.SourceStatus
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class RelayRegistryTest : FunSpec({
    val now = Instant.parse("2026-10-09T12:00:00Z")
    val since = now.minus(Duration.ofDays(1))

    fun registry(file: java.nio.file.Path = Files.createTempDirectory("state").resolve("relays.json")) =
        RelayRegistry(file, mapper(), stale = Duration.ofSeconds(30), gone = Duration.ofSeconds(120))

    fun report(relay: String = "laptop") = RelayReport(
        relay = relay,
        version = "0.2.0",
        sentAt = now,
        items = listOf(
            Item("claude", "Desktop", "session", Section.RUNNING, "Working", "busy", now),
            Item("codex", "CLI", "session", Section.RECENT, "Done", "done", now.minusSeconds(600)),
        ),
        sources = listOf(
            SourceStatus("claude", "Claude Code", true, null, null, 1, now),
            SourceStatus("codex", "Codex", false, "state_5.sqlite nicht gefunden", null, 0, now),
        ),
    )

    test("names every item after the relay that reported it") {
        val relays = registry().apply { accept(report(), now) }

        relays.fetch(now, since).map { it.origin } shouldContainExactly listOf("laptop · Desktop", "laptop · CLI")
    }

    test("reports a chip per relay with the tools that work and the ones that fail") {
        val status = registry().apply { accept(report(), now) }.statuses(now).single()

        status.id shouldBe "relay:laptop"
        status.tools shouldBe listOf("claude")
        status.message shouldBe "Codex: state_5.sqlite nicht gefunden"
    }

    test("a silent relay turns stale, and after a while its running sessions count as ended") {
        val relays = registry().apply { accept(report(), now) }

        relays.statuses(now.plusSeconds(45)).single().message shouldBe "zuletzt vor 45 s"
        relays.fetch(now.plusSeconds(45), since).first().section shouldBe Section.RUNNING
        relays.fetch(now.plusSeconds(180), since).first().let { it.section to it.state } shouldBe (Section.RECENT to "closed")
    }

    test("forgets a relay for good, also in the file") {
        val file = Files.createTempDirectory("state").resolve("relays.json")
        val relays = registry(file).apply { accept(report("old"), now); accept(report("new"), now) }

        relays.forget("old") shouldBe true
        relays.forget("old") shouldBe false
        registry(file).statuses(now).map { it.id } shouldContainExactly listOf("relay:new")
    }

    test("keeps the last reports across a restart") {
        val file = Files.createTempDirectory("state").resolve("relays.json")
        registry(file).apply { accept(report(), now) }.save(now)

        registry(file).fetch(now, since).map { it.title } shouldContainExactly listOf("Working", "Done")
    }
})
