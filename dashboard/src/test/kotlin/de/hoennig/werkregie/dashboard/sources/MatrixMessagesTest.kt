package de.hoennig.werkregie.dashboard.sources

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

class MatrixMessagesTest : FunSpec({
    val message = MatrixMessage(
        eventId = "\$event",
        roomId = "!room:example.org",
        roomName = "Team",
        direct = false,
        sender = "Anna",
        time = Instant.parse("2026-10-10T12:00:00Z"),
        text = "Hallo zusammen",
        mentionsMe = false,
    )

    // Scenario#8.04
    test("a direct message shows, marked as direct") {
        val direct = message.copy(direct = true)
        MatrixMessages.reason(direct, emptySet()) shouldBe "direct"
        val item = MatrixMessages.item(direct, "direct", "example.org", "matrix.to")
        item.title shouldBe "Hallo zusammen"
        item.detail shouldBe "Anna"
        item.flags.single().kind shouldBe "direct"
    }

    // Scenario#8.05
    test("a mention in any room shows, marked as mention") {
        MatrixMessages.reason(message.copy(mentionsMe = true), emptySet()) shouldBe "mention"
    }

    test("a mention is found in m.mentions, by user ID or by display name") {
        MatrixMessages.mentions("egal", setOf("@me:example.org"), "@me:example.org", "Mi") shouldBe true
        MatrixMessages.mentions("frag @me:example.org", null, "@me:example.org", "Mi") shouldBe true
        MatrixMessages.mentions("Mi: schau mal", null, "@me:example.org", "Mi") shouldBe true
        MatrixMessages.mentions("Mittag?", null, "@me:example.org", "Mi") shouldBe false
    }

    // Scenario#8.06
    test("every message of a configured room shows, marked as room") {
        MatrixMessages.reason(message, setOf("!room:example.org")) shouldBe "room"
    }

    // Scenario#8.07
    test("a message of another room, without a mention, stays hidden") {
        MatrixMessages.reason(message, setOf("!other:example.org")) shouldBe null
    }

    // Scenario#8.08
    test("a message the device cannot decrypt shows as encrypted, with room, sender and time") {
        val item = MatrixMessages.item(message.copy(text = null), "room", "example.org", "matrix.to")
        item.title shouldBe "verschlüsselt"
        item.state shouldBe "encrypted"
        item.detail shouldBe "Team · Anna"
        item.time shouldBe message.time
    }

    // Scenario#8.09
    test("only the start of the text goes into the row, on one line") {
        val long = "> zitierte Zeile\nErste Zeile\n\n" + "x".repeat(200)
        val title = MatrixMessages.item(message.copy(text = long), "room", "example.org", "matrix.to").title
        title.length shouldBe MatrixMessages.SNIPPET_LENGTH
        title shouldNotContain "\n"
        title shouldNotContain "zitierte"
    }

    // Scenario#8.10
    test("a message links to itself, by matrix.to or Element Desktop's schemes") {
        MatrixMessages.link("matrix.to", "!room:example.org", "\$event") shouldBe "https://matrix.to/#/!room:example.org/\$event"
        MatrixMessages.link("element", "!room:example.org", "\$event") shouldBe "element://vector/webapp/#/room/!room:example.org/\$event"
        MatrixMessages.link("io.element.desktop", "!r:x", "\$e") shouldBe "io.element.desktop://vector/webapp/#/room/!r:x/\$e"
    }

    // Scenario#12.04
    test("a row's link gives back room ID and event ID, in each form") {
        for (style in listOf("matrix.to", "element", "io.element.desktop")) {
            MatrixMessages.ids(MatrixMessages.link(style, "!room:example.org", "\$event")) shouldBe ("!room:example.org" to "\$event")
        }
        MatrixMessages.ids("https://matrix.to/#/!demo:example.org") shouldBe null
    }

    // Scenario#12.01, Scenario#12.02
    test("a recovery key is told apart from a security phrase") {
        MatrixMessages.isRecoveryKey("EsTc LW2K PGiF wKEA 3As5 g5c4 BXwk qeeJ ZJV8 Q9fu gUMN UE4d") shouldBe true
        MatrixMessages.isRecoveryKey("EsTcLW2KPGiFwKEA3As5g5c4BXwkqeeJZJV8Q9fugUMNUE4d") shouldBe true
        MatrixMessages.isRecoveryKey("correct horse battery staple") shouldBe false
        MatrixMessages.isRecoveryKey("EsTc LW2K PGiF wKEA 3As5 g5c4 BXwk qeeJ ZJV8 Q9fu gUMN UE40") shouldBe false
    }

    fun row(room: String, event: String, reason: String, time: String) =
        MatrixMessages.item(message.copy(roomId = room, eventId = event, time = Instant.parse(time)), reason, "example.org", "matrix.to")
    val since = Instant.parse("2026-10-10T12:00:00Z")

    // Scenario#13.01
    test("a direct chat keeps its last message, however old") {
        val old = row("!dm:x", "\$1", "direct", "2026-09-01T10:00:00Z")
        MatrixMessages.visible(listOf(old), since) shouldBe listOf(old)
    }

    // Scenario#13.02
    test("a configured room keeps its last message, however old") {
        val old = row("!team:x", "\$1", "room", "2026-09-01T10:00:00Z")
        MatrixMessages.visible(listOf(old), since) shouldBe listOf(old)
    }

    // Scenario#13.03
    test("older messages of a conversation stay within the period, and old mentions are not kept") {
        val older = row("!dm:x", "\$1", "direct", "2026-09-01T10:00:00Z")
        val last = row("!dm:x", "\$2", "direct", "2026-09-02T10:00:00Z")
        val recent = row("!team:x", "\$3", "room", "2026-10-11T10:00:00Z")
        val oldMention = row("!other:x", "\$4", "mention", "2026-09-03T10:00:00Z")
        MatrixMessages.visible(listOf(older, last, recent, oldMention), since) shouldBe listOf(last, recent)
    }

    // Scenario#13.04
    test("an unread message carries the mark") {
        val item = MatrixMessages.item(message.copy(unread = true), "direct", "example.org", "matrix.to")
        item.flags.map { it.kind } shouldBe listOf("direct", "unread")
    }

    // Scenario#13.05, Scenario#13.06
    test("a read receipt after a message takes its mark, one before keeps it") {
        val item = MatrixMessages.item(message.copy(unread = true), "direct", "example.org", "matrix.to")
        MatrixMessages.read(item, message.time.plusSeconds(60)).flags.map { it.kind } shouldBe listOf("direct")
        MatrixMessages.read(item, message.time.minusSeconds(60)).flags.map { it.kind } shouldBe listOf("direct", "unread")
    }

    // Scenario#14.01, Scenario#14.02, Scenario#14.03
    test("the later of read receipt and read marker decides until when a room is read") {
        val receipt = Instant.parse("2026-08-10T17:50:38Z")
        val marker = Instant.parse("2026-10-09T20:18:39Z")
        MatrixMessages.readUntil(receipt, marker) shouldBe marker
        MatrixMessages.readUntil(marker, receipt) shouldBe marker
        MatrixMessages.readUntil(null, marker) shouldBe marker
        MatrixMessages.readUntil(receipt, null) shouldBe receipt
        MatrixMessages.readUntil(null, null) shouldBe null
        val item = MatrixMessages.item(message.copy(time = Instant.parse("2026-10-09T20:18:39Z"), unread = true), "room", "example.org", "matrix.to")
        MatrixMessages.read(item, MatrixMessages.readUntil(receipt, marker)!!).flags.map { it.kind } shouldBe listOf("room")
    }
})
