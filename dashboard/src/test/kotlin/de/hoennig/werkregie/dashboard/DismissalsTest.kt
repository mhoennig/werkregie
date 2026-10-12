package de.hoennig.werkregie.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant

class DismissalsTest : FunSpec({
    val mapper = mapper()
    val time = Instant.parse("2026-10-12T10:00:00Z")
    val ask = Flag("ask", "wartet auf dich")

    fun session(at: Instant, url: String? = "claude://code/continue?session=local_a", command: String? = null) =
        Item("claude", "Desktop", "session", Section.OPEN, "Asks", "idle", at, url = url, command = command, flags = listOf(ask))

    test("a dismissed session loses its marker until it changes") {
        val dismissals = Dismissals(Files.createTempDirectory("state").resolve(Dismissals.FILE_NAME), mapper)
        val terminal = session(time, url = null, command = "claude --resume t")
        dismissals.dismiss("claude://code/continue?session=local_a", time, now = time)

        dismissals.apply(listOf(session(time), terminal)).map { it.flags } shouldBe listOf(emptyList(), listOf(ask))
        dismissals.apply(listOf(session(time.plusSeconds(60)))).single().flags shouldBe listOf(ask)
        // the dismissal ended with the change, also for a later read at the earlier time
        dismissals.apply(listOf(session(time))).single().flags shouldBe listOf(ask)
    }

    test("dismissals survive a restart") {
        val file = Files.createTempDirectory("state").resolve(Dismissals.FILE_NAME)
        Dismissals(file, mapper).dismiss("claude --resume t", time, now = time)

        Dismissals(file, mapper).apply(listOf(session(time, url = null, command = "claude --resume t"))).single().flags shouldBe emptyList()
    }

    test("a pull request is never dismissed") {
        val dismissals = Dismissals(Files.createTempDirectory("state").resolve(Dismissals.FILE_NAME), mapper)
        val pr = Item("gitea", "javagil", "pr", Section.OPEN, "#1", "open", time, url = "https://git/pulls/1", flags = listOf(ask))
        dismissals.dismiss("https://git/pulls/1", time, now = time)

        dismissals.apply(listOf(pr)).single().flags shouldBe listOf(ask)
    }
})
