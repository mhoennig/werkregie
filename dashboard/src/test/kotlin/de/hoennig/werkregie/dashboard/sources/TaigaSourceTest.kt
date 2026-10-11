package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.TaskboardConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.time.Instant

class TaigaSourceTest : FunSpec({
    val mapper = ObjectMapper()

    /**
     * A Taiga that knows the user 7 with the password "secret" and answers with whatever [answers] hold;
     * its API lies below [base], and [conf] is its `conf.json`, none by default.
     */
    class FakeTaiga(val base: String = "/api/v1") {
        val requests = mutableListOf<String>()
        val logins = mutableListOf<String>()
        var token = "t1"
        var conf: String? = null
        val answers = mutableMapOf<String, String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                val (status, body) = when {
                    path == "/conf.json" -> conf?.let { 200 to it } ?: (404 to "")
                    !path.startsWith(base) -> 404 to ""
                    path == "$base/auth" -> {
                        val login = exchange.requestBody.readAllBytes().decodeToString()
                        logins += login
                        if ("\"password\":\"secret\"" in login) 200 to """{"id":7,"auth_token":"$token","refresh":"r"}"""
                        else 401 to """{"detail":"wrong"}"""
                    }
                    exchange.requestHeaders.getFirst("Authorization") != "Bearer $token" -> 401 to """{"detail":"expired"}"""
                    else -> {
                        requests += path
                        200 to (answers.entries.firstOrNull { path.startsWith(it.key) }?.value ?: "[]")
                    }
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    fun config(url: String, statuses: List<String> = listOf("In Arbeit", "Bereit")) = TaskboardConfig(
        name = "team", type = "taiga", url = url, username = "anna", passwordEnv = "TAIGA_PASSWORD",
        projects = listOf("shop"), statuses = statuses,
    )
    val env = mapOf("TAIGA_PASSWORD" to "secret")
    val now = Instant.parse("2026-10-11T12:00:00Z")

    fun story(ref: Int, subject: String, status: String) =
        """{"ref":$ref,"subject":"$subject","status_extra_info":{"name":"$status","is_closed":false},
            "project_extra_info":{"slug":"shop","name":"Shop"},"modified_date":"2026-10-10T08:00:00.000Z"}"""

    test("the open stories, tasks and issues assigned to the user, in each configured project") {
        val taiga = FakeTaiga()
        taiga.answers["/api/v1/projects/by_slug?slug=shop"] = """{"id":42}"""
        taiga.answers["/api/v1/userstories"] = "[${story(600, "Checkout", "In Arbeit")}]"
        taiga.answers["/api/v1/tasks"] = "[${story(601, "Payment form", "Neu")}]"
        try {
            val items = TaigaSource(config(taiga.url), JsonHttp(mapper), env::get).fetch(now, now)
            items.map { Triple(it.kind, it.title, it.state) } shouldContainExactly listOf(
                Triple("story", "Checkout", "In Arbeit"),
                Triple("task", "Payment form", "Neu"),
            )
            items.first().let {
                it.source shouldBe "taiga"
                it.origin shouldBe "team"
                it.section shouldBe Section.OPEN
                it.project shouldBe "shop"
                it.detail shouldBe "US #600"
                it.url shouldBe "${taiga.url}/project/shop/us/600"
                it.time shouldBe Instant.parse("2026-10-10T08:00:00Z")
            }
            taiga.requests shouldContainExactly listOf(
                "/api/v1/projects/by_slug?slug=shop",
                "/api/v1/userstories?project=42&assigned_users=7&status__is_closed=false",
                "/api/v1/tasks?project=42&assigned_to=7&status__is_closed=false",
                "/api/v1/issues?project=42&assigned_to=7&status__is_closed=false",
            )
        } finally {
            taiga.server.stop(0)
        }
    }

    test("the configured statuses rank first, in their order; any other open status after them") {
        val source = TaigaSource(config("https://taiga.example"), JsonHttp(mapper), env::get)
        source.rank("In Arbeit") shouldBe 0
        source.rank("bereit") shouldBe 1
        source.rank("Neu") shouldBe 2
        source.rank("Bereit zum Test") shouldBe 2
    }

    test("one login serves many fetches; an expired token leads to one new login") {
        val taiga = FakeTaiga()
        taiga.answers["/api/v1/projects/by_slug?slug=shop"] = """{"id":42}"""
        try {
            val source = TaigaSource(config(taiga.url), JsonHttp(mapper), env::get)
            source.fetch(now, now)
            source.fetch(now, now)
            taiga.logins.size shouldBe 1
            taiga.token = "t2"
            source.fetch(now, now)
            taiga.logins.size shouldBe 2
        } finally {
            taiga.server.stop(0)
        }
    }

    test("a failed login names the user, never the password") {
        val taiga = FakeTaiga()
        try {
            val error = shouldThrow<IllegalStateException> {
                TaigaSource(config(taiga.url), JsonHttp(mapper), mapOf("TAIGA_PASSWORD" to "wrong")::get).fetch(now, now)
            }
            error.message shouldContain "anna"
            error.message shouldNotContain "wrong"
        } finally {
            taiga.server.stop(0)
        }
    }

    test("the API lies where Taiga's conf.json says, e.g. on a host of its own") {
        val taiga = FakeTaiga(base = "/backend/api/v1")
        taiga.conf = """{"api":"${taiga.url}/backend/api/v1/"}"""
        taiga.answers["/backend/api/v1/projects/by_slug?slug=shop"] = """{"id":42}"""
        try {
            TaigaSource(config(taiga.url), JsonHttp(mapper), env::get).fetch(now, now)
            taiga.logins.size shouldBe 1
            taiga.requests.first() shouldBe "/backend/api/v1/projects/by_slug?slug=shop"
        } finally {
            taiga.server.stop(0)
        }
    }

    test("a configured apiUrl wins over conf.json") {
        val taiga = FakeTaiga(base = "/backend/api/v1")
        taiga.conf = """{"api":"${taiga.url}/elsewhere/api/v1/"}"""
        taiga.answers["/backend/api/v1/projects/by_slug?slug=shop"] = """{"id":42}"""
        try {
            TaigaSource(config(taiga.url).copy(apiUrl = "${taiga.url}/backend/api/v1"), JsonHttp(mapper), env::get).fetch(now, now)
            taiga.requests.first() shouldBe "/backend/api/v1/projects/by_slug?slug=shop"
        } finally {
            taiga.server.stop(0)
        }
    }

    test("a conf.json that is a web page leaves the API below the pages") {
        val taiga = FakeTaiga()
        taiga.conf = "<!DOCTYPE html><html></html>"
        taiga.answers["/api/v1/projects/by_slug?slug=shop"] = """{"id":42}"""
        try {
            TaigaSource(config(taiga.url), JsonHttp(mapper), env::get).fetch(now, now)
            taiga.requests.first() shouldBe "/api/v1/projects/by_slug?slug=shop"
        } finally {
            taiga.server.stop(0)
        }
    }

    test("without projects or password the source says what is missing") {
        shouldThrow<IllegalStateException> {
            TaigaSource(config("https://taiga.example").copy(projects = emptyList()), JsonHttp(mapper), env::get).fetch(now, now)
        }.message shouldContain "projects"
        shouldThrow<IllegalStateException> {
            TaigaSource(config("https://taiga.example"), JsonHttp(mapper)) { null }.fetch(now, now)
        }.message shouldContain "passwordEnv"
    }
})
