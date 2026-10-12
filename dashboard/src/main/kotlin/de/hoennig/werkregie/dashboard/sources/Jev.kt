package de.hoennig.werkregie.dashboard.sources

import de.hoennig.werkregie.dashboard.JevConfig
import de.hoennig.werkregie.dashboard.JsonHttp
import java.time.Duration

/** Rates from 0 to 1 whether the work of an idle agent waits for the user after its last [message]. */
fun interface Judge {
    fun waitsForUser(message: String): Double
}

/** Asks Jev, TypeSafe's System One API, with one `noul` question, as werkrevisor does. */
class JevJudge(private val config: JevConfig, private val apiKey: String, private val http: JsonHttp) : Judge {
    override fun waitsForUser(message: String): Double {
        val body = mapOf(
            "model" to config.model,
            "state" to mapOf("message" to message.takeLast(MAX_CHARS)),
            "questions" to mapOf(QUESTION to mapOf("type" to "noul", "instructions" to INSTRUCTIONS)),
        )
        val answer = http.post(config.endpoint, body, mapOf("Authorization" to "Bearer $apiKey"), Duration.ofSeconds(10))
        return answer["answers"]?.get(QUESTION)?.get("noul")?.takeIf { it.isNumber }?.asDouble()
            ?: throw IllegalStateException("Jev answer without $QUESTION")
    }

    companion object {
        // the end of a message holds its question; 3000 characters are about 1000 tokens
        const val MAX_CHARS = 3000
        private const val QUESTION = "waits_for_user"
        private val INSTRUCTIONS = """
            `message` is the last message of a coding agent to its user; the agent is idle afterwards and does nothing on its own.
            Does the work stay stuck until the user answers or acts, e.g. a question, a decision, a requested manual step or requested output?
            A mere offer or hint, after which the work is complete, does not count. The text may be German or English.
        """.trimIndent()

        /** The judge of [config], or null with the reason, e.g. a missing key. */
        fun of(config: JevConfig, http: JsonHttp, env: (String) -> String? = System::getenv): Pair<Judge?, String?> {
            val key = config.apiKey ?: config.apiKeyEnv?.let(env)?.takeIf { it.isNotBlank() }
                ?: return null to "Jev: kein Schlüssel" + (config.apiKeyEnv?.let { " in $it" } ?: "")
            return JevJudge(config, key, http) to null
        }
    }
}
