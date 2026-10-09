package de.hoennig.werkregie.dashboard.relay

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.Dashboard
import de.hoennig.werkregie.dashboard.RelayConfig
import de.hoennig.werkregie.dashboard.RelayReport
import de.hoennig.werkregie.dashboard.expandHome
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Reads the local agents through [local] and posts their full state to the server every few
 * seconds. The connection goes out from this machine, so it works where nothing can reach in;
 * a failed post is retried with a growing pause, and every change between failing and working
 * is logged once.
 */
class RelayClient(
    private val config: RelayConfig,
    private val local: Dashboard,
    private val mapper: ObjectMapper,
    private val log: (String) -> Unit = { println("${Instant.now()} $it") },
) {
    val name: String = config.name ?: InetAddress.getLocalHost().hostName.substringBefore('.')
    private val endpoint = URI((config.serverUrl ?: error("relay.serverUrl fehlt")).trimEnd('/') + "/api/relay")
    private val token = token()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile
    private var lastError: String? = "noch nicht gesendet"

    init {
        require(RelayRegistry.NAME.matches(name)) { "relay.name '$name' erlaubt nur A-Z, a-z, 0-9, Punkt, Bindestrich und Unterstrich" }
    }

    fun runForever() {
        log("Relay $name meldet an $endpoint alle ${config.intervalSeconds} s")
        var failures = 0
        while (!Thread.currentThread().isInterrupted) {
            failures = if (sendOnce()) 0 else failures + 1
            val pause = config.intervalSeconds * (1L shl minOf(failures, 4))
            Thread.sleep(Duration.ofSeconds(minOf(pause, MAX_PAUSE_SECONDS)))
        }
    }

    /** One report; true when the server took it. */
    fun sendOnce(now: Instant = Instant.now()): Boolean {
        val error = runCatching { post(now) }.fold({ it }, { it.message ?: it.javaClass.simpleName })
        if (error != lastError) log(error?.let { "Fehler: $it" } ?: "verbunden")
        lastError = error
        return error == null
    }

    private fun post(now: Instant): String? {
        val snapshot = local.snapshot(now)
        val report = RelayReport(name, local.version, now, snapshot.items, snapshot.sources)
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(report)))
            .build()
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        return when (status) {
            in 200..299 -> null
            401 -> "Server lehnt das Token ab (HTTP 401)"
            else -> "HTTP $status von $endpoint"
        }
    }

    private fun token(): String =
        config.token
            ?: config.tokenEnv?.let { System.getenv(it) }
            ?: config.tokenFile?.let { expandHome(it) }?.takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }
            ?: error("kein Token konfiguriert (relay.token, relay.tokenEnv oder relay.tokenFile)")

    companion object {
        private const val MAX_PAUSE_SECONDS = 60L
    }
}
