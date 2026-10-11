package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.WerkatorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class WerkatorSourceTest : FunSpec({
    val mapper = ObjectMapper()
    val source = WerkatorSource(WerkatorConfig("javagil", "https://werkator.example/"), JsonHttp(mapper))
    val now = Instant.parse("2026-10-09T12:00:00Z")
    val since = now.minus(Duration.ofDays(7))

    fun build(name: String, status: String, startedAt: Instant) = mapper.readTree(
        """{"name":"$name","status":"$status","startedAt":"$startedAt","commit":"0123456789abcdef",
           "artifactKey":"key-1","durationSeconds":56,"pullRequest":{"number":130,"url":"https://git.example/pulls/130"}}""",
    )

    test("an ai-review build is a werkrevisor run of the PR branch") {
        val item = source.item("werkator", "werkator", build("feature@ai-review", "success", now.minus(Duration.ofHours(1))), now, since)!!

        item.source shouldBe "werkrevisor"
        item.title shouldBe "feature"
        item.section shouldBe Section.RECENT
        item.url shouldBe "https://werkator.example/repos/werkator/builds/key-1"
        item.badges.single().label shouldBe "PR #130"
    }

    test("a recent pending build is running, an old one is stale") {
        source.item("r", "r", build("b", "pending", now.minus(Duration.ofMinutes(5))), now, since)!!.section shouldBe Section.RUNNING
        source.item("r", "r", build("b", "pending", now.minus(Duration.ofDays(2))), now, since)!!.state shouldBe "stale"
    }

    test("builds before the window are left out") {
        source.item("r", "r", build("b", "success", now.minus(Duration.ofDays(8))), now, since) shouldBe null
    }

    test("the load: Werkator's verdict and the shares of CPU, RAM and the first disk") {
        val system = mapper.readTree(
            """{"timestamp":"2026-10-11T15:42:26Z","cpuCount":4,"ramTotalGib":8.0,
               "cpuUsed":{"current":3.0},"ramUsedGib":{"current":6.0},
               "disks":[{"key":"ssd","label":"SSD","totalGib":16.0,"usedGib":{"current":12.0}},{"key":"hdd","label":"HDD","totalGib":20.0}],
               "health":{"level":"pressure","reasons":["swap 2.54 of 4.00 GiB in use"]}}""",
        )

        val load = source.load(system, listOf(0.5))

        load.level shouldBe "pressure"
        load.reasons shouldBe listOf("swap 2.54 of 4.00 GiB in use")
        load.cpu shouldBe 0.75
        load.ram shouldBe 0.75
        load.disk shouldBe 0.75
        load.diskLabel shouldBe "SSD"
        load.at shouldBe Instant.parse("2026-10-11T15:42:26Z")
    }

    test("a load without numbers says nothing rather than zero") {
        val load = source.load(mapper.readTree("""{"cpuCount":0,"disks":[]}"""), emptyList())

        load.level shouldBe "unknown"
        load.cpu shouldBe null
        load.ram shouldBe null
        load.disk shouldBe null
    }

    test("the CPU history: 48 means of equal steps, as a share of the CPUs; a step without samples is null") {
        val samples = (0 until 96).map { if (it in 2..3) "null" else (it % 2 * 2).toString() }
        val history = mapper.readTree("""{"values":{"cpuUsed":[${samples.joinToString(",")}]}}""")

        val shares = source.cpuHistory(history, mapper.readTree("""{"cpuCount":2}"""))

        shares.size shouldBe 48
        shares[0] shouldBe 0.5
        shares[1] shouldBe null
        shares[47] shouldBe 0.5
    }
})
