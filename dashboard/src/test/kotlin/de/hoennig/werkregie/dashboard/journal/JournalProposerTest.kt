package de.hoennig.werkregie.dashboard.journal

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JournalConfig
import de.hoennig.werkregie.dashboard.JournalCustomer
import de.hoennig.werkregie.dashboard.JournalProject
import de.hoennig.werkregie.dashboard.JournalTarget
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.time.LocalDate

class JournalProposerTest : FunSpec({
    val day = LocalDate.parse("2026-10-12")

    fun at(time: String) = Instant.parse("2026-10-12T${time}:00Z")

    val config = JournalConfig(
        customers = listOf(
            JournalCustomer("Hostsharing", listOf(JournalProject("HSAdmin NG", repositories = listOf("hs.hsadmin.ng"), taiga = listOf("hsadmin-ng")))),
            JournalCustomer("Intern", listOf(JournalProject("Werkregie", repositories = listOf("werkregie")), JournalProject("Sonstiges"))),
        ),
        fallback = JournalTarget("Intern", "Sonstiges"),
    )
    val stories = TaigaStories { slug, ref ->
        when (ref) {
            545L, 546L -> Story(slug, 545, "US", "Security Hardening")
            else -> null
        }
    }

    fun session(id: String, repository: String, vararg spans: Pair<String, String>, title: String? = id, branches: List<String> = emptyList()) =
        Activity("claude", id, "think · cli", title, "/home/mi/$repository", repository, repository, branches,
            spans.map { (from, to) -> Span(at(from), at(to)) })

    fun item(source: String, kind: String, title: String, time: String, project: String?, detail: String? = null, url: String? = null) =
        Item(source, "javagil", kind, Section.RECENT, title, "done", at(time), project = project, detail = detail, url = url)

    fun propose(sessions: List<Activity> = emptyList(), items: List<Item> = emptyList(), journal: JournalConfig = config) =
        JournalProposer(journal, stories).propose(JournalDay(day, "UTC", sessions, items))

    test("one entry per Taiga story, whichever task it was") {
        val proposal = propose(
            sessions = listOf(session("s1", "hs.hsadmin.ng", "09:00" to "10:00", branches = listOf("main", "mihoe/Taiga545-hardening"))),
            items = listOf(item("taiga", "task", "Header setzen", "11:00", "hsadmin-ng", detail = "Task #546")),
        )

        proposal.entries.single().let {
            it.project shouldBe "HSAdmin NG"
            it.story?.ref shouldBe 545
            it.description shouldBe "Taiga#US-545 Security Hardening: Task #546 Header setzen; Sitzung „s1“"
        }
    }

    test("a story and the rest of its project are two entries, the one without story first") {
        val proposal = propose(
            sessions = listOf(
                session("s1", "hs.hsadmin.ng", "09:00" to "10:00", branches = listOf("mihoe/Taiga545-hardening")),
                session("s2", "hs.hsadmin.ng", "11:00" to "12:00"),
            ),
        )

        proposal.entries.map { it.project to it.story?.ref } shouldContainExactly listOf("HSAdmin NG" to null, "HSAdmin NG" to 545L)
    }

    test("one entry per project without a Taiga ID, naming the pull request and the sessions") {
        val proposal = propose(
            sessions = listOf(session("Journal archive", "werkregie", "09:00" to "10:00"), session("Journal import", "werkregie", "13:00" to "14:00")),
            items = listOf(
                item("gitea", "activity", "merge PR #41 Werkregie keeps the work of each day", "10:30", "mi/werkregie",
                    url = "https://git.javagil.de/mi/werkregie/pulls/41"),
                item("werkator", "build", "claude-code/journal-archive", "10:20", "werkregie"),
                item("gitea", "pr", "#45 WIP: A Werkjournal import file", "13:30", "mi/werkregie", url = "https://git.javagil.de/mi/werkregie/pulls/45"),
            ),
        )

        proposal.entries.single().let {
            it.project shouldBe "Werkregie"
            it.story shouldBe null
            it.description shouldBe "PR#41 Werkregie keeps the work of each day; PR#45 A Werkjournal import file; Sitzung „Journal archive“; Sitzung „Journal import“"
            it.evidence.map { evidence -> evidence.kind } shouldContainExactly listOf("session", "build", "activity", "session", "pr")
        }
    }

    test("the hours are the union of the active spans and a quarter of an hour before each action, rounded half up") {
        val proposal = propose(
            sessions = listOf(session("a", "werkregie", "09:00" to "10:00"), session("b", "werkregie", "09:30" to "10:20")),
            items = listOf(item("gitea", "activity", "merge PR #41", "11:00", "mi/werkregie")),
        )

        proposal.entries.single().let { it.minutes to it.hours } shouldBe (95L to 2)
    }

    test("two entries at the same time share the minutes") {
        val proposal = propose(sessions = listOf(session("a", "werkregie", "09:00" to "11:00"), session("b", "hs.hsadmin.ng", "10:00" to "11:00")))

        proposal.entries.map { it.project to it.minutes } shouldContainExactlyInAnyOrder listOf("Werkregie" to 90L, "HSAdmin NG" to 30L)
    }

    test("the shared minutes add up to the time of the clock") {
        val proposal = propose(
            sessions = listOf(
                session("a", "werkregie", "09:00" to "09:10"),
                session("b", "hs.hsadmin.ng", "09:00" to "09:10"),
                session("c", "keluna", "09:00" to "09:10"),
            ),
        )

        proposal.entries.map { it.minutes }.sorted() shouldContainExactly listOf(3L, 3L, 4L)
    }

    test("less than half an hour is a hint, not an entry of the file") {
        val proposal = propose(sessions = listOf(session("a", "werkregie", "09:00" to "09:20"), session("b", "hs.hsadmin.ng", "10:00" to "10:30")))

        proposal.entries.map { it.project to it.hours } shouldContainExactly listOf("HSAdmin NG" to 1, "Werkregie" to 0)
        proposal.file.workEntries.map { it.description } shouldContainExactly listOf("Sitzung „b“")
    }

    test("unmapped work goes to the fallback project, or without one stays a hint") {
        propose(sessions = listOf(session("x", "keluna", "09:00" to "10:00"))).entries.single().let { it.customer to it.project } shouldBe
            ("Intern" to "Sonstiges")

        propose(sessions = listOf(session("x", "keluna", "09:00" to "10:00")), journal = config.copy(fallback = null)).let {
            it.entries.single().let { entry -> Triple(entry.mapped, entry.project, entry.hours) } shouldBe Triple(false, "keluna", 0)
            it.file.workEntries shouldBe emptyList()
        }
    }

    test("a day holds at most 12 hours, the largest entry giving first") {
        val proposal = propose(
            sessions = listOf(
                session("a", "hs.hsadmin.ng", "00:00" to "08:00"),
                session("b", "werkregie", "08:00" to "12:00"),
                session("c", "keluna", "12:00" to "15:00"),
            ),
        )

        proposal.entries.map { it.project to it.hours } shouldContainExactlyInAnyOrder listOf("HSAdmin NG" to 5, "Werkregie" to 4, "Sonstiges" to 3)
    }

    test("the same day gives the same file, ids and descriptions included") {
        val sessions = listOf(session("b", "werkregie", "13:00" to "14:00"), session("a", "werkregie", "09:00" to "10:00"))

        mapper().writeValueAsString(propose(sessions).file) shouldBe mapper().writeValueAsString(propose(sessions.reversed()).file)
    }

    test("the file holds only the fields Werkjournal allows, each project of its customer, whole hours from 1 to 12") {
        val proposal = propose(
            sessions = listOf(session("a", "werkregie", "09:00" to "11:00"), session("b", "hs.hsadmin.ng", "12:00" to "13:00"), session("c", "werkregie", "14:00" to "14:10")),
        )
        val file: JsonNode = mapper().valueToTree(proposal.file)

        file.fieldNames().asSequence().toList() shouldContainExactly listOf("schemaVersion", "customers", "projects", "workEntries")
        file["schemaVersion"].asInt() shouldBe 1
        file["customers"].map { it.fieldNames().asSequence().toSet() }.toSet() shouldBe setOf(setOf("id", "name"))
        file["projects"].map { it.fieldNames().asSequence().toSet() }.toSet() shouldBe setOf(setOf("id", "customerId", "name"))
        file["workEntries"].map { it.fieldNames().asSequence().toSet() }.toSet() shouldBe
            setOf(setOf("id", "date", "customerId", "projectId", "hours", "description"))
        val customerOf = file["projects"].associate { it["id"].asText() to it["customerId"].asText() }
        file["workEntries"].forEach { entry ->
            customerOf[entry["projectId"].asText()] shouldBe entry["customerId"].asText()
            entry["hours"].isInt shouldBe true
            (entry["hours"].asInt() in 1..12) shouldBe true
            entry["date"].asText() shouldBe "2026-10-12"
            java.util.UUID.fromString(entry["id"].asText())
        }
        file["workEntries"].size() shouldBe 2
    }

    test("recognises Taiga IDs as people and Taiga write them") {
        listOf("Taiga#US-591", "Taiga#591", "mihoe/Taiga591-x", "US-591 fix", "TG-591 #closed", "taiga 591").forEach {
            JournalProposer.TAIGA_ID.find(it)?.groupValues?.get(1) shouldBe "591"
        }
        listOf("status-591", "PR #591", "focus-591").forEach { JournalProposer.TAIGA_ID.find(it) shouldBe null }
    }

    test("an unreachable Taiga leaves the work with its project") {
        val proposal = JournalProposer(config) { _, _ -> error("Taiga down") }.propose(
            JournalDay(day, "UTC", listOf(session("s1", "hs.hsadmin.ng", "09:00" to "10:00", branches = listOf("Taiga545"))), emptyList()),
        )

        proposal.entries.single().let { it.story to it.description } shouldBe (null to "Sitzung „s1“")
        proposal.entries.single().project shouldContain "HSAdmin"
    }
})
