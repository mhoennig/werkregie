package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.HttpStatusException
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.TaskboardConfig
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** The open user stories, tasks and issues of the configured Taiga projects that are assigned to the user. */
class TaigaSource(
    private val config: TaskboardConfig,
    private val http: JsonHttp,
    private val env: (String) -> String? = System::getenv,
) : Source {
    override val id = "taiga:${config.name}"
    override val label = "Taiga ${config.name}"
    override val url = config.url.trimEnd('/')
    override val ttl: Duration = Duration.ofSeconds(60)

    private val api = "$url/api/v1"
    private var session: Session? = null

    private data class Session(val token: String, val userId: Long)

    /** What Taiga calls the three kinds, and how a story differs: it has several assignees. */
    internal enum class Kind(val path: String, val assignee: String, val label: String, val page: String) {
        STORY("userstories", "assigned_users", "US", "us"),
        TASK("tasks", "assigned_to", "Task", "task"),
        ISSUE("issues", "assigned_to", "Issue", "issue"),
    }

    @Synchronized
    override fun fetch(now: Instant, since: Instant): List<Item> {
        if (config.projects.isEmpty()) throw IllegalStateException("keine Projekte konfiguriert (projects)")
        return try {
            read(session ?: login())
        } catch (e: HttpStatusException) {
            // an expired token: log in once more
            if (e.status != 401 || session == null) throw e
            session = null
            read(login())
        }
    }

    private fun login(): Session {
        val user = config.username ?: config.usernameEnv?.let(env)
            ?: throw IllegalStateException("kein Benutzer konfiguriert (username oder usernameEnv)")
        val password = config.passwordEnv?.let(env)
            ?: throw IllegalStateException("kein Passwort konfiguriert (passwordEnv)")
        val answer = runCatching { http.post("$api/auth", mapOf("type" to "normal", "username" to user, "password" to password)) }
            .getOrElse { throw IllegalStateException("Anmeldung als $user fehlgeschlagen: ${it.message}") }
        return Session(
            token = answer.text("auth_token") ?: error("Taiga meldet kein Token"),
            userId = answer["id"]?.asLong() ?: error("Taiga meldet keinen Benutzer"),
        ).also { session = it }
    }

    private fun read(session: Session): List<Item> {
        val headers = mapOf("Authorization" to "Bearer ${session.token}", "x-disable-pagination" to "True")
        return config.projects.flatMap { slug ->
            val project = http.get("$api/projects/by_slug?slug=${URLEncoder.encode(slug, Charsets.UTF_8)}", headers)
            val projectId = project["id"]?.asLong() ?: error("Projekt $slug ohne id")
            Kind.entries.flatMap { kind ->
                http.get("$api/${kind.path}?project=$projectId&${kind.assignee}=${session.userId}&status__is_closed=false", headers)
                    .map { item(kind, it, slug) }
            }
        }
    }

    internal fun item(kind: Kind, node: JsonNode, slug: String): Item {
        val ref = node["ref"]?.asLong()
        val status = node["status_extra_info"]?.text("name") ?: "offen"
        val project = node["project_extra_info"]?.text("slug") ?: slug
        return Item(
            source = "taiga",
            origin = config.name,
            kind = kind.name.lowercase(),
            section = Section.OPEN,
            title = node.text("subject") ?: "?",
            state = status,
            time = node.text("modified_date")?.let { OffsetDateTime.parse(it).toInstant() },
            project = project,
            detail = listOfNotNull(kind.label, ref?.let { "#$it" }).joinToString(" "),
            url = ref?.let { "$url/project/$project/${kind.page}/$it" },
            rank = rank(status),
        )
    }

    /** The place of a status in the configured order; any other open status comes after them all. */
    internal fun rank(status: String): Int =
        config.statuses.indexOfFirst { it.equals(status, ignoreCase = true) }.takeIf { it >= 0 } ?: config.statuses.size
}
