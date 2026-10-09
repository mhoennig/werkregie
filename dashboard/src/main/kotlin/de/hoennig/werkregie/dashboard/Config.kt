package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
    // reuses the token of a login in ~/.config/tea/config.yml, so no secret lives in this file
    val teaLogin: String? = null,
    // "mine": created by, assigned to or review requested from the token's user; "all": every open PR the user can see
    val pullRequests: String = "mine",
    val maxPullRequests: Int = 30,
)

object ConfigLoader {
    val defaultPath: Path = expandHome("~/.config/werkregie/dashboard.yml")

    private val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(path: Path): DashboardConfig =
        if (Files.exists(path)) yaml.readValue(path.toFile()) else DashboardConfig()

    fun teaToken(login: String, teaConfig: Path = expandHome("~/.config/tea/config.yml")): String? {
        if (!Files.exists(teaConfig)) return null
        val logins = yaml.readTree(teaConfig.toFile())["logins"] ?: return null
        return logins.firstOrNull { it["name"]?.asText() == login }?.get("token")?.asText()
    }
}

fun expandHome(path: String): Path =
    Path.of(if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path)
