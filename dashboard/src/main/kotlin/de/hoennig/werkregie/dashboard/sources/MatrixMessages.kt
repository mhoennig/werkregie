package de.hoennig.werkregie.dashboard.sources

import de.hoennig.werkregie.dashboard.Flag
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import java.time.Instant

/** A Matrix message as the dashboard needs it, free of the client library. */
data class MatrixMessage(
    val eventId: String,
    val roomId: String,
    val roomName: String,
    val direct: Boolean,
    val sender: String,
    val time: Instant,
    // null while the device has no key for it
    val text: String?,
    val mentionsMe: Boolean,
)

/**
 * What shows of Matrix, and how: direct messages, mentions in every room, every message of the
 * configured rooms; each row carries only the start of the text, since a relay reports it to the server.
 */
object MatrixMessages {
    const val SNIPPET_LENGTH = 80

    /** Why [message] shows, as the kind of its marker, or null when it does not. */
    fun reason(message: MatrixMessage, rooms: Set<String>): String? = when {
        message.direct -> "direct"
        message.mentionsMe -> "mention"
        message.roomId in rooms -> "room"
        else -> null
    }

    /**
     * A mention of [userId]: listed in the event's `m.mentions`, or, for clients that do not set it,
     * the user ID or the display name in the text.
     */
    fun mentions(text: String, mentioned: Set<String>?, userId: String, displayName: String?): Boolean =
        userId in mentioned.orEmpty() ||
            text.contains(userId) ||
            (!displayName.isNullOrBlank() && Regex("""(^|\W)${Regex.escape(displayName)}(\W|$)""").containsMatchIn(text))

    fun snippet(text: String): String {
        val line = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(">") }.joinToString(" ")
        return if (line.length <= SNIPPET_LENGTH) line else line.take(SNIPPET_LENGTH - 1).trimEnd() + "…"
    }

    /** The message in Element: matrix.to anywhere, or Element Desktop's URL schemes; IDs as Element writes them. */
    fun link(style: String, roomId: String, eventId: String): String = when (style) {
        "element", "io.element.desktop" -> "$style://vector/webapp/#/room/$roomId/$eventId"
        else -> "https://matrix.to/#/$roomId/$eventId"
    }

    fun item(message: MatrixMessage, reason: String, origin: String, linkStyle: String) = Item(
        source = "matrix",
        origin = origin,
        kind = "message",
        section = Section.OPEN,
        title = message.text?.let(::snippet)?.ifEmpty { null } ?: "verschlüsselt",
        state = if (message.text == null) "encrypted" else "message",
        time = message.time,
        detail = if (message.direct) message.sender else "${message.roomName} · ${message.sender}",
        url = link(linkStyle, message.roomId, message.eventId),
        flags = listOf(Flag(reason)),
    )
}
