package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.expandHome
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Claude Code sessions: the Desktop app keeps one JSON file per session (title, cwd, model,
 * last activity); every running Claude Code process, Desktop or terminal, keeps one JSON file
 * per PID with its busy/idle status and, for Desktop, the id of its Desktop session.
 */
class ClaudeCodeSource(
    private val config: ClaudeConfig,
    private val mapper: ObjectMapper,
    private val processes: ProcessTable = ProcessTable.linux,
) : Source {
    override val id = "claude"
    override val label = "Claude Code"
    override val ttl: Duration = Duration.ofSeconds(2)

    private data class Live(
        val sessionId: String,
        val hostSessionId: String?,
        val name: String?,
        val cwd: String?,
        val status: String,
        val updatedAt: Instant?,
    )

    override fun fetch(now: Instant, since: Instant): List<Item> {
        val live = readLive()
        val liveByHost = live.filter { it.hostSessionId != null }.associateBy { it.hostSessionId }
        val desktop = jsonFiles(expandHome(config.desktopDir), depth = 3)
            .mapNotNull { node -> desktopItem(node, liveByHost, since) }
        val terminal = live.filter { it.hostSessionId == null }.map { terminalItem(it) }
        return desktop + terminal
    }

    private fun readLive(): List<Live> =
        jsonFiles(expandHome(config.liveDir), depth = 1)
            .filter { node ->
                val pid = node["pid"]?.asLong() ?: return@filter false
                processes.isAlive(pid, node["procStart"]?.asText())
            }
            .map { node ->
                Live(
                    sessionId = node.text("sessionId") ?: "",
                    hostSessionId = node.text("hostSessionId"),
                    name = node.text("name"),
                    cwd = node.text("cwd"),
                    status = node.text("status") ?: "idle",
                    updatedAt = node.epochMillis("updatedAt"),
                )
            }
            // a resumed session may briefly have two processes: the newest one counts
            .groupBy { it.hostSessionId ?: it.sessionId }
            .map { (_, sessions) -> sessions.maxBy { it.updatedAt ?: Instant.EPOCH } }

    private fun desktopItem(node: JsonNode, liveByHost: Map<String?, Live>, since: Instant): Item? {
        val sessionId = node.text("sessionId") ?: return null
        val live = liveByHost[sessionId]
        val archived = node["isArchived"]?.asBoolean() == true
        val time = listOfNotNull(node.epochMillis("lastActivityAt"), live?.updatedAt).maxOrNull()
        val (section, state) = when {
            live == null && archived -> Section.RECENT to "archived"
            live == null -> Section.RECENT to "closed"
            else -> sectionOf(live.status) to live.status
        }
        if (section == Section.RECENT && (time == null || time.isBefore(since))) return null
        val cwd = node.text("cwd")
        return Item(
            source = "claude",
            origin = "Desktop",
            kind = "session",
            section = section,
            title = node.text("title") ?: live?.name ?: "(ohne Titel)",
            state = state,
            time = time,
            project = Projects.of(cwd),
            detail = listOfNotNull(node.text("model"), Projects.shortPath(cwd)).joinToString(" · "),
            command = node.text("cliSessionId")?.let { resumeCommand(cwd, it) },
            repository = Projects.repository(cwd),
            url = sessionId.takeIf { DESKTOP_SESSION.matches(it) }?.let { "claude://code/continue?session=$it" },
        )
    }

    private fun terminalItem(live: Live) = Item(
        source = "claude",
        origin = "Terminal",
        kind = "session",
        section = sectionOf(live.status),
        title = live.name ?: Projects.of(live.cwd) ?: live.sessionId,
        state = live.status,
        time = live.updatedAt,
        project = Projects.of(live.cwd),
        detail = Projects.shortPath(live.cwd),
        command = resumeCommand(live.cwd, live.sessionId),
        repository = Projects.repository(live.cwd),
    )

    companion object {
        // the session IDs Claude Desktop accepts in claude://code/continue
        private val DESKTOP_SESSION = Regex("^local_[A-Za-z0-9-]{1,64}$")
    }

    private fun sectionOf(status: String) = if (status == "busy") Section.RUNNING else Section.OPEN

    private fun resumeCommand(cwd: String?, sessionId: String) =
        (cwd?.let { "cd '$it' && " } ?: "") + "claude --resume $sessionId"

    private fun jsonFiles(dir: Path, depth: Int): List<JsonNode> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir, depth).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "json" }.toList()
        }.mapNotNull { runCatching { mapper.readTree(it.toFile()) }.getOrNull() }
    }
}

internal fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

internal fun JsonNode.epochMillis(field: String): Instant? =
    get(field)?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }
