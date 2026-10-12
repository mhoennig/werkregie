package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * The end of a Claude Code transcript: an AskUserQuestion without a result, and Claude's last text
 * with whether the user wrote after it. [lastTextId] is the uuid of the entry holding that text.
 * [prompt] is the user's last input, a prompt or the answers to an AskUserQuestion; [asked] the questions of the open AskUserQuestion.
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

    // how far back the user's last input is searched when the end holds none
    const val SEARCH_BYTES = 8 * 1024 * 1024L

    fun end(path: Path, mapper: ObjectMapper, tailBytes: Long = TAIL_BYTES, searchBytes: Long = SEARCH_BYTES): TranscriptEnd =
        RandomAccessFile(path.toFile(), "r").use { file -> end(file, mapper, tailBytes, searchBytes) }

    private fun end(file: RandomAccessFile, mapper: ObjectMapper, tailBytes: Long, searchBytes: Long): TranscriptEnd {
        val tail = part(file, file.length(), tailBytes)
        val questions = linkedMapOf<String, String?>()
        var prompt: String? = null
        var lastText: String? = null
        var lastTextId: String? = null
        var claudeLast = false
        for (line in tail.lines) {
            val entry = runCatching { mapper.readTree(line) }.getOrNull() ?: continue
            if (entry["isSidechain"]?.asBoolean() == true || entry["isMeta"]?.asBoolean() == true) continue
            promptOf(entry)?.let { prompt = it }
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
                    if (isHuman(content)) claudeLast = false
                }
            }
        }
        var end = tail.start
        while (prompt == null && end > 0 && file.length() - end < searchBytes) {
            val part = part(file, end, tailBytes)
            prompt = part.lines.mapNotNull { line -> runCatching { mapper.readTree(line) }.getOrNull()?.let(::promptOf) }.lastOrNull()
            end = part.start
        }
        val asked = questions.values.filterNotNull().joinToString(" ").takeIf { it.isNotBlank() }
        return TranscriptEnd(questions.isNotEmpty(), lastText, lastTextId, claudeLast && lastText != null, prompt, asked)
    }

    private fun promptOf(entry: JsonNode): String? {
        if (entry.text("type") != "user" || entry["isSidechain"]?.asBoolean() == true || entry["isMeta"]?.asBoolean() == true) return null
        // the summary of a compacted conversation is no input of the user
        if (entry["isCompactSummary"]?.asBoolean() == true) return null
        entry["toolUseResult"]?.get("answers")?.takeIf { it.isObject }?.let { answers ->
            return answers.mapNotNull { it.asText().takeIf { answer -> answer.isNotBlank() && !answer.startsWith("[User dismissed") } }
                .joinToString(" · ").takeIf { it.isNotEmpty() }
        }
        val content = entry["message"]?.get("content")
        return if (isHuman(content)) content?.let(::humanText) else null
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

    /** The whole lines of up to [bytes] before [end], which starts a line; [start] is where the first whole line starts. */
    private class Part(val lines: List<String>, val start: Long)

    private fun part(file: RandomAccessFile, end: Long, bytes: Long): Part {
        val from = maxOf(0, end - bytes)
        val buffer = ByteArray((end - from).toInt())
        file.seek(from)
        file.readFully(buffer)
        // the first line of a part is cut, unless it starts the file
        val skip = if (from == 0L) 0 else buffer.indexOf('\n'.code.toByte()) + 1
        // a part inside one long line holds no whole line; the next part ends where it starts
        if (from > 0 && (skip == 0 || skip == buffer.size)) return Part(emptyList(), from)
        val lines = String(buffer, skip, buffer.size - skip, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        return Part(lines, from + skip)
    }
}
