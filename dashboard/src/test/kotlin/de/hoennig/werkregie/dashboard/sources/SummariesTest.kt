package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.SummaryConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant

class SummariesTest : FunSpec({
    val mapper = ObjectMapper().registerKotlinModule()
    val now = Instant.parse("2026-10-12T12:00:00Z")
    val long = "Bitte baue den Export. ".repeat(20).trim()

    test("a text over the limit is summarized") {
        val asked = mutableListOf<Pair<String, Int>>()
        val summaries = Summaries({ text, max -> asked += text to max; "Baue den Export" }, null, mapper)

        summaries.of("Kurze Frage?", now) shouldBe "Kurze Frage?"
        summaries.of(long, now) shouldBe "Baue den Export"
        asked shouldBe listOf(long to 160)
    }

    test("a summary over the limit is shortened once more") {
        val answers = ArrayDeque(listOf("y".repeat(200), "Kurz"))
        val asked = mutableListOf<String>()
        val summaries = Summaries({ text, _ -> asked += text; answers.removeFirst() }, null, mapper)

        summaries.of(long, now) shouldBe "Kurz"
        asked shouldBe listOf(long, "y".repeat(200))
    }

    test("each text is summarized once, also after a restart") {
        val file = Files.createTempDirectory("summaries").resolve(Summaries.FILE_NAME)
        var calls = 0
        val summarizer = Summarizer { _, _ -> calls++; "Baue den Export" }

        Summaries(summarizer, file, mapper).apply {
            of(long, now)
            of(long, now.plusSeconds(2))
        }
        Summaries(summarizer, file, mapper).of(long, now.plusSeconds(60)) shouldBe "Baue den Export"

        calls shouldBe 1
    }

    test("a summary unused for a day is dropped") {
        val file = Files.createTempDirectory("summaries").resolve(Summaries.FILE_NAME)
        val summaries = Summaries({ text, _ -> text.take(10) }, file, mapper)

        summaries.of(long, now)
        summaries.of("$long Danke.", now.plusSeconds(2 * 86_400))

        Summaries(null, file, mapper).of(long, now.plusSeconds(2 * 86_400)) shouldEndWith "…"
    }

    test("without a model or after a failure the text is cut") {
        var calls = 0
        val failing = Summaries({ _, _ -> calls++; throw IllegalStateException("HTTP 503") }, null, mapper)

        Summaries(null, null, mapper).of(long, now) shouldBe long.take(159).trimEnd() + "…"
        failing.of(long, now).length shouldBe 160
        failing.error shouldBe "Zusammenfassung: HTTP 503"
        failing.of(long, now.plusSeconds(30))
        calls shouldBe 1
        failing.of(long, now.plusSeconds(61))
        calls shouldBe 2
    }

    test("the model gets the text between tags, without reasoning, and its answer is the summary") {
        val requests = mutableListOf<Pair<String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat") { exchange ->
                requests += exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readAllBytes().decodeToString()
                val answer = """{"choices":[{"message":{"role":"assistant","content":" Baue den Export? "}}]}""".toByteArray()
                exchange.sendResponseHeaders(200, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }
        try {
            val config = SummaryConfig(endpoint = "http://127.0.0.1:${server.address.port}/chat", apiKey = "k")
            val (summarizer, problem) = ChatSummarizer.of(config, JsonHttp(mapper))

            summarizer!!.summarize(long, 160) shouldBe "Baue den Export?"
            problem shouldBe null
            val (authorization, body) = requests.single()
            authorization shouldBe "Bearer k"
            val request = mapper.readTree(body)
            request["model"].asText() shouldBe "anthropic/claude-haiku-5.5"
            request["reasoning"]["enabled"].asBoolean() shouldBe false
            request["messages"][1]["content"].asText() shouldBe "<message>\n$long\n</message>"
        } finally {
            server.stop(0)
        }
    }

    test("without a key there is no summarizer, and the reason") {
        ChatSummarizer.of(SummaryConfig(apiKeyEnv = "OPENROUTER_API_KEY"), JsonHttp(mapper)) { null } shouldBe
            (null to "Zusammenfassung: kein Schlüssel in OPENROUTER_API_KEY")
    }
})
