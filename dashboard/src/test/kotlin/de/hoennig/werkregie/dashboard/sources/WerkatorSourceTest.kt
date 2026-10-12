package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.WerkatorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
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

    fun system(extra: String = "") = mapper.readTree(
        """{"timestamp":"2026-10-11T15:42:26Z","cpuCount":4,"ramTotalGib":8.0,
           "cpuUsed":{"current":3.0},"ramUsedGib":{"current":7.6},"load1":4.4,
           "disks":[{"key":"ssd","label":"SSD","totalGib":16.0,"usedGib":{"current":12.0}},{"key":"hdd","label":"HDD","totalGib":20.0,"usedGib":{"current":19.0}}],
           "swapTotalGib":4.0,"swapUsedGib":{"current":1.0},"cpuPressure":5.0,"memoryPressure":0.5,"ioPressure":1.0,
           "health":{"level":"pressure","reasons":["swap 2.54 of 4.00 GiB in use"]}$extra}""",
    )

    test("the load holds the three most critical charts in Werkator's order") {
        val load = source.load(system(), mapOf("ramUsedGib" to listOf(4.0, null, 8.0), "load1" to listOf(2.0, 8.0)))

        load.level shouldBe "pressure"
        load.reasons shouldBe listOf("swap 2.54 of 4.00 GiB in use")
        load.at shouldBe Instant.parse("2026-10-11T15:42:26Z")
        // RAM and HDD are critical, load warns; CPU and SSD at 75 % stay out
        load.charts.map { Triple(it.key, it.value, it.level) } shouldBe listOf(
            Triple("ram", 0.95, "bad"),
            Triple("disk", 0.95, "bad"),
            Triple("load", 4.4, "warn"),
        )
        load.charts[1].label shouldBe "HDD"
        load.charts[0].history shouldBe listOf(0.5, null, 1.0)
        // load above the threads: the highest value is the scale
        load.charts[2].history shouldBe listOf(0.25, 1.0)
    }

    test("a metric above its threshold comes first, then the ones closest to theirs") {
        val system = mapper.readTree(
            """{"cpuCount":4,"ramTotalGib":8.0,"cpuUsed":{"current":1.0},"ramUsedGib":{"current":4.0},"load1":1.0,
               "disks":[{"key":"ssd","label":"SSD","totalGib":16.0,"usedGib":{"current":12.0}},
                        {"key":"hdd","label":"HDD","totalGib":20.0,"usedGib":{"current":5.0}}],
               "swapTotalGib":4.0,"swapUsedGib":{"current":2.5},"cpuPressure":5.0,"memoryPressure":0.5,"ioPressure":1.0}""",
        )

        val charts = source.load(system, mapOf("swapUsedGib" to listOf(1.0, 2.0))).charts

        // only swap warns; SSD at 94 % of its threshold and RAM at 63 % are the closest to theirs
        charts.map { it.key } shouldBe listOf("ram", "disk", "swap")
        charts.map { it.level } shouldBe listOf("ok", "ok", "warn")
        charts[2].value shouldBe 0.625
        charts[2].history shouldBe listOf(0.25, 0.5)
    }

    test("a failed history is read again at the next fetch") {
        var historyStatus = 503
        val werkator = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (status, body) = when (exchange.requestURI.path) {
                    "/api/system" -> 200 to system().toString()
                    "/api/system/history" -> historyStatus to """{"values":{"ramUsedGib":[4.0,8.0]}}"""
                    else -> 200 to "[]"
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val live = WerkatorSource(WerkatorConfig("live", "http://127.0.0.1:${werkator.address.port}"), JsonHttp(mapper))
            live.fetch(now, since)
            live.load()!!.charts.first().history shouldBe emptyList()

            historyStatus = 200
            live.fetch(now.plusSeconds(30), since)

            live.load()!!.charts.first().history.filterNotNull() shouldBe listOf(0.5, 1.0)
        } finally {
            werkator.stop(0)
        }
    }

    test("a load without numbers says nothing rather than zero") {
        // RAM in use, but no total to take its share of
        val load = source.load(mapper.readTree("""{"cpuCount":0,"ramUsedGib":{"current":2.0},"disks":[]}"""), emptyMap())

        load.level shouldBe "unknown"
        load.charts.map { it.key } shouldBe listOf("ram")
        load.charts.map { it.value } shouldBe listOf(null)
        load.charts.map { it.history } shouldBe listOf(emptyList())
    }

    test("the history: 48 means of equal steps per series; a step without samples is null") {
        val samples = (0 until 96).map { if (it in 2..3) "null" else (it % 2 * 2).toString() }
        val history = mapper.readTree("""{"values":{"cpuUsed":[${samples.joinToString(",")}],"load1":[]}}""")

        val steps = source.steps(history)

        steps.getValue("cpuUsed").size shouldBe 48
        steps.getValue("cpuUsed")[0] shouldBe 1.0
        steps.getValue("cpuUsed")[1] shouldBe null
        steps.getValue("cpuUsed")[47] shouldBe 1.0
        steps.getValue("load1") shouldBe emptyList()
    }
})
