package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * The end of a Claude Code transcript: an AskUserQuestion without a result, and Claude's last text
 * with whether the user wrote after it. [lastTextId] is the uuid of the entry holding that text.
 */
data class TranscriptEnd(
    val openQuestion: Boolean,
    val lastText: String?,
    val lastTextId: String?,
    val claudeLast: Boolean,
)

internal object Transcript {
    // a long session has megabytes of tool output; its end is all that counts
    const val TAIL_BYTES = 512 * 1024L

    fun end(path: Path, mapper: ObjectMapper, tailBytes: Long = TAIL_BYTES): TranscriptEnd {
        val lines = tail(path, tailBytes)
        val questions = mutableSetOf<String>()
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
                        "tool_use" -> if (block.text("name") == "AskUserQuestion") block.text("id")?.let(questions::add)
                    }
                }
                "user" -> {
                    content?.filter { it.isObject && it.text("type") == "tool_result" }
                        ?.forEach { block -> block.text("tool_use_id")?.let(questions::remove) }
                    if (isHuman(content)) claudeLast = false
                }
            }
        }
        return TranscriptEnd(questions.isNotEmpty(), lastText, lastTextId, claudeLast && lastText != null)
    }

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
