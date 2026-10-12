package de.hoennig.werkregie.dashboard.journal

import de.hoennig.werkregie.dashboard.Dashboard
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class JournalArchiveTest : FunSpec({
    val berlin = ZoneId.of("Europe/Berlin")
    val today = LocalDate.parse("2026-10-12")

    // local time in Berlin, which is UTC+2 in October
    fun at(time: String, day: LocalDate = today) = day.atTime(java.time.LocalTime.parse(time)).atZone(berlin).toInstant()

    fun archive(dir: Path = Files.createTempDirectory("journal"), keepDays: Long = 400) = JournalArchive(dir, berlin, keepDays, mapper())

    fun session(vararg spans: Span) = Activity("claude", "s1", "think · cli", "Journal", "/home/mi/werkregie", "werkregie", "werkregie", listOf("main"), spans.toList())

    fun item(source: String, kind: String, title: String, time: Instant, state: String = "done", url: String? = null) =
        Item(source, "javagil", kind, Section.RECENT, title, state, time, project = "mi/werkregie", url = url)

    test("a span over midnight falls into the files of both days") {
        val dir = Files.createTempDirectory("journal")
        archive(dir).record(at("23:40"), emptyList(), listOf(session(Span(at("23:30"), at("00:20", today.plusDays(1))))))

        val reread = archive(dir)
        reread.day(today).sessions.single().spans shouldContainExactly listOf(Span(at("23:30"), at("00:00", today.plusDays(1))))
        reread.day(today.plusDays(1)).sessions.single().spans shouldContainExactly
            listOf(Span(at("00:00", today.plusDays(1)), at("00:20", today.plusDays(1))))
        Files.exists(dir.resolve("2026-10-12.json")) shouldBe true
    }

    test("a recorded span stays when the relay no longer reports it") {
        val dir = Files.createTempDirectory("journal")
        archive(dir).record(at("09:30"), emptyList(), listOf(session(Span(at("09:00"), at("09:20")))))

        archive(dir).record(at("10:30"), emptyList(), listOf(session(Span(at("10:00"), at("10:30"))).copy(branches = listOf("x"))))

        archive(dir).day(today).sessions.single().let {
            it.spans shouldContainExactly listOf(Span(at("09:00"), at("09:20")), Span(at("10:00"), at("10:30")))
            it.branches shouldContainExactly listOf("main", "x")
        }
    }

    test("keeps the items of each day, a later state replacing the earlier one, the sessions apart") {
        val archive = archive()
        val build = "https://werkator.javagil.de/builds/7"
        archive.record(at("12:00"), listOf(
            item("gitea", "activity", "merge PR #41 Journal", at("10:00"), "merge_pull_request", "https://git.javagil.de/mi/werkregie/pulls/41"),
            item("gitea", "activity", "comment PR #41 Journal", at("10:05"), "comment_pull", "https://git.javagil.de/mi/werkregie/pulls/41"),
            item("werkator", "build", "claude-code/journal-archive", at("10:10"), "running", build),
            item("werkrevisor", "review", "PR #41", at("10:20")),
            item("taiga", "task", "Task #12 Journal", at("10:30"), "In Arbeit"),
            item("matrix", "message", "Kurze Frage", at("10:40"), "unread"),
            item("claude", "session", "Journal", at("10:50"), "idle"),
            item("gitea", "pr", "yesterday", at("23:00", today.minusDays(1)), "open"),
        ), emptyList())

        archive.record(at("12:01"), listOf(item("werkator", "build", "claude-code/journal-archive", at("10:15"), "success", build)), emptyList())

        archive.day(today).items.map { it.source to it.state } shouldContainExactly listOf(
            "gitea" to "merge_pull_request",
            "gitea" to "comment_pull",
            "werkator" to "success",
            "werkrevisor" to "done",
            "taiga" to "In Arbeit",
            "matrix" to "unread",
        )
        archive.day(today.minusDays(1)).items.map { it.title } shouldContainExactly listOf("yesterday")
    }

    test("deletes the days older than keepDays") {
        val dir = Files.createTempDirectory("journal")
        Files.writeString(dir.resolve("${today.minusDays(401)}.json"), "{}")
        Files.writeString(dir.resolve("${today.minusDays(400)}.json"), "{}")
        Files.writeString(dir.resolve("notes.txt"), "kept")

        archive(dir, keepDays = 400).record(at("12:00"), listOf(item("gitea", "pr", "now", at("11:00"))), emptyList())

        Files.list(dir).use { files -> files.map { it.fileName.toString() }.sorted().toList() } shouldContainExactly
            listOf("${today.minusDays(400)}.json", "2026-10-12.json", "notes.txt")
    }

    test("the recorder fills the archive every interval, without anybody asking for the page") {
        val source = object : Source {
            override val id = "gitea"
            override val label = "Gitea"
            override val ttl: Duration = Duration.ZERO
            override fun fetch(now: Instant, since: Instant) = listOf(item("gitea", "pr", "open PR", now))
        }
        val archive = archive()
        val recorder = JournalRecorder(
            archive, Dashboard(listOf(source), windowHours = 24),
            { _, _ -> listOf(session(Span(Instant.now(), Instant.now()))) },
            windowHours = 24, interval = Duration.ofMillis(20), log = {},
        )

        val thread = recorder.start()
        val deadline = Instant.now().plusSeconds(5)
        val day = { archive.day(LocalDate.now(berlin)) }
        while ((day().items.isEmpty() || day().sessions.isEmpty()) && Instant.now().isBefore(deadline)) Thread.sleep(20)
        thread.interrupt()

        day().items.map { it.title } shouldContainExactly listOf("open PR")
        day().sessions.map { it.session } shouldContainExactly listOf("s1")
    }
})
