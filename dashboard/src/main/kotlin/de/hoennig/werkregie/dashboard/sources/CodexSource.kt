package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import de.hoennig.werkregie.dashboard.CodexConfig
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.expandHome
import org.sqlite.SQLiteConfig
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Codex threads of the ChatGPT Desktop app, its IDE plugins and the CLI: the thread list comes
 * from Codex' own SQLite state (opened read-only), whether a thread is working right now from
 * the tail of its rollout file, where every turn starts with `task_started` and ends with `task_complete`.
 */
class CodexSource(private val config: CodexConfig, private val mapper: ObjectMapper) : Source {
    override val id = "codex"
    override val label = "Codex"
    override val ttl: Duration = Duration.ofSeconds(3)

    private val home = expandHome(config.home)

    override fun fetch(now: Instant, since: Instant): List<Item> {
        val database = home.resolve("state_5.sqlite")
        if (!Files.exists(database)) {
            throw IllegalStateException("$database nicht gefunden (andere Codex-Version?)")
        }
        val sqlite = SQLiteConfig().apply { setReadOnly(true) }
        return sqlite.createConnection("jdbc:sqlite:$database").use { connection ->
            val columns = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(threads)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name")) }
                }
            }
            // `name` holds the short thread name; older versions only have `title`, the first prompt
            val name = if ("name" in columns) "coalesce(nullif(name, ''), title)" else "title"
            val model = if ("model" in columns) "model" else "null"
            val sql = """
                select id, $name as name, cwd, updated_at, archived, source, git_branch, rollout_path, $model as model
                from threads where updated_at >= ? order by updated_at desc limit 300
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, since.epochSecond)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val source = rows.getString("source") ?: ""
                            if (!config.includeSubagents && source.contains("subagent")) continue
                            val updatedAt = Instant.ofEpochSecond(rows.getLong("updated_at"))
                            val running = isRunning(rows.getString("rollout_path"), now)
                            val cwd = rows.getString("cwd")
                            add(
                                Item(
                                    source = "codex",
                                    origin = originOf(source),
                                    kind = "session",
                                    section = if (running) Section.RUNNING else Section.RECENT,
                                    title = (rows.getString("name") ?: "(ohne Titel)").firstLine(),
                                    state = when {
                                        running -> "busy"
                                        rows.getInt("archived") != 0 -> "archived"
                                        else -> "done"
                                    },
                                    time = updatedAt,
                                    project = Projects.of(cwd),
                                    detail = listOfNotNull(
                                        rows.getString("model"),
                                        rows.getString("git_branch")?.let { "⎇ $it" },
                                        Projects.shortPath(cwd),
                                    ).joinToString(" · "),
                                    command = "codex resume ${rows.getString("id")}",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun originOf(source: String) = when (source) {
        "vscode" -> "IDE/Desktop"
        "cli" -> "CLI"
        "exec" -> "exec"
        else -> source.take(20)
    }

    /** A turn still open in a rollout file written to within the last 30 minutes. */
    internal fun isRunning(rolloutPath: String?, now: Instant): Boolean {
        val path = rolloutPath?.let { Path.of(it) } ?: return false
        if (!Files.exists(path)) return false
        val modified = Files.getLastModifiedTime(path).toInstant()
        if (modified.isBefore(now.minus(Duration.ofMinutes(30)))) return false
        return lastTurnEvent(path) == "task_started"
    }

    private fun lastTurnEvent(path: Path): String? {
        val tail = RandomAccessFile(path.toFile(), "r").use { file ->
            val length = minOf(file.length(), 512 * 1024L)
            file.seek(file.length() - length)
            ByteArray(length.toInt()).also { file.readFully(it) }.toString(Charsets.UTF_8)
        }
        return tail.lines().asReversed().firstNotNullOfOrNull { line ->
            if ("\"event_msg\"" !in line || "\"task_" !in line && "\"turn_" !in line) return@firstNotNullOfOrNull null
            val type = runCatching { mapper.readTree(line)["payload"]?.get("type")?.asText() }.getOrNull()
            type?.takeIf { it.startsWith("task_") || it.startsWith("turn_") }
        }
    }
}
