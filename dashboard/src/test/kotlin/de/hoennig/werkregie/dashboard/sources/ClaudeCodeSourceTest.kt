package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.Section
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ClaudeCodeSourceTest : FunSpec({
    val now = Instant.parse("2026-10-09T12:00:00Z")
    val since = now.minus(Duration.ofDays(1))

    fun source(alivePids: Set<Long>): ClaudeCodeSource {
        val root = Files.createTempDirectory("claude")
        val desktop = Files.createDirectories(root.resolve("desktop/account/workspace"))
        val live = Files.createDirectories(root.resolve("live"))
        fun desktopSession(id: String, title: String, lastActivity: Instant, archived: Boolean = false) =
            Files.writeString(
                desktop.resolve("local_$id.json"),
                """{"sessionId":"local_$id","cliSessionId":"cli-$id","cwd":"/home/u/Projekte/werkator/.claude/worktrees/x-1",
                   "title":"$title","model":"claude-opus-5-5","lastActivityAt":${lastActivity.toEpochMilli()},"isArchived":$archived}""",
            )
        fun liveProcess(pid: Long, sessionId: String, hostSessionId: String?, status: String) =
            Files.writeString(
                live.resolve("$pid.json"),
                """{"pid":$pid,"sessionId":"$sessionId","cwd":"/home/u/Projekte/notes","status":"$status",
                   "updatedAt":${now.toEpochMilli()},"procStart":"1"${hostSessionId?.let { ",\"hostSessionId\":\"$it\"" } ?: ""}}""",
            )
        desktopSession("busy", "Working", now.minus(Duration.ofHours(2)))
        desktopSession("idle", "Waiting", now.minus(Duration.ofHours(3)))
        desktopSession("closed", "Finished", now.minus(Duration.ofHours(4)))
        desktopSession("old", "Ancient", now.minus(Duration.ofDays(5)))
        liveProcess(1, "cli-busy", "local_busy", "busy")
        liveProcess(2, "cli-idle", "local_idle", "idle")
        liveProcess(3, "cli-closed", "local_closed", "busy") // process gone
        liveProcess(4, "terminal", null, "idle")
        return ClaudeCodeSource(
            ClaudeConfig(desktopDir = desktop.parent.parent.toString(), liveDir = live.toString()),
            ObjectMapper(),
        ) { pid, _ -> pid in alivePids }
    }

    test("joins Desktop sessions with live processes into running, open and recent") {
        val items = source(alivePids = setOf(1, 2, 4)).fetch(now, since)

        items.map { Triple(it.title, it.section, it.state) } shouldContainExactlyInAnyOrder listOf(
            Triple("Working", Section.RUNNING, "busy"),
            Triple("Waiting", Section.OPEN, "idle"),
            Triple("Finished", Section.RECENT, "closed"),
            Triple("notes", Section.OPEN, "idle"),
        )
    }

    test("names the project of a worktree after its repository") {
        val working = source(alivePids = setOf(1)).fetch(now, since).first { it.title == "Working" }

        working.project shouldBe "werkator"
        working.detail shouldBe "claude-opus-5-5 · worktree x-1"
        working.command shouldBe "cd '/home/u/Projekte/werkator/.claude/worktrees/x-1' && claude --resume cli-busy"
    }

    test("a Desktop session links into Claude Desktop, a terminal session does not") {
        val items = source(alivePids = setOf(1, 4)).fetch(now, since)

        items.first { it.title == "Working" }.url shouldBe "claude://code/continue?session=local_busy"
        items.first { it.title == "notes" }.url shouldBe null
    }
})
