package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoennig.werkregie.dashboard.journal.JournalArchive
import de.hoennig.werkregie.dashboard.relay.RelayRegistry
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * The page, its assets and the JSON API. Without an [accessToken] (local mode) everything is open;
 * with one, the dashboard shows real data only to a request carrying the token — as cookie from a
 * browser, as bearer header from a script — and invented demo data to anyone else, and relays may
 * report only with the bearer header, never with the cookie alone, so no other site can make a
 * logged-in browser post to it.
 */
class WebServer(
    private val bindAddress: String,
    private val port: Int,
    private val dashboard: Dashboard,
    private val mapper: ObjectMapper,
    private val accessToken: AccessToken? = null,
    private val relays: RelayRegistry? = null,
    private val journal: JournalArchive? = null,
    private val dismissals: Dismissals? = null,
) {
    private lateinit var server: HttpServer

    /** Starts listening and returns the port, which differs from [port] only when that is 0. */
    fun start(): Int {
        server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/api/dashboard") { exchange -> handle(exchange) { dashboard(it) } }
        server.createContext("/api/token/verify") { exchange -> handle(exchange) { verify(it) } }
        server.createContext("/api/relay") { exchange -> handle(exchange) { relay(it) } }
        server.createContext("/api/journal/") { exchange -> handle(exchange) { journal(it) } }
        server.createContext("/api/dismiss") { exchange -> handle(exchange) { dismiss(it) } }
        server.createContext("/") { exchange -> handle(exchange) { StaticFiles.serve(it) } }
        server.start()
        return server.address.port
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange, handler: (HttpExchange) -> Unit) {
        exchange.use {
            try {
                handler(it)
            } catch (e: Exception) {
                it.respond(500, "text/plain", "internal error".toByteArray())
            }
        }
    }

    private fun dashboard(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "GET only".toByteArray())
        val snapshot = when {
            accessToken == null -> dashboard.snapshot(access = Access.OPEN)
            accessToken.matches(exchange.cookie(AccessToken.COOKIE) ?: exchange.bearer()) ->
                dashboard.snapshot(access = Access.GRANTED)
            else -> Demo.snapshot(Instant.now(), dashboard.version)
        }
        exchange.respond(200, "application/json", mapper.writeValueAsBytes(snapshot))
    }

    /** `GET /api/journal/YYYY-MM-DD`: the record of a day; only with the token, since it is the user's work itself. */
    private fun journal(exchange: HttpExchange) {
        if (journal == null) return exchange.respond(404, "text/plain", "no journal in this mode".toByteArray())
        if (accessToken != null && !accessToken.matches(exchange.cookie(AccessToken.COOKIE) ?: exchange.bearer())) {
            return exchange.respond(401, "text/plain", "token required".toByteArray())
        }
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "GET only".toByteArray())
        val day = exchange.requestURI.path.removePrefix("/api/journal/")
            .takeIf { DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return exchange.respond(400, "text/plain", "day as YYYY-MM-DD".toByteArray())
        exchange.respond(200, "application/json", mapper.writeValueAsBytes(journal.day(day)))
    }

    /** Lets the page check a token before it stores it. */
    private fun verify(exchange: HttpExchange) {
        val ok = accessToken == null || accessToken.matches(exchange.bearer())
        exchange.respond(if (ok) 204 else 401, "text/plain", ByteArray(0))
    }

    private fun relay(exchange: HttpExchange) {
        if (relays == null || accessToken == null) return exchange.respond(404, "text/plain", "no relays in this mode".toByteArray())
        if (!accessToken.matches(exchange.bearer())) return exchange.respond(401, "text/plain", "token required".toByteArray())
        val path = exchange.requestURI.path
        if (path.startsWith("/api/relay/")) return forget(exchange, path.removePrefix("/api/relay/"))
        if (path != "/api/relay") return exchange.respond(404, "text/plain", "not found".toByteArray())
        if (exchange.requestMethod != "POST") return exchange.respond(405, "text/plain", "POST only".toByteArray())
        val body = exchange.requestBody.readNBytes(MAX_REPORT_BYTES + 1)
        if (body.size > MAX_REPORT_BYTES) return exchange.respond(413, "text/plain", "report too large".toByteArray())
        val report = runCatching { mapper.readValue<RelayReport>(body) }.getOrNull()
            ?.takeIf { RelayRegistry.NAME.matches(it.relay) }
            ?: return exchange.respond(400, "text/plain", "invalid report".toByteArray())
        relays.accept(report)
        exchange.respond(204, "text/plain", ByteArray(0))
    }

    /**
     * `POST /api/dismiss` with `{"key", "time"}`: the page dismisses a session from "Du bist dran".
     * Like a relay, only with the bearer header; and only JSON, which a form of another site cannot send to the local mode.
     */
    private fun dismiss(exchange: HttpExchange) {
        if (dismissals == null) return exchange.respond(404, "text/plain", "no dismissals in this mode".toByteArray())
        if (exchange.requestMethod != "POST") return exchange.respond(405, "text/plain", "POST only".toByteArray())
        if (accessToken != null && !accessToken.matches(exchange.bearer())) {
            return exchange.respond(401, "text/plain", "token required".toByteArray())
        }
        if (exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
            return exchange.respond(415, "text/plain", "JSON only".toByteArray())
        }
        val body = exchange.requestBody.readNBytes(MAX_DISMISS_BYTES + 1)
        if (body.size > MAX_DISMISS_BYTES) return exchange.respond(413, "text/plain", "too large".toByteArray())
        val request = runCatching { mapper.readValue<DismissRequest>(body) }.getOrNull()
            ?: return exchange.respond(400, "text/plain", "invalid request".toByteArray())
        // only a session that asks now, so the file holds nothing a caller made up
        if (!Dismissals.asks(dashboard.snapshot().items, request.key)) {
            return exchange.respond(404, "text/plain", "no session asks with this key".toByteArray())
        }
        dismissals.dismiss(request.key, request.time)
        exchange.respond(204, "text/plain", ByteArray(0))
    }

    private data class DismissRequest(val key: String, val time: Instant)

    /** `DELETE /api/relay/NAME`: what `werkregie-dashboard forget-relay NAME` sends. */
    private fun forget(exchange: HttpExchange, name: String) {
        if (exchange.requestMethod != "DELETE") return exchange.respond(405, "text/plain", "DELETE only".toByteArray())
        if (!RelayRegistry.NAME.matches(name)) return exchange.respond(400, "text/plain", "invalid relay name".toByteArray())
        val forgotten = relays!!.forget(name)
        exchange.respond(if (forgotten) 204 else 404, "text/plain", if (forgotten) ByteArray(0) else "unknown relay".toByteArray())
    }

    private fun HttpExchange.bearer(): String? =
        requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

    private fun HttpExchange.cookie(name: String): String? =
        requestHeaders["Cookie"].orEmpty()
            .flatMap { it.split(';') }
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    companion object {
        // a few hundred sessions of a week fit into well under a megabyte
        private const val MAX_REPORT_BYTES = 4 * 1024 * 1024
        private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private const val MAX_DISMISS_BYTES = 4 * 1024
    }
}

/** The page and its assets from the classpath; anything outside `static/` is not reachable. */
private object StaticFiles {
    private val safePath = Regex("^/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
    private val types = mapOf(
        "html" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "webmanifest" to "application/manifest+json",
        "woff2" to "font/woff2",
    )

    fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path.let { if (it == "/") "/index.html" else it }
        val resource = path.takeIf { safePath.matches(it) && ".." !in it }
            ?.let { StaticFiles::class.java.getResource("/static$it") }
        val type = types[path.substringAfterLast('.')]
        if (resource == null || type == null) {
            exchange.respond(404, "text/plain", "not found".toByteArray())
        } else {
            exchange.respond(200, type, resource.readBytes())
        }
    }
}

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    responseHeaders.add("Content-Type", contentType)
    responseHeaders.add("Cache-Control", "no-store")
    if (status == 204) {
        sendResponseHeaders(204, -1)
    } else {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }
}
