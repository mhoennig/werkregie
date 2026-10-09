package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.module.kotlin.readValue
import de.hoennig.werkregie.dashboard.relay.RelayRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class WebServerTest : FunSpec({
    val mapper = mapper()
    val client = HttpClient.newHttpClient()
    val state = Files.createTempDirectory("state")
    val accessToken = AccessToken(state.resolve("access-token"))
    val relays = RelayRegistry(state.resolve("relays.json"), mapper, Duration.ofSeconds(30), Duration.ofSeconds(120))
    val server = WebServer("127.0.0.1", 0, Dashboard(listOf(relays), windowHours = 24), mapper, accessToken, relays)
    val base = "http://127.0.0.1:${server.start()}"
    afterSpec { server.stop() }

    fun get(path: String, vararg headers: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).apply { if (headers.isNotEmpty()) headers(*headers) }.build(), HttpResponse.BodyHandlers.ofString())

    fun post(path: String, body: Any, vararg headers: String) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).apply { if (headers.isNotEmpty()) headers(*headers) }
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    val report = RelayReport(
        "laptop", "0.2.0", Instant.now(),
        listOf(Item("claude", "Desktop", "session", Section.RUNNING, "Working", "busy", Instant.now())),
        listOf(SourceStatus("claude", "Claude Code", true, null, null, 1, Instant.now())),
    )

    test("without the token the dashboard shows demo data") {
        mapper.readValue<Snapshot>(get("/api/dashboard").body()).access shouldBe Access.DEMO
    }

    test("a relay must send the token as bearer header; the cookie alone is not enough") {
        post("/api/relay", report).statusCode() shouldBe 401
        post("/api/relay", report, "Cookie", "${AccessToken.COOKIE}=${accessToken.token}").statusCode() shouldBe 401
        post("/api/relay", report, "Authorization", "Bearer ${accessToken.token}").statusCode() shouldBe 204
    }

    test("with the token as cookie the dashboard shows the relays' items") {
        post("/api/relay", report, "Authorization", "Bearer ${accessToken.token}")

        val snapshot = mapper.readValue<Snapshot>(get("/api/dashboard", "Cookie", "other=1; ${AccessToken.COOKIE}=${accessToken.token}").body())

        snapshot.access shouldBe Access.GRANTED
        snapshot.items.map { it.origin } shouldContain "laptop · Desktop"
        snapshot.sources.map { it.id } shouldContain "relay:laptop"
    }

    test("verifies a token for the page before it keeps it") {
        get("/api/token/verify", "Authorization", "Bearer ${accessToken.token}").statusCode() shouldBe 204
        get("/api/token/verify", "Authorization", "Bearer wrong").statusCode() shouldBe 401
    }

    test("rejects a report with an invalid relay name") {
        post("/api/relay", report.copy(relay = "../etc"), "Authorization", "Bearer ${accessToken.token}").statusCode() shouldBe 400
    }

    test("serves the page and nothing outside its assets") {
        get("/").statusCode() shouldBe 200
        get("/werkregie.css").statusCode() shouldBe 200
        get("/werkregie.properties").statusCode() shouldBe 404
    }
})

class LocalWebServerTest : FunSpec({
    val mapper = mapper()
    val server = WebServer("127.0.0.1", 0, Dashboard(emptyList(), windowHours = 24), mapper)
    val base = "http://127.0.0.1:${server.start()}"
    afterSpec { server.stop() }

    test("local mode is open and takes no relays") {
        val client = HttpClient.newHttpClient()
        val dashboard = client.send(HttpRequest.newBuilder(URI("$base/api/dashboard")).build(), HttpResponse.BodyHandlers.ofString())
        val relay = client.send(
            HttpRequest.newBuilder(URI("$base/api/relay")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        mapper.readValue<Snapshot>(dashboard.body()).access shouldBe Access.OPEN
        relay.statusCode() shouldBe 404
    }
})
