package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
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
    val werkrevisors: List<WerkrevisorConfig> = emptyList(),
    val giteas: List<GiteaConfig> = emptyList(),
    val matrix: MatrixConfig? = null,
    val taskboards: List<TaskboardConfig> = emptyList(),
    val server: ServerConfig = ServerConfig(),
    val relay: RelayConfig = RelayConfig(),
    val journal: JournalConfig = JournalConfig(),
)

/** The record of each day's work, the base of a Werkjournal import: kept by the modes `server` and `local`. */
data class JournalConfig(
    val enabled: Boolean = true,
    // the time zone of the user's calendar days, e.g. Europe/Berlin; default the zone of the machine
    val zone: String? = null,
    // entries of a session apart by at most this many minutes count as worked through; read by the relay
    val gapMinutes: Long = 15,
    val keepDays: Long = 400,
    // the Werkjournal customers and projects the work of a day is booked to, by name, as Werkjournal matches them
    val customers: List<JournalCustomer> = emptyList(),
    // where work goes that no project names, e.g. { customer: Intern, project: Sonstiges }
    val fallback: JournalTarget? = null,
)

data class JournalCustomer(val name: String, val projects: List<JournalProject> = emptyList())

/** A Werkjournal project and what belongs to it; a repository is named without its owner, e.g. hs.hsadmin.ng. */
data class JournalProject(
    val name: String,
    val repositories: List<String> = emptyList(),
    // Taiga project slugs; the first one resolves a Taiga ID found in a branch or a title
    val taiga: List<String> = emptyList(),
    // Matrix rooms by ID or name
    val rooms: List<String> = emptyList(),
    // working directories without a repository, e.g. ~/Documents/Hostsharing
    val paths: List<String> = emptyList(),
)

data class JournalTarget(val customer: String, val project: String)

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
    // one transcript per session, <dir>/<project>/<session>.jsonl
    val projectsDir: String = "~/.claude/projects",
    // rates whether the last message of an idle session waits for the user; without it, no chat text goes to Jev
    val jev: JevConfig? = null,
    // shortens the texts in the tooltip of a waiting session; without it, a long text is cut and no chat text goes to the model
    val summary: SummaryConfig? = null,
)

/** TypeSafe's System One API (Jev), as werkrevisor uses it. */
data class JevConfig(
    val endpoint: String = "https://api.typesafe.ai/v1/systemone",
    val model: String = "jev-latest",
    val apiKey: String? = null,
    val apiKeyEnv: String? = null,
    // from this rating up, a session waits for the user; 0.75 kept every real request of the sample of PR#40
    val threshold: Double = 0.75,
)

/** A cheap chat model through OpenRouter, so that the model can be exchanged. */
data class SummaryConfig(
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val model: String = "anthropic/claude-haiku-5.5",
    val apiKey: String? = null,
    val apiKeyEnv: String? = null,
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

/** werkrevisor's statistics service, whose review costs the overview shows. */
data class WerkrevisorConfig(
    val name: String,
    // the address of the statistics page, e.g. https://werkrevisor.example.org
    val url: String,
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
    // the address of the board's web pages, e.g. https://taiga.example.org
    val url: String,
    // Taiga's API, e.g. https://taiga-api.example.org/api/v1; by default the field api of <url>/conf.json, else <url>/api/v1
    val apiUrl: String? = null,
    val username: String? = null,
    val usernameEnv: String? = null,
    val passwordEnv: String? = null,
    // project slugs, as in the address of a project
    val projects: List<String> = emptyList(),
    // status names, most important first; a list in one place holds equal names, e.g. [In Arbeit, In Progress];
    // an open status named nowhere comes first, marked unknown; closed ones never show
    val statuses: List<Any> = emptyList(),
    // open statuses the page hides until the user shows them, e.g. [Erledigt, Verworfen]
    val hiddenStatuses: List<String> = emptyList(),
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

    /**
     * The configuration at [path]; an entry this version does not know is named on [warn] and skipped,
     * since a newer version may already have written it, e.g. while its pull request waits for the deployment.
     */
    fun load(path: Path, warn: (String) -> Unit = System.err::println): DashboardConfig {
        if (!Files.exists(path)) return DashboardConfig()
        val tolerant = yaml.copy().addHandler(object : DeserializationProblemHandler() {
            override fun handleUnknownProperty(
                context: DeserializationContext, parser: JsonParser, deserializer: JsonDeserializer<*>, bean: Any?, name: String,
            ): Boolean {
                // a data class buffers its entries before it reads them, so the parser's path may name the wrong one;
                // the class of the section is always right
                val section = ((bean as? Class<*>) ?: bean?.javaClass)?.simpleName ?: "?"
                warn("Konfiguration $path: unbekannter Eintrag '$name' in $section übergangen")
                parser.skipChildren()
                return true
            }
        })
        return tolerant.readValue(path.toFile())
    }

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
