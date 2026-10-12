package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * The end of a Claude Code transcript: an AskUserQuestion without a result, and Claude's last text
 * with whether the user wrote after it. [lastTextId] is the uuid of the entry holding that text.
 * [prompt] is the user's last input, [asked] the questions of the open AskUserQuestion.
 */
data class TranscriptEnd(
    val openQuestion: Boolean,
    val lastText: String?,
    val lastTextId: String?,
    val claudeLast: Boolean,
    val prompt: String? = null,
    val asked: String? = null,
) {
    /** What Claude waits for: the open AskUserQuestion, else its last text if the user did not answer it. */
    val question: String? get() = asked ?: lastText?.takeIf { claudeLast }
}

internal object Transcript {
    // a long session has megabytes of tool output; its end is all that counts
    const val TAIL_BYTES = 512 * 1024L

    fun end(path: Path, mapper: ObjectMapper, tailBytes: Long = TAIL_BYTES): TranscriptEnd {
        val lines = tail(path, tailBytes)
        val questions = linkedMapOf<String, String?>()
        var prompt: String? = null
        var lastText: String? = null
        var lastTextId: String? = null
        var claudeLast = false
        for (line in lines) {
            val entry = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
            if (entry["isSidechain"]?.asBoolean() == true || entry["isMeta"]?.asBoolean() == true) continue
            val content = entry["message"]?.get("content")
            when (entry.text("type")) {
                "assistant" -> content?.filter { it.isObject }?.forEach { block ->
                    when (block.text("type")) {
                        "text" -> block.text("text")?.let {
                            lastText = it.trim()
                            lastTextId = entry.text("uuid")
                            claudeLast = true
                        }
                        "tool_use" -> if (block.text("name") == "AskUserQuestion") block.text("id")?.let { questions[it] = asked(block) }
                    }
                }
                "user" -> {
                    content?.filter { it.isObject && it.text("type") == "tool_result" }
                        ?.forEach { block -> block.text("tool_use_id")?.let(questions::remove) }
                    if (isHuman(content)) {
                        claudeLast = false
                        // the summary of a compacted conversation is no input of the user
                        if (entry["isCompactSummary"]?.asBoolean() != true) prompt = content?.let(::humanText)
                    }
                }
            }
        }
        val asked = questions.values.filterNotNull().joinToString(" ").takeIf { it.isNotBlank() }
        return TranscriptEnd(questions.isNotEmpty(), lastText, lastTextId, claudeLast && lastText != null, prompt, asked)
    }

    private fun asked(block: JsonNode): String? =
        block["input"]?.get("questions")?.mapNotNull { it.text("question") }?.joinToString(" ")?.takeIf { it.isNotBlank() }

    private fun humanText(content: JsonNode): String? = when {
        content.isTextual -> content.asText()
        else -> content.mapNotNull { block -> block.text("text")?.takeIf { block.text("type") == "text" && !it.trimStart().startsWith("<") } }
            .joinToString("\n")
    }.trim().takeIf { it.isNotEmpty() }

    // a prompt of the user, not a tool result and not a message of Claude Code itself, e.g. <command-name>
    private fun isHuman(content: JsonNode?): Boolean = when {
        content == null -> false
        content.isTextual -> !content.asText().trimStart().startsWith("<")
        else -> content.any { it.text("type") == "text" && it.text("text")?.trimStart()?.startsWith("<") == false }
    }

    private fun tail(path: Path, tailBytes: Long): List<String> =
        RandomAccessFile(path.toFile(), "r").use { file ->
            val start = maxOf(0, file.length() - tailBytes)
            val bytes = ByteArray((file.length() - start).toInt())
            file.seek(start)
            file.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).split('\n')
            // the first line of a tail is cut
            if (start > 0) lines.drop(1) else lines
        }.filter { it.isNotBlank() }
}
