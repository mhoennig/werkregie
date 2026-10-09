package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.Badge
import de.hoennig.werkregie.dashboard.GiteaConfig
import de.hoennig.werkregie.dashboard.JsonHttp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GiteaSourceTest : FunSpec({
    val mapper = ObjectMapper()
    val source = GiteaSource(GiteaConfig("javagil", "https://git.example"), JsonHttp(mapper), mapper) { null }

    fun status(context: String, state: String, description: String) = mapper.readTree(
        """{"context":"$context","status":"$state","description":"$description","target_url":"https://w.example/b/1"}""",
    )

    test("the werkrevisor gate shows its verdict") {
        source.statusBadge(status("ai-review/gate", "failure", "RED CHANGES_REQUIRED: open question")) shouldBe
            Badge("werkrevisor Lauf RED", "red", "https://w.example/b/1", "RED CHANGES_REQUIRED: open question")
    }

    test("the werkrevisor assessment is its verdict") {
        source.statusBadge(status("ai-review/assessment", "failure", "rule violated")).let { it.label to it.state } shouldBe
            ("werkrevisor Urteil" to "failure")
    }

    test("other contexts keep their commit status") {
        source.statusBadge(status("werkator", "pending", "build queued")).let { it.label to it.state } shouldBe
            ("werkator" to "pending")
    }
})
