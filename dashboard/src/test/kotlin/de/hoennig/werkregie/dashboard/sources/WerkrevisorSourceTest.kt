package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.WerkrevisorConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WerkrevisorSourceTest : FunSpec({
    val mapper = ObjectMapper()
    val source = WerkrevisorSource(WerkrevisorConfig("javagil", "https://werkrevisor.example/"), JsonHttp(mapper))

    test("the costs of 24 hours and 7 days, and the last 7 days oldest first") {
        val days = (1..10).joinToString(",") { """{"period":"2026-10-${21 - it}","sums":{"costUsd":$it.0}}""" }
        val stats = mapper.readTree(
            """{"windows":[{"name":"24h","sums":{"costUsd":2.83,"reviews":92,"pullRequests":45}},
                           {"name":"7d","sums":{"costUsd":29.69,"reviews":349}}],
               "days":[$days]}""",
        )

        val costs = source.costs(stats)

        costs.source shouldBe "werkrevisor:javagil"
        costs.url shouldBe "https://werkrevisor.example"
        costs.day shouldBe 2.83
        costs.week shouldBe 29.69
        costs.reviews shouldBe 92
        costs.pullRequests shouldBe 45
        costs.perDay shouldBe listOf(7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)
    }

    test("a service without sums yet reports no costs rather than zero") {
        val costs = source.costs(mapper.readTree("""{"windows":[],"days":[]}"""))

        costs.day shouldBe null
        costs.week shouldBe null
        costs.perDay shouldBe emptyList()
    }
})
