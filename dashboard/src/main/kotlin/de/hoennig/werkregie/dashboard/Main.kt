package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.hoennig.werkregie.dashboard.relay.RelayClient
import de.hoennig.werkregie.dashboard.relay.RelayRegistry
import de.hoennig.werkregie.dashboard.sources.ClaudeCodeSource
import de.hoennig.werkregie.dashboard.sources.CodexSource
import de.hoennig.werkregie.dashboard.sources.GiteaSource
import de.hoennig.werkregie.dashboard.sources.MatrixSource
import de.hoennig.werkregie.dashboard.sources.WerkatorSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private const val USAGE = """
werkregie-dashboard [MODUS] [--config DATEI]

  local         (Standard) Dashboard nur auf diesem Rechner: liest Claude Code, Codex,
                Werkator und Gitea selbst, ohne Token, nur über localhost erreichbar
  server        Dashboard auf einem Host: liest Werkator und Gitea, nimmt Relays an,
                zeigt echte Daten nur mit dem Zugriffstoken
  relay         liest Claude Code und Codex und meldet sie an relay.serverUrl
  token         gibt das Zugriffstoken des Servers aus (erzeugt es beim ersten Mal)
  matrix login  meldet Werkregie als eigenes Gerät deines Matrix-Kontos an (matrix.homeserver);
                Benutzer und Passwort fragt es ab, die Sitzung liegt im Zustandsverzeichnis
  matrix verify verifiziert dieses Gerät mit dem Wiederherstellungsschlüssel (früher "Security Key")
                oder der Sicherheitsphrase und holt die Schlüssel älterer Nachrichten aus dem Backup
  forget-relay NAME
                entfernt einen Relay, der sich nicht mehr meldet, vom Server
                (über den laufenden Server, sonst direkt aus relays.json)
  init server|relay [VERZEICHNIS]
                schreibt systemd-Unit (und für den Server .htaccess) zum Installieren
  version       gibt die Version aus

Konfiguration: --config, sonst WERKREGIE_DASHBOARD_CONFIG, sonst ~/.config/werkregie/dashboard.yml
"""

fun main(args: Array<String>) {
    val arguments = args.toMutableList()
    val configPath = arguments.indexOf("--config").takeIf { it >= 0 }?.let { index ->
        arguments.removeAt(index)
        Path.of(arguments.removeAtOrNull(index) ?: fail("--config braucht eine Datei"))
    } ?: System.getenv("WERKREGIE_DASHBOARD_CONFIG")?.let(Path::of) ?: ConfigLoader.defaultPath
    val mode = arguments.removeFirstOrNull() ?: "local"
    if (mode in setOf("-h", "--help", "help")) return println(USAGE.trimIndent())
    if (mode == "version") return println(version())

    val config = ConfigLoader.load(configPath)
    val mapper = mapper()
    when (mode) {
        "local" -> local(config, configPath, mapper)
        "server" -> server(config, configPath, mapper)
        "relay" -> relay(config, mapper)
        "token" -> println(AccessToken(stateDir(config.server).resolve(AccessToken.FILE_NAME)).token)
        "matrix" -> {
            when (arguments.removeFirstOrNull()) {
                "login" -> println(matrixLogin(config))
                "verify" -> println(matrixVerify(config))
                else -> fail("matrix kennt 'login' und 'verify'")
            }
        }
        "forget-relay" -> println(forgetRelay(config, mapper, arguments.removeFirstOrNull() ?: fail("forget-relay braucht den Namen des Relays")))
        "init" -> {
            val target = arguments.removeFirstOrNull() ?: fail("init braucht 'server' oder 'relay'")
            val directory = Path.of(arguments.removeFirstOrNull() ?: "werkregie-$target").toAbsolutePath()
            println(Deployment(launcher(), configPath.toAbsolutePath()).write(target, directory, config.port))
        }
        else -> fail("unbekannter Modus '$mode'\n${USAGE.trimIndent()}")
    }
}

private fun local(config: DashboardConfig, configPath: Path, mapper: ObjectMapper) {
    val http = JsonHttp(mapper)
    val dashboard = Dashboard(localSources(config, mapper) + remoteSources(config, http, mapper), config.windowHours, version())
    // loopback only: the page shows private work and the backend holds the Gitea tokens
    val port = WebServer("127.0.0.1", config.port, dashboard, mapper).start()
    warm(dashboard)
    println("Werkregie-Dashboard (lokal): http://localhost:$port/  (Konfiguration: $configPath)")
}

private fun server(config: DashboardConfig, configPath: Path, mapper: ObjectMapper) {
    val state = stateDir(config.server)
    val accessToken = AccessToken(state.resolve(AccessToken.FILE_NAME)).also { it.token }
    val relays = RelayRegistry(
        state.resolve(RelayRegistry.FILE_NAME),
        mapper,
        stale = Duration.ofSeconds(config.server.relayStaleSeconds),
        gone = Duration.ofSeconds(config.server.relayGoneSeconds),
    )
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { relays.save() } })
    val dashboard = Dashboard(remoteSources(config, JsonHttp(mapper), mapper) + relays, config.windowHours, version())
    val port = WebServer(config.server.bindAddress, config.port, dashboard, mapper, accessToken, relays).start()
    warm(dashboard)
    println("Werkregie-Dashboard (Server): ${config.server.bindAddress}:$port  (Konfiguration: $configPath, Zustand: $state)")
}

private fun relay(config: DashboardConfig, mapper: ObjectMapper) {
    val local = Dashboard(localSources(config, mapper), config.windowHours, version())
    RelayClient(config.relay, local, mapper).runForever()
}

/** Asks the running server to drop the relay; with no server running, edits its state file. */
private fun forgetRelay(config: DashboardConfig, mapper: ObjectMapper, name: String): String {
    if (!RelayRegistry.NAME.matches(name)) fail("ungültiger Relay-Name '$name'")
    val state = stateDir(config.server)
    val token = AccessToken(state.resolve(AccessToken.FILE_NAME)).token
    val host = config.server.bindAddress.let { if (it == "0.0.0.0") "127.0.0.1" else it }
    val request = HttpRequest.newBuilder(URI("http://$host:${config.port}/api/relay/$name"))
        .header("Authorization", "Bearer $token")
        .DELETE()
        .build()
    val status = runCatching {
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }.getOrNull()
    val hint = " Läuft der Relay noch, meldet er sich wieder."
    return when (status) {
        204 -> "Relay $name entfernt.$hint"
        404 -> "Relay $name ist dem Server unbekannt."
        null -> {
            val relays = RelayRegistry(state.resolve(RelayRegistry.FILE_NAME), mapper, Duration.ZERO, Duration.ZERO)
            if (relays.forget(name)) "Server läuft nicht: Relay $name aus relays.json entfernt.$hint"
            else "Server läuft nicht, und relays.json kennt keinen Relay $name."
        }
        else -> fail("Server antwortet mit HTTP $status")
    }
}

internal fun localSources(config: DashboardConfig, mapper: ObjectMapper): List<Source> = buildList {
    if (config.claude.enabled) add(ClaudeCodeSource(config.claude, mapper))
    if (config.codex.enabled) add(CodexSource(config.codex, mapper))
    // no homeserver, no Matrix: nothing is assumed
    config.matrix?.takeIf { it.homeserver != null }?.let { add(MatrixSource(it, stateDir(config.server), mapper)) }
}

private fun matrixVerify(config: DashboardConfig): String {
    val console = System.console() ?: fail("matrix verify braucht ein Terminal")
    return runCatching {
        MatrixSource.verify(stateDir(config.server)) {
            console.readPassword("Wiederherstellungsschlüssel oder Sicherheitsphrase: ")?.let(::String).orEmpty()
        }
    }.getOrElse { fail("Verifizierung fehlgeschlagen: ${it.message}") }
}

private fun matrixLogin(config: DashboardConfig): String {
    val matrix = config.matrix?.takeIf { it.homeserver != null } ?: fail("matrix.homeserver ist nicht konfiguriert")
    val console = System.console() ?: fail("matrix login braucht ein Terminal")
    val user = console.readLine("Matrix-Benutzer (z. B. @name:server): ")?.trim().orEmpty()
    val password = console.readPassword("Passwort: ")?.let(::String).orEmpty()
    if (user.isEmpty() || password.isEmpty()) fail("Benutzer und Passwort nötig")
    val host = java.net.InetAddress.getLocalHost().hostName.substringBefore('.')
    val (userId, deviceId) = runCatching { MatrixSource.login(matrix, stateDir(config.server), user, password, "Werkregie $host") }
        .getOrElse { fail("Anmeldung fehlgeschlagen: ${it.message}") }
    return """
        Angemeldet als $userId, Gerät $deviceId ("Werkregie $host").
        Verschlüsselte Nachrichten liest das Gerät erst, wenn es Schlüssel hat; bis dahin zeigt es sie als "verschlüsselt".
        Läuft der Relay oder der lokale Modus schon, starte ihn neu.
    """.trimIndent()
}

private fun remoteSources(config: DashboardConfig, http: JsonHttp, mapper: ObjectMapper): List<Source> = buildList {
    config.werkators.forEach { add(WerkatorSource(it, http)) }
    config.giteas.forEach { add(GiteaSource(it, http, mapper)) }
}

// fills the caches, so the first page load does not wait for the remote servers
private fun warm(dashboard: Dashboard) = Thread.ofVirtual().start { dashboard.snapshot() }

fun mapper(): ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    // a relay may be newer than its server: fields the server does not know yet are skipped
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

private fun version(): String =
    Deployment::class.java.getResourceAsStream("/werkregie.properties")
        ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
        ?: "dev"

/** The start script of this installation: `<home>/lib/<jar>` → `<home>/bin/werkregie-dashboard`. */
private fun launcher(): Path =
    Path.of(Deployment::class.java.protectionDomain.codeSource.location.toURI())
        .parent.parent.resolve("bin/werkregie-dashboard")

private fun MutableList<String>.removeAtOrNull(index: Int) = if (index < size) removeAt(index) else null

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
