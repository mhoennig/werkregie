package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hoennig.werkregie.dashboard.sources.ClaudeCodeSource
import de.hoennig.werkregie.dashboard.sources.CodexSource
import de.hoennig.werkregie.dashboard.sources.GiteaSource
import de.hoennig.werkregie.dashboard.sources.WerkatorSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    val configPath = args.firstOrNull()?.let(Path::of)
        ?: System.getenv("WERKREGIE_DASHBOARD_CONFIG")?.let(Path::of)
        ?: ConfigLoader.defaultPath
    val config = ConfigLoader.load(configPath)
    val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    val dashboard = Dashboard(sources(config, mapper), config.windowHours, version())

    // loopback only: the page shows private work and the backend holds the Gitea tokens
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), config.port), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/api/dashboard") { exchange ->
        exchange.respond(200, "application/json", mapper.writeValueAsBytes(dashboard.snapshot()))
    }
    server.createContext("/") { exchange -> StaticFiles.serve(exchange) }
    server.start()
    // warm the caches, so the first page load does not wait for the remote servers
    Thread.ofVirtual().start { dashboard.snapshot() }
    println("Werkregie-Dashboard: http://localhost:${config.port}/  (Konfiguration: $configPath)")
}

private fun sources(config: DashboardConfig, mapper: ObjectMapper): List<Source> {
    val http = JsonHttp(mapper)
    return buildList {
        if (config.claude.enabled) add(ClaudeCodeSource(config.claude, mapper))
        if (config.codex.enabled) add(CodexSource(config.codex, mapper))
        config.werkators.forEach { add(WerkatorSource(it, http)) }
        config.giteas.forEach { add(GiteaSource(it, http, mapper)) }
    }
}

private fun version(): String =
    Main::class.java.getResourceAsStream("/werkregie.properties")
        ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
        ?: "dev"

/** The page and its assets from the classpath; anything outside `static/` is not reachable. */
private object StaticFiles {
    private val safePath = Regex("^/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
    private val types = mapOf(
        "html" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "svg" to "image/svg+xml",
        "woff2" to "font/woff2",
    )

    fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path.let { if (it == "/") "/index.html" else it }
        val resource = path.takeIf { safePath.matches(it) && ".." !in it }
            ?.let { Main::class.java.getResource("/static$it") }
        val type = types[path.substringAfterLast('.')]
        if (resource == null || type == null) {
            exchange.respond(404, "text/plain", "not found".toByteArray())
        } else {
            exchange.respond(200, type, resource.readBytes())
        }
    }
}

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    use {
        responseHeaders.add("Content-Type", contentType)
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }
}

private object Main
