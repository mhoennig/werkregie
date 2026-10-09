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
    val dashboard = Dashboard(sources(config, mapper), config.windowHours)

    // loopback only: the page shows private work and the backend holds the Gitea tokens
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), config.port), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/api/dashboard") { exchange ->
        exchange.respond(200, "application/json", mapper.writeValueAsBytes(dashboard.snapshot()))
    }
    server.createContext("/") { exchange ->
        val page = if (exchange.requestURI.path == "/") Main::class.java.getResource("/static/index.html") else null
        if (page == null) {
            exchange.respond(404, "text/plain", "not found".toByteArray())
        } else {
            exchange.respond(200, "text/html; charset=utf-8", page.readBytes())
        }
    }
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

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    use {
        responseHeaders.add("Content-Type", contentType)
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }
}

private object Main
