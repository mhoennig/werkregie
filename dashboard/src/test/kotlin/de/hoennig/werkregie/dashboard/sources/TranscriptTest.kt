package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class TranscriptTest : FunSpec({
    val mapper = ObjectMapper()
    fun claude(uuid: String, text: String) =
        """{"type":"assistant","uuid":"$uuid","message":{"content":[{"type":"text","text":"$text"}]}}"""

    test("reads only the end of a long transcript, without its cut first line") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(claude("u1", "x".repeat(200)), claude("u2", "Fertig?")).joinToString("\n"))

        Transcript.end(path, mapper, tailBytes = 120) shouldBe TranscriptEnd(false, "Fertig?", "u2", claudeLast = true)
    }

    test("a command of Claude Code itself is no message of the user") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(
            claude("u1", "Fertig?"),
            """{"type":"user","message":{"content":"<command-name>/compact</command-name>"}}""",
            """{"type":"user","isMeta":true,"message":{"content":"Caveat"}}""",
        ).joinToString("\n"))

        Transcript.end(path, mapper).claudeLast shouldBe true
    }

    test("the question of an open AskUserQuestion and the last input of the user") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(
            """{"type":"user","message":{"content":"Baue den Export"}}""",
            """{"type":"user","message":{"content":[{"type":"text","text":"<system-reminder>x</system-reminder>"},{"type":"text","text":"Zeig mir die Frage"}]}}""",
            """{"type":"user","isCompactSummary":true,"message":{"content":"This session is being continued"}}""",
            claude("u1", "Zwei Varianten gibt es."),
            """{"type":"assistant","uuid":"u2","message":{"content":[{"type":"tool_use","id":"t1","name":"AskUserQuestion",
               "input":{"questions":[{"question":"Welcher Text soll im Tooltip stehen?","header":"Text","options":[]}]}}]}}""".replace("\n", ""),
        ).joinToString("\n"))

        val end = Transcript.end(path, mapper)

        end.prompt shouldBe "Zeig mir die Frage"
        end.question shouldBe "Welcher Text soll im Tooltip stehen?"
    }

    test("Claude's last text is the question only if the user did not answer it") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(claude("u1", "Soll ich?"), """{"type":"user","message":{"content":"Ja"}}""").joinToString("\n"))

        Transcript.end(path, mapper).question shouldBe null
    }

    test("an answer to AskUserQuestion is the last input") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(
            """{"type":"user","message":{"content":"Baue den Export"}}""",
            """{"type":"assistant","uuid":"u1","message":{"content":[{"type":"tool_use","id":"t1","name":"AskUserQuestion","input":{}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"answered"}]},
               "toolUseResult":{"answers":{"Welcher Zugang?":"OpenRouter"}}}""".replace("\n", ""),
            claude("u2", "Erledigt."),
        ).joinToString("\n"))

        Transcript.end(path, mapper).prompt shouldBe "OpenRouter"
    }

    test("a prompt before the read end is found") {
        val path = Files.createTempFile("transcript", ".jsonl")
        Files.writeString(path, listOf(
            """{"type":"user","message":{"content":"Alter Auftrag"}}""",
            """{"type":"user","message":{"content":"Baue den Export"}}""",
        ).plus((1..20).map { claude("u$it", "x".repeat(100)) }).joinToString("\n"))

        Transcript.end(path, mapper, tailBytes = 500).prompt shouldBe "Baue den Export"
        Transcript.end(path, mapper, tailBytes = 500, searchBytes = 1000).prompt shouldBe null
    }
})
