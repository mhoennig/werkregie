package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.Flag
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.expandHome
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Claude Code sessions: the Desktop app keeps one JSON file per session (title, cwd, model,
 * last activity); every running Claude Code process, Desktop or terminal, keeps one JSON file
 * per PID with its busy/idle status and, for Desktop, the id of its Desktop session.
 * A session that is not busy gets the marker `ask` when it waits for the user: by its process file,
 * by an unanswered AskUserQuestion in its transcript, or by [judge]'s rating of Claude's last text.
 * The marker carries the user's last input and Claude's question, shortened by [summaries].
 */
class ClaudeCodeSource(
    private val config: ClaudeConfig,
    private val mapper: ObjectMapper,
    private val judge: Judge? = null,
    // why there is no judge although one is configured, e.g. a missing key
    private val judgeProblem: String? = null,
    private val summaries: Summaries = Summaries(null, null, mapper),
    // why there is no summary model although one is configured
    private val summaryProblem: String? = null,
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
        val waitingFor: String?,
        val updatedAt: Instant?,
    )

    private data class Stamp(val size: Long, val modified: Long)

    private val transcriptEnds = ConcurrentHashMap<Path, Pair<Stamp, TranscriptEnd>>()

    // by the uuid of the rated entry, so that each message of Claude costs one call
    private val ratings = ConcurrentHashMap<String, Double>()

    @Volatile private var judgeFailedAt: Instant? = null

    @Volatile private var judgeError: String? = null

    override fun note(): String? = judgeProblem ?: judgeError ?: summaryProblem ?: summaries.error

    override fun fetch(now: Instant, since: Instant): List<Item> {
        val live = readLive()
        val liveByHost = live.filter { it.hostSessionId != null }.associateBy { it.hostSessionId }
        val transcripts = transcriptPaths()
        val asks = Asks(transcripts, now)
        val desktop = jsonFiles(expandHome(config.desktopDir), depth = 3)
            .mapNotNull { node -> desktopItem(node, liveByHost, since, asks) }
        val terminal = live.filter { it.hostSessionId == null }.map { terminalItem(it, asks) }
        transcriptEnds.keys.retainAll(asks.read)
        ratings.keys.retainAll(asks.rated)
        return desktop + terminal
    }

    /** The markers of one fetch; it notes what it read, so the caches keep only that. */
    private inner class Asks(private val transcripts: Map<String, Path>, private val now: Instant) {
        val read = mutableSetOf<Path>()
        val rated = mutableSetOf<String>()

        fun of(sessionId: String?, status: String?, waitingFor: String?): List<Flag> {
            if (status == "busy") return emptyList()
            val end = sessionId?.let(transcripts::get)?.let { path -> transcriptEnd(path)?.also { read.add(path) } }
            val reason = when {
                status == "waiting" -> WAITING_FOR[waitingFor] ?: waitingFor ?: QUESTION_OPEN
                end == null -> return emptyList()
                end.openQuestion -> QUESTION_OPEN
                !end.claudeLast -> return emptyList()
                (rate(end) ?: return emptyList()) >= (config.jev?.threshold ?: 1.0) -> WAITS_FOR_YOU
                else -> return emptyList()
            }
            return listOf(Flag("ask", reason, end?.prompt?.let { summaries.of(it, now) }, end?.question?.let { summaries.of(it, now) }))
        }

        private fun rate(end: TranscriptEnd): Double? {
            val judge = judge ?: return null
            val id = end.lastTextId ?: return null
            val text = end.lastText ?: return null
            rated.add(id)
            ratings[id]?.let { return it }
            if (judgeFailedAt?.plus(JUDGE_RETRY)?.isAfter(now) == true) return null
            return runCatching { judge.waitsForUser(text) }
                .onSuccess {
                    ratings[id] = it
                    judgeFailedAt = null
                    judgeError = null
                }
                .onFailure {
                    judgeFailedAt = now
                    judgeError = "Jev: ${it.message ?: it.javaClass.simpleName}"
                }
                .getOrNull()
        }
    }

    private fun transcriptEnd(path: Path): TranscriptEnd? = runCatching {
        val stamp = Stamp(Files.size(path), Files.getLastModifiedTime(path).toMillis())
        transcriptEnds[path]?.takeIf { it.first == stamp }?.second
            ?: Transcript.end(path, mapper).also { transcriptEnds[path] = stamp to it }
    }.getOrNull()

    private fun transcriptPaths(): Map<String, Path> {
        val dir = expandHome(config.projectsDir)
        if (!Files.isDirectory(dir)) return emptyMap()
        // depth 2: a subagent's transcript lies deeper, in <session>/subagents/
        return Files.walk(dir, 2).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "jsonl" }.toList()
        }.associateBy { it.fileName.toString().removeSuffix(".jsonl") }
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
                    waitingFor = node.text("waitingFor"),
                    updatedAt = node.epochMillis("updatedAt"),
                )
            }
            // a resumed session may briefly have two processes: the newest one counts
            .groupBy { it.hostSessionId ?: it.sessionId }
            .map { (_, sessions) -> sessions.maxBy { it.updatedAt ?: Instant.EPOCH } }

    private fun desktopItem(node: JsonNode, liveByHost: Map<String?, Live>, since: Instant, asks: Asks): Item? {
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
            // an archived session is done, whatever it asked last
            flags = if (archived) emptyList() else asks.of(node.text("cliSessionId") ?: live?.sessionId, live?.status, live?.waitingFor),
        )
    }

    private fun terminalItem(live: Live, asks: Asks) = Item(
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
        flags = asks.of(live.sessionId, live.status, live.waitingFor),
    )

    companion object {
        // the session IDs Claude Desktop accepts in claude://code/continue
        private val DESKTOP_SESSION = Regex("^local_[A-Za-z0-9-]{1,64}$")

        const val QUESTION_OPEN = "Frage offen"
        const val WAITS_FOR_YOU = "wartet auf dich"
        private val WAITING_FOR = mapOf("input needed" to QUESTION_OPEN, "dialog open" to "Dialog offen")

        // a failing Jev is not asked on every poll
        private val JUDGE_RETRY: Duration = Duration.ofSeconds(60)
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

internal fun JsonNode.number(field: String): Double? = get(field)?.takeIf { it.isNumber }?.asDouble()

internal fun JsonNode.epochMillis(field: String): Instant? =
    get(field)?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }
