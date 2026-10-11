package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

data class DashboardConfig(
    val port: Int = 4747,
    // the history shown below "Läuft jetzt" / "Offen"
    val windowHours: Long = 168,
    val claude: ClaudeConfig = ClaudeConfig(),
    val codex: CodexConfig = CodexConfig(),
    val werkators: List<WerkatorConfig> = emptyList(),
    val giteas: List<GiteaConfig> = emptyList(),
    val matrix: MatrixConfig? = null,
    val taskboards: List<TaskboardConfig> = emptyList(),
    val server: ServerConfig = ServerConfig(),
    val relay: RelayConfig = RelayConfig(),
)

/** Mode `server`: the dashboard on a host, fed by relays; `port` above is the port it listens on. */
data class ServerConfig(
    // behind a reverse proxy, e.g. Hostsharing's Apache, loopback is all it needs
    val bindAddress: String = "127.0.0.1",
    // the address the browser and the relays use, e.g. https://regie.example.org/; for `init`
    val publicBaseUrl: String? = null,
    // access token and relay states; default ${XDG_STATE_HOME:-~/.local/state}/werkregie
    val stateDir: String? = null,
    // a relay silent for this long is shown as stale ...
    val relayStaleSeconds: Long = 30,
    // ... and after this long its running and open sessions count as ended
    val relayGoneSeconds: Long = 120,
)

/** Mode `relay`: reads the local agents and reports them to a server. */
data class RelayConfig(
    // the server's public base URL, e.g. https://regie.example.org/
    val serverUrl: String? = null,
    // the server's access token (`werkregie-dashboard token` on the server prints it)
    val token: String? = null,
    val tokenEnv: String? = null,
    val tokenFile: String? = null,
    // how this machine is named on the dashboard; default the host name
    val name: String? = null,
    val intervalSeconds: Long = 5,
)

data class ClaudeConfig(
    val enabled: Boolean = true,
    // one JSON file per session of Claude Code Desktop
    val desktopDir: String = "~/.config/Claude/claude-code-sessions",
    // one JSON file per running Claude Code process, with its busy/idle status
    val liveDir: String = "~/.claude/sessions",
)

data class CodexConfig(
    val enabled: Boolean = true,
    val home: String = "~/.codex",
    // guardian reviews and other spawned threads
    val includeSubagents: Boolean = false,
)

data class WerkatorConfig(
    val name: String,
    val url: String,
    // shown when the instance is unreachable, e.g. "tools/remote --env-file .env.vm4006 port-forward start"
    val hint: String? = null,
    // private repositories are redacted without it; `werkator control-token` prints it
    val controlToken: String? = null,
    val controlTokenEnv: String? = null,
)

data class GiteaConfig(
    val name: String,
    val url: String,
    val token: String? = null,
    val tokenEnv: String? = null,
    // reuses the token of a login in ~/.config/tea/config.yml, so no secret lives in this file:
    // the login's name, or the user name of a login for this url
    val teaLogin: String? = null,
    // "mine": created by, assigned to or review requested from the token's user; "all": every open PR the user can see
    val pullRequests: String = "mine",
    val maxPullRequests: Int = 30,
    // accounts whose approval is no human's, such as werkrevisor's
    val reviewBots: List<String> = listOf("ai-reviewer"),
)

/**
 * A task board with the items assigned to the user, read in the modes `local` and `server`.
 * Only `type: taiga` exists yet; instance and projects come from the configuration alone.
 */
data class TaskboardConfig(
    val name: String,
    val type: String,
    // the address of the board's web pages, e.g. https://taiga.example.org; its API lies below /api/v1
    val url: String,
    val username: String? = null,
    val usernameEnv: String? = null,
    val passwordEnv: String? = null,
    // project slugs, as in the address of a project
    val projects: List<String> = emptyList(),
    // status names, most important first; other open items follow them, closed ones never show
    val statuses: List<String> = emptyList(),
)

/** Matrix with the user's own account, read in the modes `local` and `relay`; `matrix login` signs in. */
data class MatrixConfig(
    // server name or base URL; no default, so that no homeserver is assumed
    val homeserver: String? = null,
    // rooms whose every message shows, by room ID; direct messages and mentions show anyway
    val rooms: List<String> = emptyList(),
    // where a message links to: "matrix.to", "element" or "io.element.desktop" (Element Desktop's own schemes)
    val link: String = "matrix.to",
    val maxMessages: Int = 200,
)

object ConfigLoader {
    val defaultPath: Path = expandHome("~/.config/werkregie/dashboard.yml")

    private val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(path: Path): DashboardConfig =
        if (Files.exists(path)) yaml.readValue(path.toFile()) else DashboardConfig()

    /** The token of the tea login named [login], or else of the login of user [login] on [url]. */
    fun teaToken(login: String, url: String, teaConfig: Path = expandHome("~/.config/tea/config.yml")): String? {
        if (!Files.exists(teaConfig)) return null
        val logins = yaml.readTree(teaConfig.toFile())["logins"]?.toList() ?: return null
        val sameHost = { entry: JsonNode -> entry["url"]?.asText()?.trimEnd('/') == url.trimEnd('/') }
        val match = logins.firstOrNull { it["name"]?.asText() == login }
            ?: logins.firstOrNull { it["user"]?.asText() == login && sameHost(it) }
        return match?.get("token")?.asText()
    }
}

fun stateDir(config: ServerConfig): Path =
    config.stateDir?.let(::expandHome)
        ?: (System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: expandHome("~/.local/state"))
            .resolve("werkregie")

fun expandHome(path: String): Path =
    Path.of(if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path)
