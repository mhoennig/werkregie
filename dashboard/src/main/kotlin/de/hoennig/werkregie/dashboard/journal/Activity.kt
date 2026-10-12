package de.hoennig.werkregie.dashboard.journal

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A stretch of time in which a session was worked in; a single entry gives a span of length zero. */
data class Span(val from: Instant, val to: Instant)

/** The active time of one agent session, e.g. a Claude Code session or a Codex thread. */
data class Activity(
    val tool: String, // claude | codex
    val session: String,
    // the client, e.g. claude-desktop or cli; on the server prefixed with the relay, as the items are
    val origin: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    val project: String? = null,
    val repository: String? = null,
    // the branches the session worked on, e.g. claude-code/journal-archive; they often name a task
    val branches: List<String> = emptyList(),
    val spans: List<Span> = emptyList(),
)

/** Where the active time of the sessions comes from: the local transcripts, or the relays on a server. */
fun interface ActivitySource {
    fun activity(now: Instant, since: Instant): List<Activity>
}

object Spans {
    /** Sorts [spans] and joins those apart by at most [gap]; with a gap of zero only the overlapping and touching ones. */
    fun merge(spans: List<Span>, gap: Duration = Duration.ZERO): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans.sortedBy { it.from }) {
            val last = merged.lastOrNull()
            if (last != null && !span.from.isAfter(last.to.plus(gap))) {
                merged[merged.size - 1] = Span(last.from, maxOf(last.to, span.to))
            } else {
                merged += span
            }
        }
        return merged
    }

    /** The parts of [span] on each local day of [zone]; a span over midnight falls into two days. */
    fun byDay(span: Span, zone: ZoneId): Map<LocalDate, Span> {
        val parts = linkedMapOf<LocalDate, Span>()
        var from = span.from
        while (true) {
            val day = from.atZone(zone).toLocalDate()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            if (!span.to.isAfter(end) || span.to == from) {
                parts[day] = Span(from, span.to)
                return parts
            }
            parts[day] = Span(from, end)
            from = end
        }
    }

    /** [spans] cut to the time from [since] on; spans entirely before it are dropped. */
    fun since(spans: List<Span>, since: Instant): List<Span> =
        spans.filter { !it.to.isBefore(since) }.map { if (it.from.isBefore(since)) Span(since, it.to) else it }
}
