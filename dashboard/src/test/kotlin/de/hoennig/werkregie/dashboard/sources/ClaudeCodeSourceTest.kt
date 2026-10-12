package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.Flag
import de.hoennig.werkregie.dashboard.JevConfig
import de.hoennig.werkregie.dashboard.Section
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
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

    /** Desktop sessions with live processes and transcripts, for the markers of sessions that wait for the user. */
    class Sessions {
        val root: Path = Files.createTempDirectory("claude")
        private val desktop = Files.createDirectories(root.resolve("desktop/account/workspace"))
        private val live = Files.createDirectories(root.resolve("live"))
        private val projects = Files.createDirectories(root.resolve("projects/-home-u-Projekte-notes"))
        val alive = mutableSetOf<Long>()
        private var pid = 0L
        private var uuid = 0

        fun session(id: String, status: String? = null, waitingFor: String? = null, archived: Boolean = false, transcript: List<String> = emptyList()) {
            Files.writeString(
                desktop.resolve("local_$id.json"),
                """{"sessionId":"local_$id","cliSessionId":"cli-$id","cwd":"/home/u/Projekte/notes","title":"$id",
                   "lastActivityAt":${now.toEpochMilli()},"isArchived":$archived}""",
            )
            if (status != null) {
                alive += ++pid
                Files.writeString(
                    live.resolve("$pid.json"),
                    """{"pid":$pid,"sessionId":"cli-$id","hostSessionId":"local_$id","cwd":"/home/u/Projekte/notes","status":"$status",
                       "updatedAt":${now.toEpochMilli()},"procStart":"1"${waitingFor?.let { ",\"waitingFor\":\"$it\"" } ?: ""}}""",
                )
            }
            if (transcript.isNotEmpty()) Files.writeString(projects.resolve("cli-$id.jsonl"), transcript.joinToString("\n"))
        }

        fun claude(text: String) =
            """{"type":"assistant","uuid":"u${++uuid}","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

        fun ask(id: String) =
            """{"type":"assistant","uuid":"u${++uuid}","message":{"content":[{"type":"tool_use","id":"$id","name":"AskUserQuestion","input":{}}]}}"""

        fun answer(id: String) =
            """{"type":"user","uuid":"u${++uuid}","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":"A"}]}}"""

        fun user(text: String) = """{"type":"user","uuid":"u${++uuid}","message":{"role":"user","content":"$text"}}"""

        fun source(judge: Judge? = null, summaries: Summaries = Summaries(null, null, ObjectMapper())) = ClaudeCodeSource(
            ClaudeConfig(
                desktopDir = root.resolve("desktop").toString(),
                liveDir = live.toString(),
                projectsDir = root.resolve("projects").toString(),
                jev = judge?.let { JevConfig(apiKey = "k") },
            ),
            ObjectMapper(),
            judge,
            summaries = summaries,
        ) { pid, _ -> pid in alive }
    }

    fun List<de.hoennig.werkregie.dashboard.Item>.asks() = associate { it.title to it.flags }

    test("a session that waits for the user asks for an answer") {
        val sessions = Sessions().apply {
            session("question", status = "waiting", waitingFor = "input needed")
            session("permission", status = "waiting", waitingFor = "dialog open")
            session("sandbox", status = "waiting", waitingFor = "sandbox request")
            session("working", status = "busy", transcript = listOf(ask("t1")))
        }

        sessions.source().fetch(now, since).asks() shouldBe mapOf(
            "question" to listOf(Flag("ask", "Frage offen")),
            "permission" to listOf(Flag("ask", "Dialog offen")),
            "sandbox" to listOf(Flag("ask", "sandbox request")),
            "working" to emptyList(),
        )
    }

    test("an unanswered question in the transcript asks for an answer") {
        val sessions = Sessions().apply {
            session("closed", transcript = listOf(claude("Welche Variante?"), ask("t1")))
            session("answered", transcript = listOf(ask("t1"), answer("t1"), claude("Erledigt.")))
            session("archived", archived = true, transcript = listOf(ask("t1")))
        }

        sessions.source().fetch(now, since).asks() shouldBe mapOf(
            "closed" to listOf(Flag("ask", "Frage offen", question = "Welche Variante?")),
            "answered" to emptyList(),
            "archived" to emptyList(),
        )
    }

    test("Jev decides on the last message of an idle session") {
        val sessions = Sessions().apply {
            session("asks", status = "idle", transcript = listOf(user("Los"), claude("Soll ich den PR anlegen?")))
            session("reports", status = "idle", transcript = listOf(user("Los"), claude("Der PR ist gemergt.")))
        }
        val judge = Judge { message -> if (message.endsWith("?")) 0.9 else 0.3 }

        sessions.source(judge).fetch(now, since).asks() shouldBe mapOf(
            "asks" to listOf(Flag("ask", "wartet auf dich", prompt = "Los", question = "Soll ich den PR anlegen?")),
            "reports" to emptyList(),
        )
        sessions.source(judge = null).fetch(now, since).asks() shouldBe mapOf("asks" to emptyList(), "reports" to emptyList())
    }

    test("only the last message of Claude is rated, once") {
        val sessions = Sessions().apply {
            session("claude", status = "idle", transcript = listOf(claude("Soll ich?")))
            session("user", status = "idle", transcript = listOf(claude("Soll ich?"), user("Ja, bitte.")))
        }
        val rated = mutableListOf<String>()
        val source = sessions.source(judge = { message -> rated += message; 0.9 })

        source.fetch(now, since)
        source.fetch(now.plusSeconds(2), since)

        rated shouldBe listOf("Soll ich?")
    }

    test("a waiting session carries the last input and Claude's question") {
        val sessions = Sessions().apply {
            session("asks", status = "idle", transcript = listOf(user("Baue den Export"), claude("Soll ich den PR anlegen?")))
            session("long", status = "waiting", waitingFor = "input needed", transcript = listOf(user("x".repeat(400)), ask("t1")))
        }
        val summaries = Summaries({ _, _ -> "Kurz" }, null, ObjectMapper())

        sessions.source({ 0.9 }, summaries).fetch(now, since).asks() shouldBe mapOf(
            "asks" to listOf(Flag("ask", "wartet auf dich", prompt = "Baue den Export", question = "Soll ich den PR anlegen?")),
            "long" to listOf(Flag("ask", "Frage offen", prompt = "Kurz")),
        )
    }

    test("a failing Jev is noted and asked again only after a minute") {
        val sessions = Sessions().apply { session("asks", status = "idle", transcript = listOf(claude("Soll ich?"))) }
        var calls = 0
        val source = sessions.source(judge = { calls++; throw IllegalStateException("HTTP 503") })

        source.fetch(now, since).asks() shouldBe mapOf("asks" to emptyList())
        source.fetch(now.plusSeconds(30), since)
        calls shouldBe 1
        source.note() shouldBe "Jev: HTTP 503"
        source.fetch(now.plusSeconds(61), since)
        calls shouldBe 2
    }
})

