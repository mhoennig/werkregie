package de.hoennig.werkregie.dashboard

import java.time.Duration
import java.time.Instant

/**
 * What a visitor without the access token sees: invented sessions, builds and pull requests of
 * invented projects, so the page shows what it does without showing anything real.
 */
object Demo {
    fun snapshot(now: Instant, version: String): Snapshot {
        fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))
        val items = listOf(
            session("claude", "laptop · Desktop", Section.RUNNING, "Checkout-Flow auf Events umstellen", "busy", ago(0), "shop-backend", "claude-opus · worktree checkout-events"),
            session("codex", "laptop · IDE/Desktop", Section.RUNNING, "Offline-Modus der Merkliste", "busy", ago(1), "mobile-app", "gpt · ⎇ feature/offline-wishlist"),
            build("werkator", "build", Section.RUNNING, "feature/checkout-events", "running", ago(2), "shop-backend", "a41c9e07"),
            session("claude", "laptop · Desktop", Section.OPEN, "Flaky Integrationstest analysieren", "idle", ago(12), "shop-backend", "claude-opus · ~/Projekte/shop-backend"),
            session("claude", "laptop · Terminal", Section.OPEN, "Release-Notes 2.4 entwerfen", "idle", ago(48), "docs-site", "~/Projekte/docs-site"),
            pullRequest("#214 Checkout publishes order events", "pending", ago(3), "acme/shop-backend", listOf(
                Badge("werkator", "pending", title = "build running"),
                Badge("werkrevisor Lauf", "pending", title = "review queued"),
            )),
            pullRequest("#88 Merkliste ohne Netz", "success", ago(35), "acme/mobile-app", listOf(
                Badge("werkator", "success", title = "build succeeded after 04:12"),
                Badge("werkrevisor Urteil GREEN", "green", title = "AI review passed"),
            ), flags = listOf(Flag("merge", "approved von ai-reviewer"))),
            pullRequest("#41 Suche im Handbuch", "failure", ago(140), "acme/docs-site", listOf(
                Badge("werkator", "failure", title = "build failed after 00:52"),
                Badge("werkrevisor Urteil ORANGE", "orange", title = "AI findings; a developer approval after checking them unlocks the merge"),
            ), flags = listOf(Flag("approve", "werkrevisor ORANGE"))),
            pullRequest("#17 Dunkles Farbschema", "open", ago(260), "acme/mobile-app", emptyList(), flags = listOf(Flag("review", "niemand angefragt"))),
            pullRequest("#213 Rabatte je Kundengruppe", "failure", ago(75), "acme/shop-backend", listOf(
                Badge("werkator", "success", title = "build succeeded after 03:40"),
                Badge("werkrevisor Lauf", "failure", title = "build failed after 01:10"),
                Badge("werkrevisor Urteil RED", "red", title = "rule violated; a new commit that fixes it is required"),
            ), flags = listOf(Flag("stuck", "RED seit 1 h ohne neuen Commit"))),
            build("werkrevisor", "review", Section.RECENT, "feature/offline-wishlist", "success", ago(34), "mobile-app", "c03e11d2 · 96 s"),
            build("werkator", "build", Section.RECENT, "feature/offline-wishlist", "success", ago(37), "mobile-app", "c03e11d2 · 4 min"),
            Item("gitea", "acme", "activity", Section.RECENT, "3 Commits auf feature/checkout-events", "push", ago(5), "acme/shop-backend", "Order events carry the payment reference"),
            session("codex", "laptop · CLI", Section.RECENT, "Abhängigkeiten aktualisieren", "done", ago(95), "shop-backend", "gpt · ⎇ main"),
            message("Kannst du dir den Checkout-PR heute noch ansehen?", ago(8), "Anna", "direct", unread = true),
            message("@du die Rabattregeln für Großkunden sind jetzt im Handbuch", ago(52), "Team Shop · Ben", "mention", unread = true),
            message("verschlüsselt", ago(130), "Release-Planung · Carla", "room", state = "encrypted"),
            task("story", "Checkout mit Zahlungsreferenz", "In Arbeit", 0, ago(40), "shop", "US #214", "#E47C40"),
            task("task", "Rabattstufen im Handbuch beschreiben", "Bereit", 1, ago(300), "docs", "Task #88", "#40A8E4"),
            task("issue", "Merkliste verliert Einträge ohne Netz", "Neu", 2, ago(1500), "mobile-app", "Issue #41", "#70728F"),
            task("story", "Gutscheine einlösen", "Im Test", -1, ago(90), "shop", "US #220", "#A8E440",
                listOf(Flag("unknown"))),
            task("task", "Altes Theme entfernen", "Erledigt", 3, ago(2000), "mobile-app", "Task #12", "#A9AABC",
                listOf(Flag("hidden"))),
            build("werkator", "build", Section.RECENT, "main", "failed", ago(130), "docs-site", "77b0e5a1 · 52 s"),
            Item("gitea", "acme", "activity", Section.RECENT, "gemergt PR #212 Steuersätze aus der Konfiguration", "merge_pull_request", ago(180), "acme/shop-backend"),
            session("claude", "laptop · Desktop", Section.RECENT, "Steuersätze konfigurierbar machen", "closed", ago(200), "shop-backend", "claude-opus · ~/Projekte/shop-backend"),
        )
        val sources = listOf(
            status("relay:laptop", "Relay laptop", 9, listOf("claude", "codex", "matrix")),
            status("werkator:acme", "Werkator acme", 4),
            status("gitea:acme", "Gitea acme", 5),
            status("taiga:acme", "Taiga acme", 5),
        )
        return Snapshot(version, now, 24, items, sources, Access.DEMO)
    }

    private fun session(source: String, origin: String, section: Section, title: String, state: String, time: Instant, project: String, detail: String) =
        Item(source, origin, "session", section, title, state, time, project, detail)

    private fun build(source: String, kind: String, section: Section, title: String, state: String, time: Instant, project: String, detail: String) =
        Item(source, "acme", kind, section, title, state, time, project, detail)

    private fun pullRequest(title: String, state: String, time: Instant, project: String, badges: List<Badge>, flags: List<Flag> = emptyList()) =
        Item("gitea", "acme", "pr", Section.OPEN, title, state, time, project, "von demo", badges = badges, flags = flags)

    private fun message(title: String, time: Instant, detail: String, reason: String, state: String = "message", unread: Boolean = false) =
        Item("matrix", "example.org", "message", Section.OPEN, title, state, time, detail = detail,
            url = "https://matrix.to/#/!demo$reason:example.org/\$demo",
            flags = listOfNotNull(Flag(reason), if (unread) Flag("unread") else null))

    private fun task(kind: String, title: String, state: String, rank: Int, time: Instant, project: String, detail: String, color: String,
                     flags: List<Flag> = emptyList()) =
        Item("taiga", "acme", kind, Section.OPEN, title, state, time, project, detail, rank = rank, color = color, flags = flags)

    private fun status(id: String, label: String, count: Int, tools: List<String> = emptyList()) =
        SourceStatus(id, label, ok = true, message = null, url = null, count = count, fetchedAt = null, tools = tools)
}
