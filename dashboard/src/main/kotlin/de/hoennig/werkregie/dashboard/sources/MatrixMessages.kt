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
    // arrived after the user last read its room, in Element or any other client
    val unread: Boolean = false,
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

    /** Room ID and event ID of a row, from its link in any of the forms of [link]. */
    fun ids(url: String): Pair<String, String>? {
        val parts = url.substringAfter("#/", "").split('/')
        if (parts.size < 2) return null
        val (room, event) = parts.takeLast(2)
        return if (room.startsWith("!") && event.startsWith("$")) room to event else null
    }

    /**
     * A recovery key, formerly "Security Key", rather than a security phrase:
     * 48 characters of base58, which Element shows in groups of four.
     */
    fun isRecoveryKey(secret: String): Boolean {
        val compact = secret.filterNot { it.isWhitespace() }
        return compact.length == 48 && compact.all { it in BASE58 }
    }

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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
        flags = listOfNotNull(Flag(reason), if (message.unread) Flag(UNREAD) else null),
    )

    /**
     * Until when the user read a room: the later of the newest read receipt and the read marker.
     * An Element without read receipts only moves its read marker `m.fully_read`.
     */
    fun readUntil(receipt: Instant?, marker: Instant?): Instant? = listOfNotNull(receipt, marker).maxOrNull()

    /** The row with its read state as of [readUntil], the time the user last read its room. */
    fun read(item: Item, readUntil: Instant): Item {
        val unread = (item.time ?: Instant.EPOCH).isAfter(readUntil)
        val flags = item.flags.filter { it.kind != UNREAD } + listOfNotNull(if (unread) Flag(UNREAD) else null)
        return if (flags == item.flags) item else item.copy(flags = flags)
    }

    /**
     * The rows of the period, and the last row of each direct chat and configured room however old,
     * so that a quiet conversation keeps one line.
     */
    fun visible(items: Collection<Item>, since: Instant): List<Item> {
        val last = items.filter { item -> item.flags.any { it.kind == "direct" || it.kind == "room" } }
            .groupBy { item -> item.url?.let(::ids)?.first }
            .filterKeys { it != null }
            .values.map { rows -> rows.maxBy { it.time ?: Instant.EPOCH } }
            .toSet()
        return items.filter { (it.time ?: Instant.EPOCH).isAfter(since) || it in last }
    }

    const val UNREAD = "unread"
}
