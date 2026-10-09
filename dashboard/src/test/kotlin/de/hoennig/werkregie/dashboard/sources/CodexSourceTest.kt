package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.CodexConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class CodexSourceTest : FunSpec({
    val source = CodexSource(CodexConfig(), ObjectMapper())
    val now = Instant.now()

    fun rollout(vararg events: String, modified: Instant = now) =
        Files.createTempFile("rollout", ".jsonl").also { file ->
            Files.writeString(
                file,
                events.joinToString("\n") { """{"type":"event_msg","payload":{"type":"$it"}}""" } +
                    "\n" + """{"type":"response_item","payload":{"type":"message"}}""" + "\n",
            )
            Files.setLastModifiedTime(file, FileTime.from(modified))
        }.toString()

    test("a turn started but not completed is running") {
        source.isRunning(rollout("task_started", "task_complete", "task_started"), now) shouldBe true
    }

    test("a completed turn is not running") {
        source.isRunning(rollout("task_started", "task_complete"), now) shouldBe false
    }

    test("an open turn in a file untouched for long is not running") {
        source.isRunning(rollout("task_started", modified = now.minus(Duration.ofHours(2))), now) shouldBe false
    }
})
