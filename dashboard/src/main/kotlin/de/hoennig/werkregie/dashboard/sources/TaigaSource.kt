package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import de.hoennig.werkregie.dashboard.Flag
import de.hoennig.werkregie.dashboard.HttpStatusException
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.Source
import de.hoennig.werkregie.dashboard.TaskboardConfig
import de.hoennig.werkregie.dashboard.journal.Story
import de.hoennig.werkregie.dashboard.journal.TaigaStories
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional

/** The open user stories, tasks and issues of the configured Taiga projects that are assigned to the user. */
class TaigaSource(
    private val config: TaskboardConfig,
    private val http: JsonHttp,
    private val env: (String) -> String? = System::getenv,
) : Source, TaigaStories {
    override val id = "taiga:${config.name}"
    override val label = "Taiga ${config.name}"
    override val url = config.url.trimEnd('/')
    override val ttl: Duration = Duration.ofSeconds(60)

    private var api: String? = config.apiUrl?.trimEnd('/')
    private var session: Session? = null

    // each place of the order holds one or more equal names, compared regardless of case
    private val order: List<Set<String>> = config.statuses.map { entry ->
        (if (entry is List<*>) entry.map { it.toString() } else listOf(entry.toString())).map { it.lowercase() }.toSet()
    }
    private val hidden = config.hiddenStatuses.map { it.lowercase() }.toSet()

    private data class Session(val token: String, val userId: Long)

    /** What Taiga calls the three kinds, and how a story differs: it has several assignees. */
    internal enum class Kind(val path: String, val assignee: String, val label: String, val page: String) {
        STORY("userstories", "assigned_users", "US", "us"),
        TASK("tasks", "assigned_to", "Task", "task"),
        ISSUE("issues", "assigned_to", "Issue", "issue"),
    }

    // a ref and its story do not change; a story's subject rarely does, and the proposal of a day may name the old one
    private val stories = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, Optional<Story>>()

    @Synchronized
    override fun fetch(now: Instant, since: Instant): List<Item> {
        if (config.projects.isEmpty()) throw IllegalStateException("keine Projekte konfiguriert (projects)")
        return authorized { api, session -> read(api, session) }
    }

    /**
     * The story a ref of project [slug] belongs to: a story itself, the story of a task, or an issue or a task without story.
     * Null when Taiga does not know the ref; an unreachable Taiga is asked again next time.
     */
    override fun story(slug: String, ref: Long): Story? {
        stories[slug to ref]?.let { return it.orElse(null) }
        val story = synchronized(this) { authorized { api, session -> resolve(api, session, slug, ref) } }
        stories[slug to ref] = Optional.ofNullable(story)
        return story
    }

    fun holds(slug: String) = config.projects.any { it.equals(slug, ignoreCase = true) }

    private fun <T> authorized(call: (String, Session) -> T): T {
        val user = config.username ?: config.usernameEnv?.let(env)
            ?: throw IllegalStateException("kein Benutzer konfiguriert (username oder usernameEnv)")
        val password = config.passwordEnv?.let(env)
            ?: throw IllegalStateException("kein Passwort konfiguriert (passwordEnv)")
        val api = api ?: discoverApi().also { api = it }
        return try {
            call(api, session ?: login(api, user, password))
        } catch (e: HttpStatusException) {
            // an expired token: log in once more
            if (e.status != 401 || session == null) throw e
            session = null
            call(api, login(api, user, password))
        }
    }

    private fun resolve(api: String, session: Session, slug: String, ref: Long): Story? {
        val headers = mapOf("Authorization" to "Bearer ${session.token}")
        val resolved = try {
            http.get("$api/resolver?project=${URLEncoder.encode(slug, Charsets.UTF_8)}&ref=$ref", headers)
        } catch (e: HttpStatusException) {
            if (e.status == 404) return null else throw e
        }
        fun story(kind: Kind, node: JsonNode) = Story(
            slug = slug,
            ref = node["ref"]?.asLong() ?: ref,
            kind = kind.label,
            subject = node.text("subject") ?: "?",
            url = "$url/project/$slug/${kind.page}/${node["ref"]?.asLong() ?: ref}",
        )
        resolved["us"]?.asLong()?.let { return story(Kind.STORY, http.get("$api/userstories/$it", headers)) }
        resolved["issue"]?.asLong()?.let { return story(Kind.ISSUE, http.get("$api/issues/$it", headers)) }
        val task = resolved["task"]?.asLong()?.let { http.get("$api/tasks/$it", headers) } ?: return null
        // a task counts for its story, so that the work on a story is one entry whichever task it was
        return task["user_story_extra_info"]?.takeIf { it.isObject }?.let { story(Kind.STORY, it) } ?: story(Kind.TASK, task)
    }

    /**
     * Where Taiga's own web app finds the API: the field `api` of its `conf.json`, possibly on a host of its own;
     * a Taiga without that file serves its API below its pages.
     * A network error is not cached, so the next fetch asks again.
     */
    private fun discoverApi(): String =
        try {
            http.get("$url/conf.json").text("api")?.trimEnd('/') ?: "$url/api/v1"
        } catch (e: HttpStatusException) {
            "$url/api/v1"
        } catch (e: JsonProcessingException) {
            // a web server that answers every path with the web app's page
            "$url/api/v1"
        }

    private fun login(api: String, user: String, password: String): Session {
        val answer = runCatching { http.post("$api/auth", mapOf("type" to "normal", "username" to user, "password" to password)) }
            .getOrElse { throw IllegalStateException("Anmeldung als $user fehlgeschlagen: ${it.message}") }
        return Session(
            token = answer.text("auth_token") ?: error("Taiga meldet kein Token"),
            userId = answer["id"]?.asLong() ?: error("Taiga meldet keinen Benutzer"),
        ).also { session = it }
    }

    private fun read(api: String, session: Session): List<Item> {
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
        val key = status.lowercase()
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
            color = node["status_extra_info"]?.text("color")?.takeIf { COLOR.matches(it) },
            flags = when {
                key in hidden -> listOf(Flag("hidden"))
                order.none { key in it } -> listOf(Flag("unknown"))
                else -> emptyList()
            },
        )
    }

    /**
     * The place of a status in the configured order. A status named nowhere comes first, so that the user adds it;
     * a hidden one comes last, for when the page shows it.
     */
    internal fun rank(status: String): Int {
        val key = status.lowercase()
        if (key in hidden) return order.size
        return order.indexOfFirst { key in it }
    }

    private companion object {
        // the page puts it into a style attribute, so nothing but a colour gets through
        val COLOR = Regex("^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    }
}
