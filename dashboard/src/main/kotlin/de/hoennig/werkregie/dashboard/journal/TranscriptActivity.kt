package de.hoennig.werkregie.dashboard.journal

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.ClaudeConfig
import de.hoennig.werkregie.dashboard.CodexConfig
import de.hoennig.werkregie.dashboard.expandHome
import de.hoennig.werkregie.dashboard.sources.Projects
import org.sqlite.SQLiteConfig
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * The active time of the local sessions, from the time stamps in their transcripts: Claude Code writes one
 * JSON line per entry to `~/.claude/projects/<project>/<session>.jsonl`, Codex to the rollout file of a thread.
 * Entries apart by at most [gap] form one span. A transcript stays after its process ended,
 * so a closed terminal session counts too.
 * Each file is read only from where the last read stopped, and only its top-level fields are parsed,
 * since a single line may hold megabytes of tool output.
 */
class TranscriptActivity(
    private val claude: ClaudeConfig,
    private val codex: CodexConfig,
    private val gap: Duration,
    private val mapper: ObjectMapper,
    // the relay reports every few seconds; the active time need not be that fresh
    private val rescan: Duration = Duration.ofSeconds(30),
) : ActivitySource {
    private class Transcript(
        var offset: Long = 0,
        var spans: List<Span> = emptyList(),
        var title: String? = null,
        var cwd: String? = null,
        var origin: String? = null,
        val branches: MutableSet<String> = linkedSetOf(),
    )

    private val transcripts = HashMap<Path, Transcript>()
    private var scannedAt: Instant? = null
    private var last: List<Activity> = emptyList()

    @Synchronized
    override fun activity(now: Instant, since: Instant): List<Activity> {
        scannedAt?.let { if (it.plus(rescan).isAfter(now)) return last }
        val seen = mutableSetOf<Path>()
        val activity = buildList {
            if (claude.enabled) addAll(claude(since, seen))
            if (codex.enabled) addAll(runCatching { codex(since, seen) }.getOrDefault(emptyList()))
        }
        // a transcript untouched since the window began need not be kept in memory
        transcripts.keys.retainAll(seen)
        scannedAt = now
        last = activity
        return activity
    }

    private fun claude(since: Instant, seen: MutableSet<Path>): List<Activity> {
        val root = expandHome(claude.projectsDir)
        if (!Files.isDirectory(root)) return emptyList()
        // depth 2: a session's subagents keep their transcripts one level deeper, in <session>/subagents
        val files = Files.walk(root, 2).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "jsonl" && it.parent.parent == root }.toList()
        }
        return files.filter { modifiedSince(it, since) }.mapNotNull { file ->
            seen.add(file)
            val transcript = read(file) { field, parser, entry -> claudeField(field, parser, entry) }
            activityOf("claude", file.nameWithoutExtension, transcript, since)
        }
    }

    private fun claudeField(field: String, parser: JsonParser, entry: Transcript) {
        when (field) {
            "cwd" -> entry.cwd = parser.valueAsString ?: entry.cwd
            "gitBranch" -> parser.valueAsString?.takeIf { it.isNotBlank() && it != "HEAD" }?.let { entry.branches += it }
            "entrypoint" -> entry.origin = parser.valueAsString ?: entry.origin
            // the name the user or Claude gave the session; the newest one counts
            "customTitle" -> entry.title = parser.valueAsString?.takeIf { it.isNotBlank() } ?: entry.title
        }
    }

    private fun codex(since: Instant, seen: MutableSet<Path>): List<Activity> {
        val database = expandHome(codex.home).resolve("state_5.sqlite")
        if (!Files.exists(database)) return emptyList()
        val sqlite = SQLiteConfig().apply { setReadOnly(true) }
        return sqlite.createConnection("jdbc:sqlite:$database").use { connection ->
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(threads)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name")) }
                }
            }
            val name = if ("name" in columns) "coalesce(nullif(name, ''), title)" else "title"
            val origin = if ("git_origin_url" in columns) "git_origin_url" else "null"
            val sql = """
                select id, $name as name, cwd, source, git_branch, rollout_path, $origin as origin
                from threads where updated_at >= ? order by updated_at desc limit 300
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, since.epochSecond)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val source = rows.getString("source") ?: ""
                            if (!codex.includeSubagents && source.contains("subagent")) continue
                            val file = rows.getString("rollout_path")?.let { Path.of(it) } ?: continue
                            if (!Files.exists(file)) continue
                            seen.add(file)
                            val transcript = read(file) { _, _, _ -> }
                            val cwd = rows.getString("cwd")
                            transcript.title = rows.getString("name")?.lineSequence()?.firstOrNull()?.trim()
                            transcript.cwd = cwd
                            transcript.origin = source.takeIf { it.isNotBlank() }
                            rows.getString("git_branch")?.takeIf { it.isNotBlank() }?.let { transcript.branches += it }
                            val repository = Projects.repositoryName(rows.getString("origin"))
                            activityOf("codex", rows.getString("id"), transcript, since, repository)?.let { add(it) }
                        }
                    }
                }
            }
        }
    }

    private fun activityOf(tool: String, session: String, transcript: Transcript, since: Instant, repository: String? = null): Activity? {
        val spans = Spans.since(transcript.spans, since).takeIf { it.isNotEmpty() } ?: return null
        return Activity(
            tool = tool,
            session = session,
            origin = transcript.origin,
            title = transcript.title,
            cwd = transcript.cwd,
            project = Projects.of(transcript.cwd),
            repository = repository ?: Projects.repository(transcript.cwd),
            branches = transcript.branches.toList(),
            spans = spans,
        )
    }

    private fun modifiedSince(file: Path, since: Instant) =
        runCatching { !Files.getLastModifiedTime(file).toInstant().isBefore(since) }.getOrDefault(false)

    /** Reads the lines added to [file] since the last read; [field] takes the top-level fields besides `timestamp`. */
    private fun read(file: Path, field: (String, JsonParser, Transcript) -> Unit): Transcript {
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        val transcript = transcripts[file]?.takeIf { it.offset <= size } ?: Transcript().also { transcripts[file] = it }
        if (transcript.offset == size) return transcript
        val times = mutableListOf<Span>()
        Files.newInputStream(file).use { input ->
            input.skipNBytes(transcript.offset)
            val line = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var position = transcript.offset
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] != NEWLINE) continue
                    line.write(buffer, start, index - start)
                    parse(line.toByteArray(), transcript, field)?.let { times += Span(it, it) }
                    line.reset()
                    start = index + 1
                    // only a complete line counts: a line still being written is read again next time
                    transcript.offset = position + start
                }
                line.write(buffer, start, count - start)
                position += count
            }
        }
        if (times.isNotEmpty()) transcript.spans = Spans.merge(transcript.spans + times, gap)
        return transcript
    }

    private fun parse(line: ByteArray, transcript: Transcript, field: (String, JsonParser, Transcript) -> Unit): Instant? {
        if (line.isEmpty()) return null
        return runCatching {
            mapper.factory.createParser(line).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) return null
                var time: Instant? = null
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    val name = parser.currentName()
                    val value = parser.nextToken()
                    when {
                        value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY -> parser.skipChildren()
                        name == "timestamp" -> time = runCatching { Instant.parse(parser.valueAsString) }.getOrNull()
                        value == JsonToken.VALUE_STRING -> field(name, parser, transcript)
                    }
                }
                time
            }
        }.getOrNull()
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
    }
}
