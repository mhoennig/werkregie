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
})
