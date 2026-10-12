package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.hoennig.werkregie.dashboard.JsonHttp
import de.hoennig.werkregie.dashboard.SummaryConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Shortens a [text] to at most [maxChars] characters, keeping its language. */
fun interface Summarizer {
    fun summarize(text: String, maxChars: Int): String
}

/** A chat model through OpenRouter's chat API, by default Haiku. */
class ChatSummarizer(private val config: SummaryConfig, private val apiKey: String, private val http: JsonHttp) : Summarizer {
    override fun summarize(text: String, maxChars: Int): String {
        // Haiku 5.5 overshoots a limit by about a fifth
        val target = maxChars * 3 / 4
        val body = mapOf(
            "model" to config.model,
            // a summary of 160 characters is about 50 tokens
            "max_tokens" to 200,
            // Haiku 5.5 thinks by default on OpenRouter and spends all tokens on it
            "reasoning" to mapOf("enabled" to false),
            "messages" to listOf(
                mapOf("role" to "system", "content" to INSTRUCTIONS.replace("{max}", "$target").replace("{words}", "${target / 8}")),
                // without the tags, Haiku answers the message instead of shortening it
                mapOf("role" to "user", "content" to "<message>\n$text\n</message>"),
            ),
        )
        val answer = http.post(config.endpoint, body, mapOf("Authorization" to "Bearer $apiKey"), Duration.ofSeconds(15))
        return answer["choices"]?.get(0)?.get("message")?.text("content")?.trim()
            ?: throw IllegalStateException("answer without text")
    }

    companion object {
        private val INSTRUCTIONS = """
            You shorten chat messages for a tooltip. The user turn holds one message between <message> tags, from a user or a coding agent.
            Do not answer, judge or continue it: restate its gist in at most {max} characters, about {words} words, in the language of the message.
            Keep key terms and names; a question stays a question, a request stays a request.
            Reply with the shortened message only, without tags, quotes or preface.
        """.trimIndent()

        /** The summarizer of [config], or null with the reason, e.g. a missing key. */
        fun of(config: SummaryConfig, http: JsonHttp, env: (String) -> String? = System::getenv): Pair<Summarizer?, String?> {
            val key = config.apiKey ?: config.apiKeyEnv?.let(env)?.takeIf { it.isNotBlank() }
                ?: return null to "Zusammenfassung: kein Schlüssel" + (config.apiKeyEnv?.let { " in $it" } ?: "")
            return ChatSummarizer(config, key, http) to null
        }
    }
}

/**
 * The texts of a tooltip, at most [maxChars] characters each: a longer text is summarized once
 * and kept by its hash in [file], so that neither the polling nor a restart asks the model again.
 * Without a model, or while it fails, a longer text is cut.
 */
class Summaries(
    private val summarizer: Summarizer?,
    private val file: Path?,
    private val mapper: ObjectMapper,
    private val maxChars: Int = 160,
) {
    data class Entry(val summary: String, val usedAt: Long)

    private val entries: MutableMap<String, Entry> = load()

    @Volatile private var failedAt: Instant? = null

    @Volatile var error: String? = null
        private set

    @Synchronized
    fun of(text: String, now: Instant): String {
        val clean = text.trim().replace(WHITESPACE, " ")
        if (clean.length <= maxChars) return clean
        val key = hash(clean)
        entries[key]?.let { entry ->
            if (now.epochSecond - entry.usedAt > REFRESH.seconds) save(key, entry.copy(usedAt = now.epochSecond), now)
            return entry.summary
        }
        val summarizer = summarizer ?: return cut(clean)
        if (failedAt?.plus(RETRY)?.isAfter(now) == true) return cut(clean)
        return runCatching { cut(summarize(summarizer, clean)) }
            .onSuccess {
                save(key, Entry(it, now.epochSecond), now)
                failedAt = null
                error = null
            }
            .onFailure {
                failedAt = now
                error = "Zusammenfassung: ${it.message ?: it.javaClass.simpleName}"
            }
            .getOrElse { cut(clean) }
    }

    // a long instruction may come back over the limit; shortening the answer once more costs little
    private fun summarize(summarizer: Summarizer, text: String): String {
        val summary = summarizer.summarize(excerpt(text), maxChars).replace(WHITESPACE, " ")
        return if (summary.length <= maxChars) summary else summarizer.summarize(summary, maxChars).replace(WHITESPACE, " ")
    }

    private fun excerpt(text: String) =
        if (text.length <= MAX_INPUT) text else text.take(MAX_INPUT / 2) + " … " + text.takeLast(MAX_INPUT / 2)

    private fun cut(text: String) = if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"

    private fun save(key: String, entry: Entry, now: Instant) {
        entries[key] = entry
        entries.values.removeIf { now.epochSecond - it.usedAt > KEEP.seconds }
        val file = file ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            mapper.writeValue(temp.toFile(), entries)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load(): MutableMap<String, Entry> =
        file?.takeIf { Files.exists(it) }
            ?.let { runCatching { mapper.readValue<Map<String, Entry>>(it.toFile()) }.getOrNull() }
            ?.toMutableMap() ?: mutableMapOf()

    companion object {
        const val FILE_NAME = "summaries.json"

        // the start of a long prompt and the end of a long answer say most; 4000 characters are about 1300 tokens
        const val MAX_INPUT = 4000

        private val KEEP: Duration = Duration.ofDays(1)
        // the day of use is enough to keep an entry; it need not be written on every poll
        private val REFRESH: Duration = Duration.ofHours(1)
        private val RETRY: Duration = Duration.ofSeconds(60)
        private val WHITESPACE = Regex("\\s+")

        private fun hash(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
