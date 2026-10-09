package de.hoennig.werkregie.dashboard.relay

import de.hoennig.werkregie.dashboard.AccessToken
import de.hoennig.werkregie.dashboard.Dashboard
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.RelayConfig
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.WebServer
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class RelayClientTest : FunSpec({
    val mapper = mapper()
    val state = Files.createTempDirectory("state")
    val accessToken = AccessToken(state.resolve("access-token"))
    val relays = RelayRegistry(state.resolve("relays.json"), mapper, Duration.ofSeconds(30), Duration.ofSeconds(120))
    val server = WebServer("127.0.0.1", 0, Dashboard(listOf(relays), windowHours = 24), mapper, accessToken, relays)
    val url = "http://127.0.0.1:${server.start()}/"
    afterSpec { server.stop() }

    val claude = object : Source {
        override val id = "claude"
        override val label = "Claude Code"
        override val ttl: Duration = Duration.ofSeconds(1)
        override fun fetch(now: Instant, since: Instant) =
            listOf(Item("claude", "Desktop", "session", Section.RUNNING, "Working", "busy", now))
    }
    val local = Dashboard(listOf(claude), windowHours = 24)

    test("reports the local sessions to the server") {
        val client = RelayClient(RelayConfig(serverUrl = url, token = accessToken.token, name = "laptop"), local, mapper) {}

        client.sendOnce() shouldBe true
        relays.fetch(Instant.now(), Instant.now().minusSeconds(60)).map { it.origin to it.title } shouldContainExactly
            listOf("laptop · Desktop" to "Working")
    }

    test("logs a rejected token once, not on every attempt") {
        val log = mutableListOf<String>()
        val client = RelayClient(RelayConfig(serverUrl = url, token = "wrong", name = "laptop"), local, mapper) { log += it }

        client.sendOnce() shouldBe false
        client.sendOnce() shouldBe false

        log shouldContainExactly listOf("Fehler: Server lehnt das Token ab (HTTP 401)")
    }
})
