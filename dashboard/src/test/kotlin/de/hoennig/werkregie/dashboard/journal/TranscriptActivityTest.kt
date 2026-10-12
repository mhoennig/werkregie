package de.hoennig.werkregie.dashboard.journal

import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.CodexConfig
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

class TranscriptActivityTest : FunSpec({
    val now = Instant.parse("2026-10-12T18:00:00Z")
    val since = now.minus(Duration.ofDays(7))

    fun at(time: String) = Instant.parse("2026-10-12T${time}:00Z")

    fun entry(time: String, vararg fields: Pair<String, String>) =
        (listOf("type" to "assistant", "timestamp" to "${at(time)}") + fields)
            .joinToString(",", "{", "}") { (key, value) -> "\"$key\":\"$value\"" }
            // a large nested object, as a tool result is, must not disturb the top-level fields
            .replaceFirst("{", """{"message":{"content":[{"timestamp":"1999-01-01T00:00:00Z","cwd":"/nested"}]},""")

    fun claudeHome(): Path = Files.createTempDirectory("claude")

    fun transcript(home: Path, session: String, vararg lines: String, project: String = "-home-mi-werkregie"): Path =
        home.resolve(project).resolve("$session.jsonl").also { file ->
            Files.createDirectories(file.parent)
            Files.writeString(file, lines.joinToString("") { "$it\n" })
            Files.setLastModifiedTime(file, FileTime.from(now))
        }

    fun reader(home: Path, codexHome: Path = Files.createTempDirectory("codex")) = TranscriptActivity(
        ClaudeConfig(projectsDir = home.toString()),
        CodexConfig(home = codexHome.toString()),
        gap = Duration.ofMinutes(15),
        mapper = mapper(),
        rescan = Duration.ZERO,
    )

    test("a pause longer than the gap splits the active time of a session") {
        val home = claudeHome()
        transcript(home, "s1", entry("09:00"), entry("09:10"), entry("09:20"), entry("10:00"))

        reader(home).activity(now, since).single().spans shouldContainExactly listOf(
            Span(at("09:00"), at("09:20")),
            Span(at("10:00"), at("10:00")),
        )
    }

    test("a transcript counts without a running process, with its directory, repository, branches and title") {
        val home = claudeHome()
        val checkout = Files.createTempDirectory("checkout")
        Files.createDirectories(checkout.resolve(".git"))
        Files.writeString(checkout.resolve(".git/config"), "[remote \"origin\"]\n\turl = ssh://git@git.javagil.de/mi/werkregie.git\n")
        transcript(
            home, "terminal-session",
            entry("09:00", "cwd" to "$checkout", "gitBranch" to "main", "entrypoint" to "cli"),
            """{"type":"custom-title","customTitle":"Journal archive","sessionId":"terminal-session"}""",
            entry("09:05", "cwd" to "$checkout", "gitBranch" to "claude-code/journal-archive", "entrypoint" to "cli"),
            entry("09:06", "cwd" to "$checkout", "gitBranch" to "HEAD", "entrypoint" to "cli"),
        )

        val activity = reader(home).activity(now, since).single()

        activity.tool shouldBe "claude"
        activity.session shouldBe "terminal-session"
        activity.origin shouldBe "cli"
        activity.title shouldBe "Journal archive"
        activity.cwd shouldBe "$checkout"
        activity.repository shouldBe "werkregie"
        activity.branches shouldContainExactly listOf("main", "claude-code/journal-archive")
        activity.spans shouldContainExactly listOf(Span(at("09:00"), at("09:06")))
    }

    test("reads a growing transcript on, and a line still being written only once it is complete") {
        val home = claudeHome()
        val file = transcript(home, "s1", entry("09:00"))
        val reader = reader(home)
        reader.activity(now, since)

        Files.writeString(file, entry("09:10") + "\n" + entry("09:20").take(30), java.nio.file.StandardOpenOption.APPEND)
        reader.activity(now, since).single().spans shouldContainExactly listOf(Span(at("09:00"), at("09:10")))

        Files.writeString(file, entry("09:20").drop(30) + "\n", java.nio.file.StandardOpenOption.APPEND)
        reader.activity(now, since).single().spans shouldContainExactly listOf(Span(at("09:00"), at("09:20")))
    }

    test("ignores the transcripts of subagents and the time before the window") {
        val home = claudeHome()
        transcript(home, "s1", entry("09:00"), entry("09:05"))
        transcript(home, "agent-1", entry("09:30"), project = "-home-mi-werkregie/s1/subagents")

        reader(home).activity(now, at("09:02")).map { it.session to it.spans } shouldContainExactly
            listOf("s1" to listOf(Span(at("09:02"), at("09:05"))))
    }

    test("the active time of a Codex thread comes from its rollout file") {
        val codexHome = Files.createTempDirectory("codex")
        val rollout = codexHome.resolve("rollout.jsonl")
        Files.writeString(
            rollout,
            """{"timestamp":"${at("14:00")}","type":"session_meta","payload":{"cwd":"/tmp"}}""" + "\n" +
                """{"timestamp":"${at("14:05")}","type":"event_msg","payload":{"type":"task_complete"}}""" + "\n",
        )
        DriverManager.getConnection("jdbc:sqlite:${codexHome.resolve("state_5.sqlite")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "create table threads (id text, name text, title text, cwd text, updated_at integer, archived integer, " +
                        "source text, git_branch text, rollout_path text, git_origin_url text)",
                )
                statement.execute(
                    "insert into threads values ('t1', 'Billing tests', 'first prompt', '/tmp', ${at("14:05").epochSecond}, 0, " +
                        "'cli', 'feature/US-591', '$rollout', 'git@git.javagil.de:hs/hs.hsadmin.ng.git')",
                )
                statement.execute(
                    "insert into threads values ('t2', 'Guardian', null, '/tmp', ${at("14:05").epochSecond}, 0, " +
                        "'subagent_review', null, '$rollout', null)",
                )
            }
        }

        val activity = reader(claudeHome(), codexHome).activity(now, since).single()

        activity.tool shouldBe "codex"
        activity.session shouldBe "t1"
        activity.title shouldBe "Billing tests"
        activity.repository shouldBe "hs.hsadmin.ng"
        activity.branches shouldContainExactly listOf("feature/US-591")
        activity.spans shouldContainExactly listOf(Span(at("14:00"), at("14:05")))
    }
})
